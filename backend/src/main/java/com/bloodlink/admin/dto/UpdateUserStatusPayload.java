package com.bloodlink.admin.dto;

import com.bloodlink.domain.enums.AccountStatus;

import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/admin/users/{id} — activate or suspend one account.
 *
 * A target status rather than a toggle or a verb: unlike a blood request's
 * lifecycle (RequestActionPayload), there is no illegal edge here to guard
 * against — every account is always in exactly one of two states, and setting it
 * to the state it is already in is a harmless no-op, not a transition to refuse.
 */
public record UpdateUserStatusPayload(

    @NotNull(message = "Status is required")
    AccountStatus status
) {}
