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
 *
 * <h2>The shape is a contract</h2>
 * The first six fields are the ones the Hospital Dashboard and the Blood Bank screens
 * have always read, with the same names and types; only their meaning was sharpened
 * by batch-level stock (Business Rule 24): {@code unitsAvailable} is USABLE units,
 * excluding expired batches, and {@code expiryDate} is the earliest expiry among
 * those usable batches. The last three were added after them, so an older client
 * that does not know them simply ignores them. InventoryApiContractIT pins this.
 *
 * Hospitals read this endpoint too. It carries totals per group only, never
 * batch-level detail; that is /batches, which hospitals cannot reach.
 */
public record InventoryItemResponse(
    String bloodType,
    /** Units in batches not yet expired: what this bank can issue now. */
    int unitsAvailable,
    /** The earliest expiry date among usable batches with units left; null if none. */
    LocalDate expiryDate,
    /** The most recent change to any of this group's batches; null if never stocked. */
    Instant lastUpdated,
    /** Business Rule 3's badge, judged on {@code unitsAvailable}. */
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
     * says "Appeal Sent.", not a countdown, and a client handed an expiry instant
     * would be tempted to enable itself once that instant passed — which would be
     * wrong, because a cooldown can also be cleared well before it by stock
     * recovering past the threshold that triggered it. The server owns this
     * decision.
     */
    AppealAvailability appealAvailability,
    /** Of {@code unitsAvailable}, the units expiring within the 28-day window. */
    int expiringSoonUnits,
    /** Units still on the shelf in expired batches: awaiting discard, and NOT part
     *  of {@code unitsAvailable}. */
    int expiredUnits,
    /** Batches of this group with any units left, expired ones included. */
    int batchCount
) {}
