package com.bloodlink.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.Donation;

public interface DonationRepository extends JpaRepository<Donation, UUID> {

    /** A donor's full history, most recent first — backs GET /api/donors/me/donations. */
    List<Donation> findByDonorIdOrderByDonationDateDesc(UUID donorId);

    /**
     * One donor's history at one bank only, most recent first, capped at 50 —
     * backs GET /api/donors/roster/{donorId}/donations. Never another bank's
     * rows: both ids are matched, so a donor who gave elsewhere too shows
     * nothing of that here.
     */
    List<Donation> findTop50ByDonorIdAndBloodBankIdOrderByDonationDateDesc(UUID donorId, UUID bloodBankId);

    /**
     * How many of the given donors have donated at this bank, in one query
     * rather than one per row — the "donationsHere" figure on the Donors page.
     *
     * Deliberately takes the exact donor ids already on the page (at most
     * `size`, capped at 100) rather than every donor at the bank: this runs
     * once per page load, not once per row, which is what keeps
     * DonorRosterService free of N+1 queries without needing a single fused
     * query that mixes a fetch join with GROUP BY (see DonorRepository's
     * roster queries for why that combination was avoided).
     */
    @Query("select don.donor.id as donorId, count(don) as total from Donation don"
        + " where don.bloodBank.id = :bloodBankId and don.donor.id in :donorIds"
        + " group by don.donor.id")
    List<DonorDonationCount> countByBloodBankIdAndDonorIdIn(
        @Param("bloodBankId") UUID bloodBankId, @Param("donorIds") List<UUID> donorIds);

    /** One donor's donation count at one bank, from {@link #countByBloodBankIdAndDonorIdIn}. */
    interface DonorDonationCount {
        UUID getDonorId();
        long getTotal();
    }

    /** "Donations" stat on the Blood Bank Dashboard — this bank, within a date range. */
    long countByBloodBankIdAndDonationDateBetween(UUID bloodBankId, LocalDate start, LocalDate end);

    /**
     * "Registered Donors" stat on the Blood Bank Dashboard.
     *
     * There is no direct link from a Donor to a BloodBank in the schema — Donation
     * is the only bridge — so this counts donors who have given at least once at
     * this bank, which is the closest honest reading of "linked to this bank"
     * available from the fixed schema.
     */
    @Query("select count(distinct d.donor.id) from Donation d where d.bloodBank.id = :bloodBankId")
    long countDistinctDonorsByBloodBankId(@Param("bloodBankId") UUID bloodBankId);

    /**
     * How many donations happened in each calendar month from {@code start}
     * onward, across every blood bank — the aggregate behind the Admin Console's
     * "Donation Trends" chart.
     *
     * A count of donation events, not a sum of units: this follows the same
     * reading of "donations" already established by
     * {@link #countByBloodBankIdAndDonationDateBetween}, which backs the Blood
     * Bank Dashboard's own "Donations" stat as a row count rather than a unit
     * total. Keeping the two readings consistent means a citywide trend and a
     * single bank's monthly figure agree on what the word means.
     *
     * Native SQL rather than JPQL, because JPQL has no portable way to floor a
     * date to the first of its month; CLAUDE.md commits this project to
     * PostgreSQL specifically, so date_trunc costs no portability this project
     * needs. Months with zero donations are simply absent from the result —
     * AdminReportService fills them in, the same way InventoryService fills in a
     * blood group no bank has ever stocked.
     */
    @Query(value = """
        select date_trunc('month', donation_date)::date as bucket, count(*) as total
        from donations
        where donation_date >= :start
        group by bucket
        order by bucket
        """, nativeQuery = true)
    List<MonthlyDonationCount> countMonthlyFrom(@Param("start") LocalDate start);

    /** One calendar month's donation count, from {@link #countMonthlyFrom}. */
    interface MonthlyDonationCount {
        LocalDate getBucket();
        long getTotal();
    }
}
