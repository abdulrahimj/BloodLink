package com.bloodlink.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.enums.RequestStatus;

public interface BloodRequestRepository extends JpaRepository<BloodRequest, UUID> {

    /**
     * The "Incoming Hospital Requests" panel: most recent requests across every
     * status, not scoped to a blood bank. Business Rule 6 says any bank may fulfil
     * any hospital's request — there is no "this bank's requests" until one is
     * fulfilled — so every bank's dashboard reads the same citywide, newest-first feed.
     */
    List<BloodRequest> findTop5ByOrderByCreatedAtDesc();

    /** The "Pending Requests" stat card — citywide, for the same reason as above. */
    long countByStatus(RequestStatus status);

    /**
     * "Units Issued" on the Blood Inventory page: units this bank has fulfilled in
     * a date range. There is no separate "fulfilled at" timestamp in the schema
     * (Business Rule 5's lifecycle only records createdAt), so createdAt is the
     * best available proxy for "this month". Always 0 until the Requests module
     * adds a way to actually approve/fulfil a request — an honest zero, not a fake one.
     */
    @Query("""
        select coalesce(sum(r.unitsRequested), 0) from BloodRequest r
        where r.fulfilledByBloodBank.id = :bloodBankId
          and r.status = :status
          and r.createdAt >= :start and r.createdAt < :end
        """)
    long sumUnitsForBankAndStatusInRange(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("status") RequestStatus status,
        @Param("start") Instant start,
        @Param("end") Instant end
    );
}
