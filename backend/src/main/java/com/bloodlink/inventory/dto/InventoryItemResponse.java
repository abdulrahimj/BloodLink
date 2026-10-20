package com.bloodlink.inventory.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.bloodlink.domain.enums.AppealAvailability;
import com.bloodlink.domain.enums.StockStatus;

/**
 * One group's stock at one bank; all eight groups always reported (never-stocked: 0 units, Critical).
 * The first six fields are a contract (InventoryApiContractIT); unitsAvailable is usable units only.
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
    AppealAvailability appealAvailability
) {}
