package com.bloodlink.request.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.UrgencyLevel;

/**
 * One blood request as every screen sees it — the Hospital Portal's "My Recent
 * Requests" tracking list and the Blood Bank Dashboard's "Incoming Hospital
 * Requests" panel are the same rows read from two directions, so they share one
 * shape rather than each getting a near-identical DTO.
 *
 * Since Business Rule 20 a request is no longer fulfilled or not: it is covered up
 * to some number of units by any number of banks. So the progress figures travel
 * with it — {@code unitsContributed}, {@code unitsOutstanding} and the
 * {@code contributions} themselves — and both portals show "2 of 4 units received"
 * rather than a binary badge. Outstanding is sent rather than left for the
 * frontend to subtract: it is the number the Contribute form caps against, and a
 * client that computed it could cap against a different figure than the server
 * enforces.
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

    /** The most recent contributing bank, or null if no bank has contributed yet.
     *  A convenience for the one-name display case; {@code contributions} is the
     *  full answer (Business Rule 20). */
    UUID lastContributedByBloodBankId,
    String lastContributedByBloodBankName,

    Instant createdAt
) {

    /**
     * Flattens the entity and its contributions for the wire. Safe to call inside a
     * transaction only — hospital and lastContributedByBloodBank are both lazy
     * associations, and this touches them (spring.jpa.open-in-view is false, so a
     * detached entity would fail here).
     *
     * The contributions are passed in rather than read from the entity: the feed
     * loads them for a whole page of requests in one query, and a mapped
     * {@code @OneToMany} would turn that back into a query per row.
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
            // Never negative, even if data were somehow over-contributed: a screen
            // asking for "-1 more units" would be a second, louder bug on top of
            // the first.
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
