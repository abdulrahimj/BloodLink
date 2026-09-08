package com.bloodlink.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.enums.RequestStatus;

import jakarta.persistence.LockModeType;

public interface BloodRequestRepository extends JpaRepository<BloodRequest, UUID> {

    /**
     * "My Recent Requests" on the Hospital Portal — this hospital's own requests
     * only, newest first. A hospital may never see another hospital's requests, so
     * the scoping is in the query rather than filtered after the fact.
     */
    List<BloodRequest> findByHospitalIdOrderByCreatedAtDesc(UUID hospitalId);

    /**
     * "Incoming Hospital Requests" for one blood bank: everything this bank could
     * still act on, plus everything it has already acted on, newest first.
     *
     * The two halves of the OR are what Business Rule 6 means in practice.
     * fulfilled_by_blood_bank_id is null exactly while a request is unclaimed
     * (PENDING) — any bank may take it, so every bank sees it. Once a bank approves,
     * that column names the bank that owns the request from then on, so it stays in
     * that bank's feed and drops out of every other bank's. Without the second half
     * a bank would lose sight of a request the moment it approved it; without the
     * first, no bank would ever see a request to approve.
     */
    @Query("""
        select r from BloodRequest r
        where r.fulfilledByBloodBank is null or r.fulfilledByBloodBank.id = :bloodBankId
        order by r.createdAt desc
        """)
    List<BloodRequest> findActionableForBank(@Param("bloodBankId") UUID bloodBankId);

    /** Every request, newest first — the ADMIN's citywide view. */
    List<BloodRequest> findAllByOrderByCreatedAtDesc();

    /** The "Pending Requests" stat card, counted over the same actionable scope as
     *  {@link #findActionableForBank} so the card and the panel below it agree. */
    @Query("""
        select count(r) from BloodRequest r
        where r.status = :status
          and (r.fulfilledByBloodBank is null or r.fulfilledByBloodBank.id = :bloodBankId)
        """)
    long countActionableForBankByStatus(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("status") RequestStatus status
    );

    /**
     * Loads one request with a row-level write lock, for the approve/reject/fulfil
     * transition in RequestService.
     *
     * Two blood banks can open the same PENDING request at the same moment, and the
     * status check and the status write are separate statements — without the lock,
     * both would read PENDING, both would pass the check, and the second write would
     * silently overwrite the first, handing one hospital's units to two banks. The
     * lock makes the second transaction wait and then re-read the already-changed
     * status, so it fails cleanly with a 409 instead.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BloodRequest r where r.id = :id")
    Optional<BloodRequest> findByIdForUpdate(@Param("id") UUID id);

    /**
     * "Units Issued" on the Blood Inventory page: units this bank has fulfilled in
     * a date range. There is no separate "fulfilled at" timestamp in the schema
     * (Business Rule 5's lifecycle only records createdAt), so createdAt is the
     * best available proxy for "this month" — a request raised late in one month and
     * fulfilled early in the next is counted against the month it was raised.
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
