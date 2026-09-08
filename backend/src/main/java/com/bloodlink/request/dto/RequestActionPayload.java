package com.bloodlink.request.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/requests/{id} — one step along Business Rule 5's lifecycle.
 *
 * An action verb rather than a target status, because the two are not the same
 * thing: "fulfil" moves the request to FULFILLED *and* deducts stock, and a client
 * that could simply post `status: FULFILLED` would be able to close a request
 * without the deduction ever happening. Naming the transition also makes an illegal
 * one (fulfilling something still PENDING) a rejected action rather than a silently
 * accepted state overwrite.
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
    UUID bloodBankId
) {}
