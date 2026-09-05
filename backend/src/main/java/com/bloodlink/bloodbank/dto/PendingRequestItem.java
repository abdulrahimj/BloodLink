package com.bloodlink.bloodbank.dto;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.UrgencyLevel;

/**
 * One row of the "Incoming Hospital Requests" panel.
 *
 * Read-only for this phase: there is no submit-a-request endpoint yet (that is
 * the Hospital Portal's own phase) and no approve/reject/fulfil action here
 * either (Business Rule 5's lifecycle, which is the Requests module). This is
 * only a live view of whatever BloodRequest rows already exist.
 */
public record PendingRequestItem(
    UUID id,
    String hospitalName,
    String bloodType,
    int unitsRequested,
    UrgencyLevel urgencyLevel,
    RequestStatus status,
    Instant createdAt
) {}
