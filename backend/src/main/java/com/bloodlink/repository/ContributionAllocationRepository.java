package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.ContributionAllocation;

/**
 * Which batches each contribution drew from (V18). Append-only: a bare
 * {@link Repository} with a way to add and ways to read, and no way to change or
 * remove an allocation, the same reasoning as AuditLogRepository.
 */
public interface ContributionAllocationRepository extends Repository<ContributionAllocation, UUID> {

    <S extends ContributionAllocation> S save(S allocation);

    Optional<ContributionAllocation> findById(UUID id);

    /**
     * Where one batch's units went: every allocation from it, oldest first, with the
     * contribution, its request and the request's hospital loaded, for the batch
     * history page. Fetch-joined because open-in-view is off: a lazy association
     * would fail once mapped outside the transaction.
     */
    @Query("""
        select a from ContributionAllocation a
          join fetch a.contribution c
          join fetch c.bloodRequest r
          join fetch r.hospital
         where a.batch.id = :batchId
         order by c.contributedAt asc, a.id asc
        """)
    List<ContributionAllocation> findForBatch(@Param("batchId") UUID batchId);

    /**
     * The allocation check: every contribution whose allocations do not add up to
     * its units_contributed. Should always be empty. Contributions with no
     * allocations at all (those made before batch stock existed) are not listed:
     * their units left stock before there were batches to trace them to.
     */
    @Query(value = """
        select a.contribution_id
          from contribution_allocations a
          join blood_request_contributions c on c.id = a.contribution_id
         group by a.contribution_id, c.units_contributed
        having sum(a.units_allocated) <> c.units_contributed
        """, nativeQuery = true)
    List<UUID> findContributionIdsWithMismatchedAllocations();
}
