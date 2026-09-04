package com.bloodlink.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.Donor;

public interface DonorRepository extends JpaRepository<Donor, UUID> {

    /** Resolves the donor profile behind an authenticated user (User 1–1 Donor). */
    Optional<Donor> findByUserId(UUID userId);
}
