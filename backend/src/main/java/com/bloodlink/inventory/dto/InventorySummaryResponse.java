package com.bloodlink.inventory.dto;

import java.util.List;
import java.util.UUID;

/**
 * Backs the stat cards and charts on the Blood Inventory detail page. Every unit
 * figure counts usable stock only (Business Rule 24); expired units are reported
 * on their own, in {@code expiredUnits}.
 */
public record InventorySummaryResponse(
    UUID bloodBankId,
    String bloodBankName,
    /** Usable units across all eight groups. */
    int totalUnits,
    /** Usable units expiring within the next {@code expiringSoonWindowDays} days,
     *  counted batch by batch: only the units that actually expire soon. */
    int expiringSoonUnits,
    int criticalGroupsCount,
    /** Units this bank has issued to hospital requests this calendar month, summed
     *  from its own contributions by when they were made (Business Rule 20). */
    int unitsIssuedThisMonth,
    List<CompositionSlice> composition,
    /** Units still on the shelf in expired batches: awaiting discard, and not part of
     *  any figure above. */
    int expiredUnits,
    /** The window "expiring soon" looks ahead: StockPolicy.EXPIRING_SOON_WINDOW_DAYS,
     *  sent so the page can label the card without keeping its own copy of 28. */
    int expiringSoonWindowDays
) {}
