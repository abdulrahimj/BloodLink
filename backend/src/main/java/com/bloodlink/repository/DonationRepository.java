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
     * One donor's history at one bank only, most recent first, capped at 50; both ids must match.
     */
    List<Donation> findTop50ByDonorIdAndBloodBankIdOrderByDonationDateDesc(UUID donorId, UUID bloodBankId);

    /**
     * How many of the given donors (one page) have donated at this bank, in one query:
     * the Donors page's donationsHere, avoiding N+1 queries.
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
     * "Registered Donors" on the Blood Bank Dashboard: donors who have given at least once at this bank,
     * since Donation is the only link between a donor and a bank.
     */
    @Query("select count(distinct d.donor.id) from Donation d where d.bloodBank.id = :bloodBankId")
    long countDistinctDonorsByBloodBankId(@Param("bloodBankId") UUID bloodBankId);

    /**
     * Donation counts per calendar month from start onward, citywide, for the Donation Trends chart (not units).
     * Native SQL for date_trunc; empty months are absent and filled in by AdminReportService.
     */
    @Query(value = """
        select date_trunc('month', donation_date)::date as bucket, count(*) as total
        from donations
        where donation_date >= :start
        group by bucket
        order by bucket
        """, nativeQuery = true)
    List<MonthlyDonationCount> countMonthlyFrom(@Param("start") LocalDate start);

    // ---------- Admin Reports: donations in a date range (Business Rule 23) ----------
    // donation_date is a plain DATE, so months need no time-zone conversion.

    /** Donation count per calendar month between two dates, both inclusive. Months
     *  with none are absent; the service fills them in as zero, as donationTrends does. */
    @Query(value = """
        select date_trunc('month', donation_date)::date as bucket, count(*) as total
        from donations
        where donation_date between :from and :to
        group by bucket
        order by bucket
        """, nativeQuery = true)
    List<MonthlyDonationCount> countMonthlyBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Donation count per blood bank between two dates; every bank appears, at zero
     *  if it recorded none (left join). Ordered by bank name. */
    @Query("""
        select b.name as label, count(d.id) as total
        from BloodBank b left join Donation d on d.bloodBank = b and d.donationDate between :from and :to
        group by b.id, b.name
        order by b.name
        """)
    List<LabelCount> countPerBankBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Donation count per tested blood group (Business Rule 4) between two dates. */
    @Query("""
        select d.bloodType as bloodType, count(d) as total from Donation d
        where d.donationDate between :from and :to
        group by d.bloodType
        """)
    List<BloodTypeCount> countPerBloodTypeBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** A named count — one bank's donations, from {@link #countPerBankBetween}. */
    interface LabelCount {
        String getLabel();
        long getTotal();
    }

    /** One calendar month's donation count, from {@link #countMonthlyFrom}. */
    interface MonthlyDonationCount {
        LocalDate getBucket();
        long getTotal();
    }
}
