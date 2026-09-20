package com.bloodlink.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BloodRequestDecline;

/**
 * Reads and writes the per-bank decline rows behind Business Rule 11.
 *
 * Only three questions are ever asked of this table, and all three are asked by
 * RequestService: has this bank already declined this request, how many banks
 * have declined it in total (which decides whether the request is now REJECTED
 * for everyone), and — via the exclusion clause in BloodRequestRepository — which
 * requests should drop out of a given bank's queue.
 */
public interface BloodRequestDeclineRepository extends JpaRepository<BloodRequestDecline, UUID> {

    /**
     * How many blood banks have declined this request.
     *
     * Compared against the total number of blood banks to decide whether a
     * decline was the last one outstanding. The unique constraint on
     * (blood_request_id, blood_bank_id) is what lets this be a plain count
     * rather than a count of distinct bank ids — one bank cannot contribute two
     * rows, so the row count *is* the bank count.
     */
    long countByBloodRequestId(UUID bloodRequestId);

    /**
     * Whether this bank has already declined this request.
     *
     * Checked before writing so a repeat Reject fails with a clear 409 explaining
     * what happened, rather than as an opaque unique-constraint violation.
     */
    boolean existsByBloodRequestIdAndBloodBankId(UUID bloodRequestId, UUID bloodBankId);

    /**
     * Which banks have declined this request.
     *
     * Used when a contribution changes the outstanding figure (Business Rule 20):
     * the banks told about it are the ones still watching the request, and a bank
     * that has declined is no longer watching — the request is not in its queue and
     * it has said it cannot help.
     */
    @Query("select d.bloodBank.id from BloodRequestDecline d where d.bloodRequest.id = :requestId")
    List<UUID> findBloodBankIdsByRequestId(@Param("requestId") UUID requestId);
}
