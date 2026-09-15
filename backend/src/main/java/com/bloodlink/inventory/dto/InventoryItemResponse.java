package com.bloodlink.inventory.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.bloodlink.domain.enums.AppealAvailability;
import com.bloodlink.domain.enums.StockStatus;

/**
 * One blood group's stock at one bank — a row of GET /api/inventory/{bloodBankId}.
 *
 * A bank always reports all eight groups, even ones it has never stocked: those
 * come back with unitsAvailable 0, null expiryDate/lastUpdated, and status
 * CRITICAL (0 is below the 15-unit floor), matching what both mockups assume —
 * a fixed eight-row table, never a partial one.
 */
public record InventoryItemResponse(
    String bloodType,
    int unitsAvailable,
    LocalDate expiryDate,
    Instant lastUpdated,
    StockStatus status,

    /**
     * Whether this bank may appeal for this group right now, and if not, why
     * (Business Rule 12) — the state of the row's Send Appeal button.
     *
     * Sent with the stock it is derived from rather than fetched separately, so
     * the button can never be drawn from one moment's cooldown state over another
     * moment's units. It is also why the button reflects reality rather than the
     * last page load: every reload of this endpoint re-derives it, including the
     * automatic reloads after adding a batch, recording a donation, or sending an
     * appeal.
     *
     * Deliberately not accompanied by a "cooling down until" timestamp. The button
     * says "Appeal Sent.", not a countdown, and a client that was handed an expiry
     * instant would be tempted to enable itself when that instant passed — which
     * would be wrong, because a cooldown can also end early when stock recovers,
     * and can fail to end when it does not. The server owns this decision.
     */
    AppealAvailability appealAvailability
) {}
