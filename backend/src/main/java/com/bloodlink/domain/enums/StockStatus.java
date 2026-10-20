package com.bloodlink.domain.enums;

/**
 * Derived, never stored: computed at read time from a bank's usable units of one group
 * (Business Rules 3 and 24), like EligibilityStatus.
 */
public enum StockStatus {
    SUFFICIENT(0),
    LOW(1),
    CRITICAL(2);

    /** Public so an aggregate query (the Admin Reports page, Business Rule 23) can
     *  classify stock in the database with these same two numbers rather than a copy. */
    public static final int CRITICAL_BELOW = 15;
    public static final int LOW_BELOW = 40;

    /**
     * How bad this level is, declared explicitly so reordering the constants cannot invert which is worse.
     */
    private final int severity;

    StockStatus(int severity) {
        this.severity = severity;
    }

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

    /**
     * True when this level is worse than other; the low-stock alert fires on such a crossing,
     * not on every deduction from an already-low group.
     */
    public boolean isWorseThan(StockStatus other) {
        return this.severity > other.severity;
    }
}
