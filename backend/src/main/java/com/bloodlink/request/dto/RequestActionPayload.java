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
     * Which bank is acting. Ignored for BLOOD_BANK_STAFF — their bank comes from
     * their own User row, never from the body — and required only for ADMIN, who
     * belongs to no single bank but may act on any (the same split InventoryService
     * already applies to inventory writes).
     */
    UUID bloodBankId,

    /**
     * Units to contribute. Required for CONTRIBUTE and meaningless for REJECT, so
     * it is nullable here and checked in the service, which is the only place that
     * knows which action arrived.
     *
     * More than the request still needs is not an error: the service records what
     * is genuinely outstanding and reports that back, because between the operator
     * reading the screen and pressing the button another bank may have covered part
     * of it. Refusing in that case would punish a race the operator cannot see;
     * capping issues exactly the units that are still wanted. More than the bank
     * holds *is* refused — that is a mistake about its own shelf, not about someone
     * else's timing.
     */
    @Positive(message = "Enter at least one unit")
    Integer units
) {}
