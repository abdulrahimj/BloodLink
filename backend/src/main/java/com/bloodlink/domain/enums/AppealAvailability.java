package com.bloodlink.domain.enums;

/**
 * Whether a bank may send an appeal for one group now, and if not why (Business Rule 12); derived, never stored.
 * Carries the reason so the button can say "Appeal Sent." during a cooldown rather than just disabling.
 */
public enum AppealAvailability {

    /** Stock is short, and no cooldown is in force — the button is active. */
    AVAILABLE,

    /** At or above 30 units: nothing to appeal about (checked first). */
    NO_SHORTAGE,

    /** This bank appealed for this group recently and stock has not recovered. */
    COOLING_DOWN
}
