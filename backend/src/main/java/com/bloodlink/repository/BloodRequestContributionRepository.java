package com.bloodlink.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.BloodRequestContribution;

/**
 * The contributions behind Business Rule 20 — who has given how much towards which
 * request.
 *
 * This table answers every "how much of this request is covered" question in the
 * system. Nothing caches that total on the request itself: a second copy of a
 * number this important is a second copy free to drift, and the one place it is
 * read on a hot path (the request feed) reads it for every listed request in a
 * single grouped query rather than one query per row.
 */
public interface BloodRequestContributionRepository extends JpaRepository<BloodRequestContribution, UUID> {

    /**
     * Units contributed to one request so far, zero if none.
     *
     * Read inside the contribution transaction, immediately after the request row
     * has been locked for update — that ordering is what makes the figure safe to
     * subtract from: a concurrent contribution either committed before the lock was
     * granted (and is therefore included here) or is still waiting for it.
     */
    @Query("""
        select coalesce(sum(c.unitsContributed), 0) from BloodRequestContribution c
        where c.bloodRequest.id = :requestId
        """)
    int sumUnitsForRequest(@Param("requestId") UUID requestId);

    /** Whether this bank has contributed to this request — Business Rule 20's
     *  "a bank that has given units cannot then decline the same request". */
    boolean existsByBloodRequestIdAndBloodBankId(UUID bloodRequestId, UUID bloodBankId);

    /**
     * Every contribution to the given requests, oldest first, with the contributing
     * bank loaded.
     *
     * Fetch-joined and taken for the whole page of requests at once: the feed shows
     * each request's contributors by name, and open-in-view is off, so a lazy
     * association would fail once mapped outside the transaction — and one query
     * per listed request would be a query per row on the busiest screen in the app.
     */
    @Query("""
        select c from BloodRequestContribution c
        join fetch c.bloodBank
        where c.bloodRequest.id in :requestIds
        order by c.contributedAt asc
        """)
    List<BloodRequestContribution> findForRequests(@Param("requestIds") Collection<UUID> requestIds);

    /**
     * Units this bank has issued in a date range — the "Units Issued" figure on the
     * Blood Inventory page.
     *
     * Measured by contributed_at, the moment the units actually left, rather than
     * by the request's creation date, which is what this figure used to be forced
     * to use (there was no fulfilment timestamp anywhere in the schema). A request
     * raised in one month and filled in the next now counts in the month it was
     * filled, and a request two banks filled counts each bank's own units against
     * that bank rather than the whole request against one of them.
     */
    @Query("""
        select coalesce(sum(c.unitsContributed), 0) from BloodRequestContribution c
        where c.bloodBank.id = :bloodBankId
          and c.contributedAt >= :start and c.contributedAt < :end
        """)
    long sumUnitsForBankInRange(
        @Param("bloodBankId") UUID bloodBankId,
        @Param("start") Instant start,
        @Param("end") Instant end
    );
}
