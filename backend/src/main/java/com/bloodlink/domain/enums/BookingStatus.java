package com.bloodlink.domain.enums;

/**
 * A donation booking's state (Business Rule 29). BOOKED is the only open state; the other three are final.
 */
public enum BookingStatus {
    /** Booked and still to come. At most one per donor (V27's partial unique index). */
    BOOKED,
    /** Cancelled by the donor, or automatically when they donated at a different bank. */
    CANCELLED,
    /** A donation was recorded at the booked bank; linked to that donation. */
    ATTENDED,
    /** The date passed with no donation; set by the daily job. */
    MISSED
}
