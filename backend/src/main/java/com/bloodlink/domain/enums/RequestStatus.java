package com.bloodlink.domain.enums;

/**
 * Where a hospital's blood request stands — Business Rule 20's lifecycle:
 * PENDING -> PARTIALLY_FULFILLED -> FULFILLED (or straight to FULFILLED when one
 * contribution covers the whole amount), or PENDING -> REJECTED.
 *
 * Status is derived from arithmetic rather than declared: after each contribution
 * it is whatever the outstanding units say it is. Nothing sets it by hand.
 *
 * APPROVED, the old "one bank has claimed this and will fill it later", is
 * deliberately gone (V15). A bank now issues units at the moment it acts, so there
 * is no promise to record between claiming and issuing — and a claim was the very
 * thing that stopped a second bank helping with the rest.
 */
public enum RequestStatus {

    /** Raised, with nothing contributed yet. Visible to every blood bank. */
    PENDING,

    /** Some units received, some still outstanding. Still visible to every bank —
     *  and it never closes itself, however many banks decline the remainder
     *  (Business Rule 20). */
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
