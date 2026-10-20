package com.bloodlink.request.dto;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.BloodRequestContribution;

/**
 * One bank's contribution to a request as both portals show it (Business Rule 20),
 * so the hospital sees where its blood came from.
 */
public record ContributionResponse(
    UUID bloodBankId,
    String bloodBankName,
    int unitsContributed,
    Instant contributedAt
) {

    /** Must run inside the transaction that loaded it — bloodBank is a lazy
     *  association (the repository fetch-joins it for exactly this). */
    public static ContributionResponse from(BloodRequestContribution contribution) {
        return new ContributionResponse(
            contribution.getBloodBank().getId(),
            contribution.getBloodBank().getName(),
            contribution.getUnitsContributed(),
            contribution.getContributedAt()
        );
    }
}
