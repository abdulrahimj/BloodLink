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
     * Whether this bank may appeal for this group now, and if not why (Business Rule 12): the Send Appeal state.
     * Sent with the stock it derives from and no countdown, since recovery can clear a cooldown early.
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
