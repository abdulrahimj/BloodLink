package com.bloodlink.inventory.dto;

import java.util.List;
import java.util.UUID;

/** Backs the stat cards and charts on the Blood Inventory detail page. */
public record InventorySummaryResponse(
    UUID bloodBankId,
    String bloodBankName,
    int totalUnits,
    /** Units in a row whose expiry_date falls within the next 28 days ("4 weeks",
     *  matching the mockup's own label) — counted whole, since one expiry_date
     *  covers the bank's entire aggregate stock of that group, not per-unit dates. */
    int expiringSoonUnits,
    int criticalGroupsCount,
    /** Units this bank has fulfilled this calendar month — see
     *  BloodRequestRepository.sumUnitsForBankAndStatusInRange for why this is
     *  always 0 until a future phase adds a way to fulfil a request at all. */
    int unitsIssuedThisMonth,
    List<CompositionSlice> composition
) {}
