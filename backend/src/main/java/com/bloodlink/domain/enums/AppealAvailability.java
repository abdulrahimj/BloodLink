package com.bloodlink.domain.enums;

/**
 * Whether a blood bank may send a donation appeal for one blood group right now,
 * and if not, why not (Business Rule 12).
 *
 * Derived, never stored — computed from current stock and the bank's cooldown row
 * at read time, the same pattern {@link StockStatus} and {@link EligibilityStatus}
 * already follow.
 *
 * The reason is carried rather than a bare boolean because the Send Appeal button
 * has to say something different in each case: a group with no shortage is simply
 * not appealable, while one in cooldown reads "Appeal Sent." — the second tells
 * the operator their own earlier click worked, which a shared "disabled" state
 * would hide.
 */
public enum AppealAvailability {

    /** Stock is short, and no cooldown is in force — the button is active. */
    AVAILABLE,

    /** At or above 30 units: nothing to appeal about (checked first). */
    NO_SHORTAGE,

    /** This bank appealed for this group recently and stock has not recovered. */
    COOLING_DOWN
}
