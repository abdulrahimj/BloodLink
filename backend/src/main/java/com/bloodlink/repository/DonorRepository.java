package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.Donor;
import com.bloodlink.domain.enums.BloodType;

import jakarta.persistence.LockModeType;

public interface DonorRepository extends JpaRepository<Donor, UUID> {

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
}
