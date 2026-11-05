package com.bloodlink.request.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.enums.RequestCancellationReason;
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
    /** Units still needed — zero once the request is FULFILLED or CANCELLED. */
    int unitsOutstanding,
    /** Every contribution, oldest first. Empty while nothing has been given. */
    List<ContributionResponse> contributions,

    /** The most recent contributing bank, or null; contributions is the full answer (Business Rule 20). */
    UUID lastContributedByBloodBankId,
    String lastContributedByBloodBankName,

    Instant createdAt,

    /** The short id people see: "3F2A9C1B", shown as "#3F2A9C1B" and used in the notice and audit entry. */
    String reference,

    /** Why it was cancelled and when (Business Rule 35); null unless CANCELLED. */
    RequestCancellationReason cancellationReason,
    Instant cancelledAt,
    /** The hospital's own note and the staff member's name; also null for bank staff, who don't need them. */
    String cancellationNote,
    String cancelledByName,

    /** When it finished (fulfilled, rejected or cancelled), null while open (Business Rule 36). */
    Instant closedAt
) {

    /**
     * Flattens the request and its contributions; call inside a transaction (lazy associations, open-in-view off).
     * Contributions are passed in, loaded for the whole page in one query.
     */
    public static BloodRequestResponse from(BloodRequest request, List<BloodRequestContribution> contributions) {
        return build(request, contributions, true);
    }

    /** The same, as blood bank staff see it: why it was cancelled, but not the note or who did it (data minimisation). */
    public static BloodRequestResponse forBank(BloodRequest request, List<BloodRequestContribution> contributions) {
        return build(request, contributions, false);
    }

    /** A request nothing has been contributed to yet — the shape every newly
     *  submitted request has. */
    public static BloodRequestResponse from(BloodRequest request) {
        return from(request, List.of());
    }

    private static BloodRequestResponse build(
        BloodRequest request, List<BloodRequestContribution> contributions, boolean showCanceller
    ) {
        var bank = request.getLastContributedByBloodBank();
        var cancelledBy = request.getCancelledBy();
        int contributed = contributions.stream().mapToInt(BloodRequestContribution::getUnitsContributed).sum();
        // Only the remaining part of a cancelled request is cancelled, so nothing is outstanding any more.
        int outstanding = request.getStatus() == RequestStatus.CANCELLED
            ? 0
            // Never negative, even if the data were somehow over-contributed.
            : Math.max(0, request.getUnitsRequested() - contributed);

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
            outstanding,
            contributions.stream().map(ContributionResponse::from).toList(),
            bank == null ? null : bank.getId(),
            bank == null ? null : bank.getName(),
            request.getCreatedAt(),
            request.getReference(),
            request.getCancellationReason(),
            request.getCancelledAt(),
            showCanceller ? request.getCancellationNote() : null,
            showCanceller && cancelledBy != null ? cancelledBy.getFullName() : null,
            request.getClosedAt()
        );
    }
}
