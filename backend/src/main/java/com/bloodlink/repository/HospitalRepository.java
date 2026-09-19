package com.bloodlink.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.Hospital;

public interface HospitalRepository extends JpaRepository<Hospital, UUID> {

    /** Backs GET /api/hospitals — a stable, human-friendly listing order, matching
     *  how BloodBankRepository orders its own seeded reference rows. */
    List<Hospital> findAllByOrderByNameAsc();

    /** Whether a hospital with this name already exists, ignoring case — the
     *  pre-flight duplicate check for Business Rule 19, as on BloodBankRepository. */
    boolean existsByNameIgnoreCase(String name);
}
