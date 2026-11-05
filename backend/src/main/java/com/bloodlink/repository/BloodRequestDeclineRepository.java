package com.bloodlink.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BloodRequestDecline;

/**
 * The per-bank decline rows behind Business Rule 11, read by RequestService: has this bank declined,
 * how many banks have, and (via BloodRequestRepository) which requests leave a bank's queue.
 */
public interface BloodRequestDeclineRepository extends JpaRepository<BloodRequestDecline, UUID> {

    /**
     * How many banks have declined this request, compared with the bank count to decide REJECTED;
     * the unique (request, bank) constraint makes the row count the bank count.
     */
    long countByBloodRequestId(UUID bloodRequestId);

    /**
     * Whether this bank already declined this request, so a repeat is a clear 409.
     */
    boolean existsByBloodRequestIdAndBloodBankId(UUID bloodRequestId, UUID bloodBankId);

    /**
     * Which banks declined this request; they no longer watch it, so get no REQUEST_PROGRESS (Business Rule 20).
     */
    @Query("select d.bloodBank.id from BloodRequestDecline d where d.bloodRequest.id = :requestId")
    List<UUID> findBloodBankIdsByRequestId(@Param("requestId") UUID requestId);

    /** Which of these requests this bank declined, for one page of its Request history (Business Rule 36). */
    @Query("""
        select d.bloodRequest.id from BloodRequestDecline d
        where d.bloodBank.id = :bloodBankId and d.bloodRequest.id in :requestIds
        """)
    List<UUID> findRequestIdsDeclinedByBank(
        @Param("bloodBankId") UUID bloodBankId, @Param("requestIds") Collection<UUID> requestIds);
}
