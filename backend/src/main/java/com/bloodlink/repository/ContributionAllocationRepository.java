package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.bloodlink.domain.ContributionAllocation;

/**
 * Which batches each contribution drew from (V18). Append-only: a bare
 * {@link Repository} with a way to add and ways to read, and no way to change or
 * remove an allocation — the same reasoning as AuditLogRepository. Nothing in the
 * application uses it yet.
 */
public interface ContributionAllocationRepository extends Repository<ContributionAllocation, UUID> {

    <S extends ContributionAllocation> S save(S allocation);

    Optional<ContributionAllocation> findById(UUID id);

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
