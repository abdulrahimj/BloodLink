package com.bloodlink.domain.enums;

/**
 * A request's lifecycle (Business Rule 20): PENDING -> PARTIALLY_FULFILLED -> FULFILLED, or PENDING -> REJECTED.
 * Derived from the contributions, never set by hand; APPROVED was removed in V15.
 */
public enum RequestStatus {

    /** Raised, with nothing contributed yet. Visible to every blood bank. */
    PENDING,

    /** Some units received, some outstanding; visible to every bank, never auto-closed by declines (Business Rule 20). */
    PARTIALLY_FULFILLED,

    /** Every requested unit has been contributed. Terminal. */
    FULFILLED,

    /** Every blood bank declined it while it was still untouched (Business Rule 11).
     *  Terminal. */
    REJECTED;

    /** True while units are still outstanding — the two states a bank may act on. */
    public boolean isOpen() {
        return this == PENDING || this == PARTIALLY_FULFILLED;
    }
}
