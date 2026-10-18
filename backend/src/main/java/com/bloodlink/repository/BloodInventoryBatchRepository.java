package com.bloodlink.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.enums.BloodType;

import jakarta.persistence.LockModeType;

/**
 * Batches of stock (V18, the source of all stock since V19).
 *
 * <h2>One definition of "usable"</h2>
 * Every query that counts stock takes {@code today} as a parameter, supplied by
 * StockPolicy from the UTC clock, and applies the same test: a batch is usable when
 * {@code expiryDate >= :today} (usable through the whole of its expiry date) and
 * expired when {@code expiryDate < :today}. No query reads the database's own
 * current_date, so "today" means the same thing in every figure, and tests can fix it.
 *
 * A bare {@link Repository} rather than JpaRepository on purpose: batches are never
 * deleted (a used-up batch stays as history), so this repository inherits no delete
 * methods to be called by mistake.
 */
public interface BloodInventoryBatchRepository extends Repository<BloodInventoryBatch, UUID> {

    <S extends BloodInventoryBatch> S save(S batch);

    Optional<BloodInventoryBatch> findById(UUID id);

    // ---------- one bank's stock, per blood group ----------

    /**
     * One bank's stock, one row per blood group it has ever held a batch of. Groups
     * with no batches at all are absent; the service reports them as zero.
     *
     * usableUnits: units in batches not yet expired. expiringSoonUnits: of those, the
     * ones expiring on or before {@code cutoff} (today + 28 days). expiredUnits: units
     * still on the shelf in expired batches, which are not counted as stock.
     * liveBatches: batches with any units left, expired ones included.
     * earliestUsableExpiry: the soonest expiry among usable batches with units left.
     * lastUpdated: the most recent change to any of the group's batches.
     */
    @Query("""
        select b.bloodType as bloodType,
               coalesce(sum(case when b.expiryDate >= :today then b.unitsRemaining else 0 end), 0) as usableUnits,
               coalesce(sum(case when b.expiryDate >= :today and b.expiryDate <= :cutoff
                                 then b.unitsRemaining else 0 end), 0) as expiringSoonUnits,
               coalesce(sum(case when b.expiryDate < :today then b.unitsRemaining else 0 end), 0) as expiredUnits,
               coalesce(sum(case when b.unitsRemaining > 0 then 1 else 0 end), 0) as liveBatches,
               min(case when b.expiryDate >= :today and b.unitsRemaining > 0 then b.expiryDate end) as earliestUsableExpiry,
               max(b.lastUpdated) as lastUpdated
          from BloodInventoryBatch b
         where b.bloodBank.id = :bloodBankId
         group by b.bloodType
        """)
    List<GroupStockRow> summariseGroupsForBank(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("today") LocalDate today,
        @Param("cutoff") LocalDate cutoff
    );

    /** One row of {@link #summariseGroupsForBank}. */
    interface GroupStockRow {
        BloodType getBloodType();
        long getUsableUnits();
        long getExpiringSoonUnits();
        long getExpiredUnits();
        long getLiveBatches();
        LocalDate getEarliestUsableExpiry();
        Instant getLastUpdated();
    }

    /** Usable units of one group at one bank: the figure every stock rule judges. */
    @Query("""
        select coalesce(sum(b.unitsRemaining), 0) from BloodInventoryBatch b
         where b.bloodBank.id = :bloodBankId and b.bloodType = :bloodType and b.expiryDate >= :today
        """)
    long sumUsableUnits(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("bloodType") BloodType bloodType,
        @Param("today") LocalDate today
    );

    /** Units of one group at one bank still on the shelf in expired batches. */
    @Query("""
        select coalesce(sum(b.unitsRemaining), 0) from BloodInventoryBatch b
         where b.bloodBank.id = :bloodBankId and b.bloodType = :bloodType and b.expiryDate < :today
        """)
    long sumExpiredUnits(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("bloodType") BloodType bloodType,
        @Param("today") LocalDate today
    );

    // ---------- issuing stock: the FEFO lock ----------

    /**
     * Locks one bank's usable batches of one blood group, in issue order — earliest
     * expiry first, then id — and returns them. Called inside the contribution
     * transaction, after the request row has been locked (BloodRequestRepository
     * .findByIdForUpdate): request first, then batches, is the one lock order in the
     * system, which is what makes a deadlock impossible.
     *
     * The ORDER BY is what PostgreSQL locks the rows in, so two transactions taking
     * the same bank's batches always queue in the same order rather than each
     * holding one the other needs. The order can never change underneath a waiting
     * transaction because expiry_date and id are fixed (V18 trigger). A batch emptied
     * by the transaction that held the lock no longer matches
     * {@code unitsRemaining > 0} when the waiter re-reads it, so it drops out.
     *
     * Serves from idx_batches_fefo, whose predicate is the {@code unitsRemaining > 0}
     * clause here.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select b from BloodInventoryBatch b
         where b.bloodBank.id = :bloodBankId and b.bloodType = :bloodType
           and b.unitsRemaining > 0 and b.expiryDate >= :today
         order by b.expiryDate asc, b.id asc
        """)
    List<BloodInventoryBatch> lockUsableBatchesInIssueOrder(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("bloodType") BloodType bloodType,
        @Param("today") LocalDate today
    );

    /**
     * Locks one batch by id, whatever its expiry date or units left, and returns it:
     * the write-off's lock (Business Rule 24). The same PESSIMISTIC_WRITE as
     * {@link #lockUsableBatchesInIssueOrder}, which on PostgreSQL is
     * {@code FOR NO KEY UPDATE}: a contribution wanting this batch waits for the
     * write-off to commit and then re-reads the units it left, and vice versa.
     *
     * Expired and empty batches are deliberately not filtered out here — writing off
     * an expired batch is the main reason this exists, and an empty one must be found
     * so the caller can say it is empty rather than that it does not exist. A
     * write-off holds no other row lock, so it can never be one half of a deadlock
     * with a contribution (request first, batches second).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BloodInventoryBatch b where b.id = :id")
    Optional<BloodInventoryBatch> lockById(@Param("id") UUID id);

    // ---------- across banks ----------

    /**
     * Banks holding at least {@code units} usable units of one group: the banks a new
     * hospital request could be met from, and so the ones alerted about it
     * (NotificationService.notifyNewRequest). Expired units do not count.
     */
    @Query("""
        select b.bloodBank.id from BloodInventoryBatch b
         where b.bloodType = :bloodType and b.expiryDate >= :today
         group by b.bloodBank.id
        having sum(b.unitsRemaining) >= :units
        """)
    List<UUID> findBankIdsWithUsableUnitsAtLeast(
        @Param("bloodType") BloodType bloodType,
        @Param("units") long units,
        @Param("today") LocalDate today
    );

    // ---------- Admin Reports: stock overview (Business Rule 23) ----------

    /** Usable units per blood group across every bank. A group no bank holds usable
     *  stock of is simply absent; the service reports it as zero. */
    @Query("""
        select b.bloodType as bloodType, sum(b.unitsRemaining) as total from BloodInventoryBatch b
         where b.expiryDate >= :today
         group by b.bloodType
        """)
    List<BloodTypeCount> sumUsableUnitsByBloodType(@Param("today") LocalDate today);

    /** Usable units per (bank, group), every bank and group that has any. The service
     *  judges each one's Critical / Low status with StockStatus, as each bank's own
     *  Inventory screen does, and counts the missing groups as zero. */
    @Query("""
        select b.bloodBank.id as bankId, b.bloodType as bloodType, sum(b.unitsRemaining) as usableUnits
          from BloodInventoryBatch b
         where b.expiryDate >= :today
         group by b.bloodBank.id, b.bloodType
        """)
    List<BankGroupUnits> sumUsableUnitsByBankAndBloodType(@Param("today") LocalDate today);

    /** One row of {@link #sumUsableUnitsByBankAndBloodType}. */
    interface BankGroupUnits {
        UUID getBankId();
        BloodType getBloodType();
        long getUsableUnits();
    }

    /** Usable units expiring on or before {@code cutoff}, across every bank. */
    @Query("""
        select coalesce(sum(b.unitsRemaining), 0) from BloodInventoryBatch b
         where b.expiryDate >= :today and b.expiryDate <= :cutoff
        """)
    long sumUsableUnitsExpiringOnOrBefore(@Param("today") LocalDate today, @Param("cutoff") LocalDate cutoff);

    /** Units still on the shelf in expired batches, across every bank: awaiting
     *  discard, and not counted as stock. */
    @Query("select coalesce(sum(b.unitsRemaining), 0) from BloodInventoryBatch b where b.expiryDate < :today")
    long sumExpiredUnits(@Param("today") LocalDate today);

    // ---------- the batch list and history (bank staff and ADMIN only) ----------

    /** One bank's live batches (any units left, expired ones included), grouped by
     *  blood group and then in issue order. */
    @Query("""
        select b from BloodInventoryBatch b
         where b.bloodBank.id = :bloodBankId and b.unitsRemaining > 0
         order by b.bloodType, b.expiryDate, b.id
        """)
    List<BloodInventoryBatch> findLiveForBank(@Param("bloodBankId") UUID bloodBankId);

    /** One bank's live batches of one blood group, in issue order. */
    @Query("""
        select b from BloodInventoryBatch b
         where b.bloodBank.id = :bloodBankId and b.bloodType = :bloodType and b.unitsRemaining > 0
         order by b.expiryDate, b.id
        """)
    List<BloodInventoryBatch> findLiveForBankAndBloodType(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("bloodType") BloodType bloodType
    );

    // ---------- integrity checks ----------

    /**
     * The stock identity check: every batch for which
     * {@code units_received = units_remaining + units allocated to hospitals + units written off}
     * does NOT hold. Should always be empty; anything returned is stock that has left
     * a shelf without a record of where it went, or a record of units that never left.
     *
     * Native SQL so the same query can be pasted into psql as it stands. MIGRATED
     * batches start with received = remaining and no history, so they satisfy it.
     */
    @Query(value = """
        select b.id
          from blood_inventory_batches b
         where b.units_received <> b.units_remaining
               + coalesce((select sum(a.units_allocated) from contribution_allocations a where a.batch_id = b.id), 0)
               + coalesce((select sum(w.units) from batch_write_offs w where w.batch_id = b.id), 0)
        """, nativeQuery = true)
    List<UUID> findIdsBreakingStockIdentity();
}
