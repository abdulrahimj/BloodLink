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
}
