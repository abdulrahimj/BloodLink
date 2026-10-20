package com.bloodlink.request.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.UrgencyLevel;

/**
 * One blood request as both portals see it, one shared shape. Carries progress (contributed, outstanding and
 * each contribution, Business Rule 20); outstanding is sent so the client caps against the server's figure.
 */
public record BloodRequestResponse(
    UUID id,
    UUID hospitalId,
    String hospitalName,
    String bloodType,
    int unitsRequested,
    UrgencyLevel urgencyLevel,
    RequestStatus status,
    String patientReference,

    /** Units received so far, across every contributing bank. */
    int unitsContributed,
    /** Units still needed — zero once the request is FULFILLED. */
    int unitsOutstanding,
    /** Every contribution, oldest first. Empty while nothing has been given. */
    List<ContributionResponse> contributions,

    /** The most recent contributing bank, or null; contributions is the full answer (Business Rule 20). */
    UUID lastContributedByBloodBankId,
    String lastContributedByBloodBankName,

    Instant createdAt
) {

    /**
     * Flattens the request and its contributions; call inside a transaction (lazy associations, open-in-view off).
     * Contributions are passed in, loaded for the whole page in one query.
     */
    public static BloodRequestResponse from(BloodRequest request, List<BloodRequestContribution> contributions) {
        var bank = request.getLastContributedByBloodBank();
        int contributed = contributions.stream().mapToInt(BloodRequestContribution::getUnitsContributed).sum();

        return new BloodRequestResponse(
            request.getId(),
            request.getHospital().getId(),
            request.getHospital().getName(),
            request.getBloodType().getLabel(),
            request.getUnitsRequested(),
            request.getUrgencyLevel(),
            request.getStatus(),
            request.getPatientReference(),
            contributed,
            // Never negative, even if the data were somehow over-contributed.
            Math.max(0, request.getUnitsRequested() - contributed),
            contributions.stream().map(ContributionResponse::from).toList(),
            bank == null ? null : bank.getId(),
            bank == null ? null : bank.getName(),
            request.getCreatedAt()
        );
    }

    /** A request nothing has been contributed to yet — the shape every newly
     *  submitted request has. */
    public static BloodRequestResponse from(BloodRequest request) {
        return from(request, List.of());
    }
}
