package com.bloodlink.request.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * PATCH /api/requests/{id} — a blood bank contributing to a request, or declining
 * it (Business Rule 20).
 */
public record RequestActionPayload(

    @NotNull(message = "Action is required")
    RequestAction action,

    /**
     * Which bank is acting: ignored for BLOOD_BANK_STAFF (their own bank), required only for an ADMIN's REJECT;
     * an ADMIN cannot CONTRIBUTE (Business Rule 8).
     */
    UUID bloodBankId,

    /**
     * Units to contribute: required for CONTRIBUTE, checked in the service. More than still needed is capped,
     * not refused; more than the bank holds is refused.
     */
    @Positive(message = "Enter at least one unit")
    Integer units
) {}
