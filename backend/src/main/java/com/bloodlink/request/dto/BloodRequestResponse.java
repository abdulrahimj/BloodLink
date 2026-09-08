package com.bloodlink.request.dto;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.UrgencyLevel;

/**
 * One blood request as every screen sees it — the Hospital Portal's "My Recent
 * Requests" tracking list and the Blood Bank Dashboard's "Incoming Hospital
 * Requests" panel are the same rows read from two directions, so they share one
 * shape rather than each getting a near-identical DTO.
 *
 * Both the id and the name are carried for the hospital and the fulfilling bank:
 * the panels display names, while the actions need ids, and returning both spares
 * the frontend a lookup against a separate reference list on every render.
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
    /** Null while the request is still PENDING or was REJECTED before any bank
     *  claimed it — no bank owns a request until one approves it. */
    UUID fulfilledByBloodBankId,
    String fulfilledByBloodBankName,
    Instant createdAt
) {

    /**
     * Flattens the entity for the wire. Safe to call inside a transaction only —
     * hospital and fulfilledByBloodBank are both lazy associations, and this touches
     * them (spring.jpa.open-in-view is false, so a detached entity would fail here).
     */
    public static BloodRequestResponse from(BloodRequest request) {
        var bank = request.getFulfilledByBloodBank();
        return new BloodRequestResponse(
            request.getId(),
            request.getHospital().getId(),
            request.getHospital().getName(),
            request.getBloodType().getLabel(),
            request.getUnitsRequested(),
            request.getUrgencyLevel(),
            request.getStatus(),
            request.getPatientReference(),
            bank == null ? null : bank.getId(),
            bank == null ? null : bank.getName(),
            request.getCreatedAt()
        );
    }
}
