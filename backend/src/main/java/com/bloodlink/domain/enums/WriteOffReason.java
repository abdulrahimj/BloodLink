package com.bloodlink.domain.enums;

/**
 * Why units of a batch were discarded (V18, {@code batch_write_offs.reason}).
 * Stored by name; V18's CHECK constraint lists the same five values.
 */
public enum WriteOffReason {

    /** Past its expiry date. */
    EXPIRED,

    /** Physically unusable: a leaking bag, a broken cold chain. */
    DAMAGED,

    /** A screening or compatibility test made the units unusable. */
    FAILED_TESTING,

    /** The batch was entered wrongly (units or expiry date). The way to correct a
     *  batch, since its identity columns can never be edited (V18 trigger). */
    DATA_ENTRY_ERROR,

    /** Anything else. A note is required (V18 CHECK). */
    OTHER;

    /** True for the one reason that must be explained in a note. */
    public boolean requiresNote() {
        return this == OTHER;
    }
}
