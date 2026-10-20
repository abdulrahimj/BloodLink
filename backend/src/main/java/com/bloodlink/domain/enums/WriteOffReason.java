package com.bloodlink.domain.enums;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Why units of a batch were discarded (V18, {@code batch_write_offs.reason}).
 * Stored by name; V18's CHECK constraint lists the same five values.
 */
public enum WriteOffReason {

    /** Past its expiry date. */
    EXPIRED("Expired"),

    /** Physically unusable: a leaking bag, a broken cold chain. */
    DAMAGED("Damaged"),

    /** A screening or compatibility test made the units unusable. */
    FAILED_TESTING("Failed testing"),

    /** The batch was entered wrongly (units or expiry date). The way to correct a
     *  batch, since its identity columns can never be edited (V18 trigger). */
    DATA_ENTRY_ERROR("Data entry error"),

    /** Anything else. A note is required (V18 CHECK). */
    OTHER("Other");

    /** How the reason reads in words, in the audit-log entry a write-off writes. */
    private final String label;

    WriteOffReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** True for the one reason that must be explained in a note. */
    public boolean requiresNote() {
        return this == OTHER;
    }

    /**
     * The reason with exactly this name, or empty (lower case included), so the write-off endpoint
     * can refuse an unknown reason with a 400 on its own field.
     */
    public static Optional<WriteOffReason> fromName(String name) {
        return Arrays.stream(values()).filter(reason -> reason.name().equals(name)).findFirst();
    }

    /** Every name, comma-separated, for the message refusing an unknown one. */
    public static String allNames() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
