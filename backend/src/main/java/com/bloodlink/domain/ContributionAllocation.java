package com.bloodlink.domain;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * How many units one contribution took from one batch (V18): a contribution's allocations add up to its units.
 * Append-only: no setters, @Immutable, no delete method, and V18 triggers refusing UPDATE, DELETE and TRUNCATE.
 */
@Entity
@Table(
    name = "contribution_allocations",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_allocation_contribution_batch",
        columnNames = {"contribution_id", "batch_id"}
    )
)
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA only
public class ContributionAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contribution_id", nullable = false, updatable = false)
    private BloodRequestContribution contribution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, updatable = false)
    private BloodInventoryBatch batch;

    @Column(name = "units_allocated", nullable = false, updatable = false)
    private int unitsAllocated;

    /** The only way to make one. */
    public static ContributionAllocation of(BloodRequestContribution contribution, BloodInventoryBatch batch, int units) {
        if (units <= 0) {
            throw new IllegalArgumentException("An allocation must be at least one unit.");
        }
        ContributionAllocation allocation = new ContributionAllocation();
        allocation.contribution = contribution;
        allocation.batch = batch;
        allocation.unitsAllocated = units;
        return allocation;
    }
}
