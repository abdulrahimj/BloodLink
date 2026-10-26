package com.bloodlink.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.Donor;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BloodType;

import jakarta.persistence.LockModeType;

public interface DonorRepository extends JpaRepository<Donor, UUID> {

    /**
     * Shared filter for both roster queries (Business Rule 21): donors with a donation at :bloodBankId, narrowed by
     * optional search (escaped LIKE patterns), blood type and eligibility against :eligibilityCutoff.
     */
    String ROSTER_FILTER = """
        exists (select 1 from Donation don where don.donor = d and don.bloodBank.id = :bloodBankId)
          and (:q is null or lower(u.fullName) like :namePattern escape '\\'
                          or u.phone like :phonePattern escape '\\')
          and (:bloodType is null or d.bloodType = :bloodType)
          and (:wantEligible is null
               or (:wantEligible = true and (d.lastDonationDate is null or d.lastDonationDate < :eligibilityCutoff))
               or (:wantEligible = false and d.lastDonationDate is not null and d.lastDonationDate >= :eligibilityCutoff))
        """;

    /** Resolves the donor profile behind an authenticated user (User 1–1 Donor). */
    Optional<Donor> findByUserId(UUID userId);

    // ---------- Admin Reports: donors (Business Rule 23) ----------

    /**
     * Donors eligible today (never donated, or last before cutoff from Donor.eligibilityCutoff),
     * counted in the database; ReportsIT checks it matches getEligibilityStatus().
     */
    @Query("select count(d) from Donor d where d.lastDonationDate is null or d.lastDonationDate < :cutoff")
    long countEligibleBefore(@Param("cutoff") LocalDate cutoff);

    /** Donors per profile blood group; a null group is donors not yet typed. */
    @Query("select d.bloodType as bloodType, count(d) as total from Donor d group by d.bloodType")
    List<BloodTypeCount> countByBloodType();

    /**
     * Donors of one blood group whose account is in :status (ACTIVE for appeals), user fetch-joined to avoid a query
     * per donor; untyped donors (null group) are excluded by the match.
     */
    @Query("select d from Donor d join fetch d.user u where d.bloodType = :bloodType and u.status = :status")
    List<Donor> findByBloodTypeWithUser(@Param("bloodType") BloodType bloodType, @Param("status") AccountStatus status);

    /**
     * The donor registered against one phone (unique since V2), for Record Donation's lookup;
     * the user is fetch-joined since most returned fields live there.
     */
    @Query("select d from Donor d join fetch d.user u where u.phone = :phone")
    Optional<Donor> findByUserPhoneWithUser(@Param("phone") String phone);

    /**
     * One donor under a row write lock while recording a donation, so two simultaneous submissions
     * cannot both pass the eligibility check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Donor d where d.id = :id")
    Optional<Donor> findByIdForUpdate(@Param("id") UUID id);

    /**
     * One page of a bank's roster, newest donation (at any bank) first, with d.id as tie-breaker for stable pages.
     * The user is fetch-joined; pageable is expected unsorted, as only this order is returned.
     */
    @Query(
        value = "select d from Donor d join fetch d.user u where " + ROSTER_FILTER
            + " order by d.lastDonationDate desc, d.id desc",
        countQuery = "select count(d) from Donor d join d.user u where " + ROSTER_FILTER
    )
    Page<Donor> findRosterAtBankByLastDonation(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("q") String q,
        @Param("namePattern") String namePattern,
        @Param("phonePattern") String phonePattern,
        @Param("bloodType") BloodType bloodType,
        @Param("wantEligible") Boolean wantEligible,
        @Param("eligibilityCutoff") LocalDate eligibilityCutoff,
        Pageable pageable
    );

    /** The same roster ordered by name, with the same filter and id tie-breaker. */
    @Query(
        value = "select d from Donor d join fetch d.user u where " + ROSTER_FILTER
            + " order by u.fullName asc, d.id asc",
        countQuery = "select count(d) from Donor d join d.user u where " + ROSTER_FILTER
    )
    Page<Donor> findRosterAtBankByName(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("q") String q,
        @Param("namePattern") String namePattern,
        @Param("phonePattern") String phonePattern,
        @Param("bloodType") BloodType bloodType,
        @Param("wantEligible") Boolean wantEligible,
        @Param("eligibilityCutoff") LocalDate eligibilityCutoff,
        Pageable pageable
    );
}
