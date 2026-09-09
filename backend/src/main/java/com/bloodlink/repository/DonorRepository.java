package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.Donor;
import com.bloodlink.domain.enums.BloodType;

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
}
