package com.bloodlink.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BloodInventory;
import com.bloodlink.domain.enums.BloodType;

import jakarta.persistence.LockModeType;

public interface BloodInventoryRepository extends JpaRepository<BloodInventory, UUID> {

    /** Every tracked blood group's stock row for one bank — never more than 8. */
    List<BloodInventory> findByBloodBankId(UUID bloodBankId);

    /** The one row for (bank, group) — at most one, per the uq_blood_inventory_bank_type
     *  constraint — looked up before an "Add New Batch" upsert. */
    Optional<BloodInventory> findByBloodBankIdAndBloodType(UUID bloodBankId, BloodType bloodType);

    /**
     * The same lookup, taking a row-level write lock — used when fulfilling a blood
     * request, where the units are read, checked against what was requested, and
     * written back down.
     *
     * That read-check-write is not atomic on its own: two requests for the same
     * group being fulfilled at the same instant would both read the pre-deduction
     * count, both decide there was enough, and the second write would overwrite the
     * first — issuing the same units twice and leaving stock overstated. The lock
     * serialises the pair so the second transaction re-reads the already-reduced
     * figure and is refused if it no longer covers the request.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from BloodInventory i where i.bloodBank.id = :bloodBankId and i.bloodType = :bloodType")
    Optional<BloodInventory> findByBloodBankIdAndBloodTypeForUpdate(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("bloodType") BloodType bloodType
    );

    /**
     * Which banks currently hold at least {@code units} of one blood group — the
     * banks a newly raised hospital request could actually be met from, and so the
     * ones whose staff are alerted about it (see NotificationService.notifyNewRequest).
     *
     * Returns ids rather than whole rows because the caller only needs to know which
     * banks to look up staff for; the stock figures themselves are read again, under
     * a lock, if and when a bank approves.
     *
     * A snapshot, not a promise: stock can move between this query and the moment a
     * bank opens the request, which is exactly why approval re-checks availability
     * rather than trusting that it was alerted.
     */
    @Query("""
        select i.bloodBank.id from BloodInventory i
        where i.bloodType = :bloodType and i.unitsAvailable >= :units
        """)
    List<UUID> findBankIdsHoldingAtLeast(
        @Param("bloodType") BloodType bloodType,
        @Param("units") int units
    );

    // ---------- Admin Reports: stock overview (Business Rule 23) ----------
    // Every figure below is summed in the database. Units past their expiry date are
    // included, exactly as InventoryService.getSummary already counts them.

    /** Units per blood group across every bank. A group no bank stocks is simply
     *  absent; the service reports it as zero. */
    @Query("select i.bloodType as bloodType, sum(i.unitsAvailable) as total from BloodInventory i group by i.bloodType")
    List<BloodTypeCount> sumUnitsByBloodType();

    /** Units whose row expires on or before {@code cutoff} — the same test
     *  InventoryService.getSummary applies for its "Expiring Soon" card, so rows
     *  already past expiry are included, as they are there. */
    @Query("select coalesce(sum(i.unitsAvailable), 0) from BloodInventory i where i.expiryDate <= :cutoff")
    long sumUnitsExpiringOnOrBefore(@Param("cutoff") LocalDate cutoff);

    /** Of those, the units whose row is already past its expiry date. */
    @Query("select coalesce(sum(i.unitsAvailable), 0) from BloodInventory i where i.expiryDate < :today")
    long sumUnitsPastExpiry(@Param("today") LocalDate today);

    /**
     * One row per blood bank: total units, how many groups it has a stock row for, and
     * how many of those rows are Critical or Low. The thresholds are passed in from
     * StockStatus (Business Rule 3), not written here. A group with no row at all is
     * zero units, which the inventory pages show as Critical; the service adds those.
     * A left join keeps a bank with no stock rows at all, as all eight groups Critical.
     */
    @Query("""
        select b.id as bankId, b.name as bankName,
               coalesce(sum(i.unitsAvailable), 0) as totalUnits,
               count(i.id) as stockedGroups,
               coalesce(sum(case when i.unitsAvailable < :criticalBelow then 1 else 0 end), 0) as criticalRows,
               coalesce(sum(case when i.unitsAvailable >= :criticalBelow and i.unitsAvailable < :lowBelow
                                 then 1 else 0 end), 0) as lowRows
        from BloodBank b left join BloodInventory i on i.bloodBank = b
        group by b.id, b.name
        order by b.name
        """)
    List<BankStockRow> summariseStockByBank(
        @Param("criticalBelow") int criticalBelow,
        @Param("lowBelow") int lowBelow
    );

    /** One bank's row from {@link #summariseStockByBank}. */
    interface BankStockRow {
        UUID getBankId();
        String getBankName();
        long getTotalUnits();
        long getStockedGroups();
        long getCriticalRows();
        long getLowRows();
    }
}
