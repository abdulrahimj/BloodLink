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
 * The contributions behind Business Rule 20; the only source for how much of a request is covered.
 * Nothing caches that total; the feed reads it for all listed requests in one grouped query.
 */
public interface BloodRequestContributionRepository extends JpaRepository<BloodRequestContribution, UUID> {

    /**
     * Units contributed to one request so far (zero if none), read just after the request row is locked,
     * so concurrent contributions are either included or still waiting.
     */
    @Query("""
        select coalesce(sum(c.unitsContributed), 0) from BloodRequestContribution c
        where c.bloodRequest.id = :requestId
        """)
    int sumUnitsForRequest(@Param("requestId") UUID requestId);

    /** Units received across every request of one hospital, open or finished: the Hospital dashboard's card. */
    @Query("""
        select coalesce(sum(c.unitsContributed), 0) from BloodRequestContribution c
        where c.bloodRequest.hospital.id = :hospitalId
        """)
    long sumUnitsForHospital(@Param("hospitalId") UUID hospitalId);

    /** Whether this bank has contributed to this request — Business Rule 20's
     *  "a bank that has given units cannot then decline the same request". */
    boolean existsByBloodRequestIdAndBloodBankId(UUID bloodRequestId, UUID bloodBankId);

    /**
     * Every contribution to the given requests, oldest first, with the bank fetch-joined
     * for a whole page in one query.
     */
    @Query("""
        select c from BloodRequestContribution c
        join fetch c.bloodBank
        where c.bloodRequest.id in :requestIds
        order by c.contributedAt asc
        """)
    List<BloodRequestContribution> findForRequests(@Param("requestIds") Collection<UUID> requestIds);

    /**
     * Units this bank issued in a date range (the Units Issued figure), by contributed_at,
     * so each bank's own units count in the month they left.
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
