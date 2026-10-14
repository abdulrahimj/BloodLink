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
import com.bloodlink.domain.enums.BloodType;

import jakarta.persistence.LockModeType;

public interface DonorRepository extends JpaRepository<Donor, UUID> {

    /**
     * Shared filter predicate for both roster queries below (Business Rule 21):
     * every donor with at least one donation at {@code :bloodBankId}, narrowed
     * by whichever of the optional filters were actually given.
     *
     * {@code exists (...)} rather than a join + GROUP BY: a donor's
     * {@code donationsHere} count is computed separately, afterward, by
     * {@code DonationRepository.countByBloodBankIdAndDonorIdIn} for just the
     * page returned — mixing a {@code join fetch} (needed below for
     * {@code d.user}) with an aggregating GROUP BY in the same query asks
     * Postgres to group by columns from a table the GROUP BY never names,
     * which it refuses. Splitting the two is also simpler to read.
     *
     * {@code :q}, naming the WHERE clause's own "is a search active?" sentinel,
     * is bound to the trimmed search text or null; {@code :namePattern} and
     * {@code :phonePattern} are its two derived LIKE patterns (DonorRosterService
     * builds both, since "matches name OR phone" needs two different shapes
     * from the one typed value). {@code :wantEligible} is null for "either",
     * true for Business Rule 2's ELIGIBLE, false for IN_RECOVERY; both cases
     * compare {@code d.lastDonationDate} against {@code :eligibilityCutoff},
     * which the service computes from {@code Donor.ELIGIBILITY_WINDOW_DAYS} —
     * borrowing that one constant for a date, not re-deriving the 90-day rule
     * here. The response's own eligibility fields are never read from this
     * filter; they come from {@code Donor.getEligibilityStatus()}/
     * {@code eligibleFrom()} on the loaded entity, same as everywhere else.
     */
    String ROSTER_FILTER = """
        exists (select 1 from Donation don where don.donor = d and don.bloodBank.id = :bloodBankId)
          and (:q is null or lower(u.fullName) like :namePattern or u.phone like :phonePattern)
          and (:bloodType is null or d.bloodType = :bloodType)
          and (:wantEligible is null
               or (:wantEligible = true and (d.lastDonationDate is null or d.lastDonationDate < :eligibilityCutoff))
               or (:wantEligible = false and d.lastDonationDate is not null and d.lastDonationDate >= :eligibilityCutoff))
        """;

    /** Resolves the donor profile behind an authenticated user (User 1–1 Donor). */
    Optional<Donor> findByUserId(UUID userId);

    /**
     * Everyone a donation appeal for one blood group goes to.
     *
     * The user is join-fetched because the appeal is addressed to the User row, not
     * the Donor row, and Donor.user is a lazy association: without the fetch this
     * would issue one extra query per donor found, which for a citywide O- appeal is
     * the whole point of doing it in one statement instead.
     *
     * Donors whose blood_type is still null (registered before being tested) are
     * excluded by the equality match itself — there is no group to appeal to them
     * about yet.
     */
    @Query("select d from Donor d join fetch d.user where d.bloodType = :bloodType")
    List<Donor> findByBloodTypeWithUser(@Param("bloodType") BloodType bloodType);

    /**
     * The donor registered against one phone number — the lookup a blood bank
     * staff member runs before recording a donation.
     *
     * Phone rather than name because V2 made users.phone unique, so this can
     * only ever match one person: a name search would return several and leave
     * staff guessing which is the donor in front of them. The user is
     * join-fetched because every field the lookup returns except blood type
     * lives on that row.
     */
    @Query("select d from Donor d join fetch d.user u where u.phone = :phone")
    Optional<Donor> findByUserPhoneWithUser(@Param("phone") String phone);

    /**
     * One donor, under a row-level write lock — used when recording a donation,
     * where eligibility is read, checked, and then overwritten with today's date.
     *
     * That read-check-write is not atomic on its own: two submissions for the
     * same donor at the same moment would both read an eligible donor and both
     * record a donation. The lock makes the second wait for the first to commit,
     * then re-read a last-donation date of today and be refused — the same
     * reasoning BloodRequestRepository.findByIdForUpdate gives for approvals.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Donor d where d.id = :id")
    Optional<Donor> findByIdForUpdate(@Param("id") UUID id);

    /**
     * One page of a bank's donor roster, newest-donation-first (Business Rule 21).
     *
     * {@code d.lastDonationDate} is the donor's last donation at any bank, not
     * only this one — the same field the response shows, labelled as such. The
     * {@code d.id} tie-breaker keeps two donors who last donated on the same
     * date from swapping between one page request and the next, the same
     * reason {@code AuditLogRepository.findAllNewestFirst} added one.
     *
     * {@code join fetch d.user u} avoids one extra query per row when the
     * service reads {@code donor.getUser()} to build each response row.
     *
     * {@code pageable} is expected unsorted — exactly as
     * {@code AuditLogRepository.findAllNewestFirst} documents — since the
     * newest-first order above, including its tie-breaker, is the only one
     * this method ever returns; only the page number and size are used from it.
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

    /** The same roster, ordered alphabetically by name instead — the other half
     *  of the Donors page's sort choice. Same filter, same tie-breaker rule
     *  (by id, so two donors who share a name never swap pages), same reason
     *  for every other choice documented on {@link #findRosterAtBankByLastDonation}. */
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
