package com.bloodlink.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.BloodBank;

public interface BloodBankRepository extends JpaRepository<BloodBank, UUID> {

    /** Backs GET /api/blood-banks — a stable, human-friendly listing order. */
    List<BloodBank> findAllByOrderByNameAsc();
}
