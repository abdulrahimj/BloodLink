package com.bloodlink.domain.enums;

/**
 * Why an open donation booking was cancelled (Business Rule 29), which decides the staff notification's wording.
 */
public enum BookingCancellation {
    /** The donor cancelled it themselves. */
    BY_DONOR,
    /** A donation was recorded for the donor at a different bank. */
    DONATED_ELSEWHERE,
    /** An administrator suspended the donor's account. */
    ACCOUNT_SUSPENDED
}
