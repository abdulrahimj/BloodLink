package com.bloodlink.request.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/requests/{id}/cancel (Business Rule 35). The reason's value (400 on reason) and the note's rules
 * (400 on note) are checked in the service; there is no hospital id, the caller's own is used.
 */
public record CancelRequestPayload(

    /** NO_LONGER_NEEDED, PATIENT_TRANSFERRED, OBTAINED_ELSEWHERE, ENTERED_BY_MISTAKE or OTHER. */
    @NotBlank(message = "A reason is required")
    String reason,

    /** Optional, except for OTHER; at most 200 characters. Blank is stored as no note. */
    String note
) {}
