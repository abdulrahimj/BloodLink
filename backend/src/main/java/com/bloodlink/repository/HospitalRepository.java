package com.bloodlink.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.Hospital;

public interface HospitalRepository extends JpaRepository<Hospital, UUID> {

    /** Backs GET /api/hospitals — a stable, human-friendly listing order, matching
     *  how BloodBankRepository orders its own seeded reference rows. */
    List<Hospital> findAllByOrderByNameAsc();
}
