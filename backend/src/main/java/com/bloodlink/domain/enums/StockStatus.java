package com.bloodlink.domain.enums;

/**
 * Derived (never stored) — computed from BloodInventory.unitsAvailable at read
 * time, per blood bank and blood group (CLAUDE.md Business Rule 3).
 * Same pattern as {@link EligibilityStatus}: a fact about the data, not a column.
 */
public enum StockStatus {
    SUFFICIENT,
    LOW,
    CRITICAL;

    private static final int CRITICAL_BELOW = 15;
    private static final int LOW_BELOW = 40;

    /**
     * Below 15 units = Critical, below 40 units = Low, otherwise Sufficient.
     * Confirm with the project owner before changing these numbers (CLAUDE.md).
     */
    public static StockStatus fromUnits(int unitsAvailable) {
        if (unitsAvailable < CRITICAL_BELOW) {
            return CRITICAL;
        }
        if (unitsAvailable < LOW_BELOW) {
            return LOW;
        }
        return SUFFICIENT;
    }
}
