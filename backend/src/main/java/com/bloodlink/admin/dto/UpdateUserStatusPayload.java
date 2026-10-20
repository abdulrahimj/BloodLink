package com.bloodlink.admin.dto;

import com.bloodlink.domain.enums.AccountStatus;

import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/admin/users/{id}: a target status rather than a toggle,
 * since setting the current status again is a harmless no-op.
 */
public record UpdateUserStatusPayload(

    @NotNull(message = "Status is required")
    AccountStatus status
) {}
