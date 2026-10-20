package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.ContributionAllocation;

/**
 * Which batches each contribution drew from (V18). Append-only: a bare Repository that can add and read only.
 */
public interface ContributionAllocationRepository extends Repository<ContributionAllocation, UUID> {

    <S extends ContributionAllocation> S save(S allocation);

    Optional<ContributionAllocation> findById(UUID id);

    /**
     * Where one batch's units went: its allocations, oldest first, with contribution, request and hospital fetch-joined
     * for the batch history page (open-in-view is off).
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
     * The allocation check: contributions whose allocations don't add up to units_contributed; should be empty.
     * Contributions from before batch stock (no allocations) are not listed.
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
