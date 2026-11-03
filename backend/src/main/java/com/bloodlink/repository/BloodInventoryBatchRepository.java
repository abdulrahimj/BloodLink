package com.bloodlink.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.enums.BloodType;

import jakarta.persistence.LockModeType;

/**
 * Batches of stock (V18; all stock since V19). Every query takes today as a parameter (usable: expiry >= today),
 * never current_date. A bare Repository, since batches are never deleted.
 */
public interface BloodInventoryBatchRepository extends Repository<BloodInventoryBatch, UUID> {

    <S extends BloodInventoryBatch> S save(S batch);

    Optional<BloodInventoryBatch> findById(UUID id);

    // ---------- one bank's stock, per blood group ----------

    /**
     * One bank's stock per blood group it has held: usable, expiring-soon (by cutoff), expired and live-batch counts,
     * earliest usable expiry and last update. Groups with no batches are absent (reported as zero).
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
     * Locks one bank's usable batches of a group in issue order (expiry, then id), after the request lock:
     * one lock order everywhere, so no deadlock. Served by idx_batches_fefo.
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
     * Locks one batch by id whatever its state: the write-off's lock (FOR NO KEY UPDATE, as a contribution's).
     * Expired and empty batches are included; holding no other lock, it cannot deadlock with a contribution.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BloodInventoryBatch b where b.id = :id")
    Optional<BloodInventoryBatch> lockById(@Param("id") UUID id);

    // ---------- across banks ----------

    /**
     * Usable units of one group per bank, for every bank holding at least 1: those alerted about a new request.
     * Expired units do not count; ordered by bank id so the alerts go out in a stable order.
     */
    @Query("""
        select b.bloodBank.id as bankId, sum(b.unitsRemaining) as usableUnits from BloodInventoryBatch b
         where b.bloodType = :bloodType and b.expiryDate >= :today
         group by b.bloodBank.id
        having sum(b.unitsRemaining) > 0
         order by b.bloodBank.id
        """)
    List<BankUnits> findUsableUnitsByBank(
        @Param("bloodType") BloodType bloodType,
        @Param("today") LocalDate today
    );

    /** One row of {@link #findUsableUnitsByBank}. */
    interface BankUnits {
        UUID getBankId();
        long getUsableUnits();
    }

    // ---------- Admin Reports: stock overview (Business Rule 23) ----------

    /** Usable units per blood group across every bank. A group no bank holds usable
     *  stock of is simply absent; the service reports it as zero. */
    @Query("""
        select b.bloodType as bloodType, sum(b.unitsRemaining) as total from BloodInventoryBatch b
         where b.expiryDate >= :today
         group by b.bloodType
        """)
    List<BloodTypeCount> sumUsableUnitsByBloodType(@Param("today") LocalDate today);

    /** Usable units per (bank, group) for every pair that has any; the service judges each with StockStatus
     * and counts missing groups as zero. */
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

    // ---------- daily expiry notices (Business Rule 26) ----------

    /** One bank's usable batches with units expiring on or before {@code latest} and never reminded, in expiry order. */
    @Query("""
        select b from BloodInventoryBatch b
         where b.bloodBank.id = :bloodBankId and b.unitsRemaining > 0 and b.expiryNoticedAt is null
           and b.expiryDate >= :today and b.expiryDate <= :latest
         order by b.expiryDate, b.bloodType, b.id
        """)
    List<BloodInventoryBatch> findUnremindedUsableExpiringBy(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("today") LocalDate today,
        @Param("latest") LocalDate latest
    );

    /** One bank's expired batches still holding units, oldest expiry first. */
    @Query("""
        select b from BloodInventoryBatch b
         where b.bloodBank.id = :bloodBankId and b.unitsRemaining > 0 and b.expiryDate < :today
         order by b.expiryDate, b.bloodType, b.id
        """)
    List<BloodInventoryBatch> findExpiredHoldingUnits(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("today") LocalDate today
    );

    /** Stamps the seven-day reminder on these batches, touching no other column (no lost update, no last_updated). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update BloodInventoryBatch b set b.expiryNoticedAt = :at where b.id in :ids")
    int markExpiryNoticed(@Param("ids") List<UUID> ids, @Param("at") Instant at);

    // ---------- integrity checks ----------

    /**
     * The stock identity check: batches where received != remaining + allocated + written off; should always be empty.
     * Native SQL so it can be pasted into psql; MIGRATED batches satisfy it.
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
