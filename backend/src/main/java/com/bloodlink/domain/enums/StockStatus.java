package com.bloodlink.domain.enums;

/**
 * Derived (never stored) — computed from BloodInventory.unitsAvailable at read
 * time, per blood bank and blood group (CLAUDE.md Business Rule 3).
 * Same pattern as {@link EligibilityStatus}: a fact about the data, not a column.
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
     * How bad this level is, low to high. Declared explicitly rather than relying on
     * the enum's own ordinal, so reordering the constants for any reason cannot
     * silently invert which of two levels counts as worse.
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
     * True when this level is worse than {@code other} — Sufficient -> Low, Low ->
     * Critical, or Sufficient -> Critical in one step.
     *
     * This is what "crossed into Low/Critical" means for the low-stock alert: the
     * alert fires when a stock level deteriorates past a threshold, not merely when
     * it sits below one. Without the comparison, every deduction from an already-low
     * group would raise a fresh alert and staff would learn to ignore all of them;
     * with it, one alert is raised per crossing and the next comes only if things
     * get worse again.
     */
    public boolean isWorseThan(StockStatus other) {
        return this.severity > other.severity;
    }
}
