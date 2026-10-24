package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BloodBank;

import jakarta.persistence.LockModeType;

public interface BloodBankRepository extends JpaRepository<BloodBank, UUID> {

    /** Backs GET /api/blood-banks — a stable, human-friendly listing order. */
    List<BloodBank> findAllByOrderByNameAsc();

    /**
     * Pre-flight duplicate check for Business Rule 19, ignoring case;
     * V14's unique index is the real guarantee.
     */
    boolean existsByNameIgnoreCase(String name);

    /** Locks one bank row so two runs of the daily expiry job cannot both send a bank's notices (Business Rule 26). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BloodBank b where b.id = :id")
    Optional<BloodBank> lockById(@Param("id") UUID id);
}
