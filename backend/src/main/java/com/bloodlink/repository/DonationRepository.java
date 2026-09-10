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
