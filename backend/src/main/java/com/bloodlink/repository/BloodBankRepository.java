package com.bloodlink.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.BloodBank;

public interface BloodBankRepository extends JpaRepository<BloodBank, UUID> {

    /** Backs GET /api/blood-banks — a stable, human-friendly listing order. */
    List<BloodBank> findAllByOrderByNameAsc();

    /**
     * Whether a blood bank with this name already exists, ignoring case — the
     * pre-flight duplicate check for Business Rule 19. V14's unique index on
     * lower(name) is the real guarantee; this only lets the 409 name the problem.
     */
    boolean existsByNameIgnoreCase(String name);
}
