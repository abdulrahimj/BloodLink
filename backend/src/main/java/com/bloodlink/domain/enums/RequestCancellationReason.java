package com.bloodlink.domain.enums;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Why a hospital cancelled its blood request (Business Rule 35, V32, {@code blood_requests.cancellation_reason}).
 * Stored by name; V32's CHECK constraint lists the same five values.
 */
public enum RequestCancellationReason {

    NO_LONGER_NEEDED("No longer needed"),
    PATIENT_TRANSFERRED("Patient transferred"),
    OBTAINED_ELSEWHERE("Obtained elsewhere"),
    ENTERED_BY_MISTAKE("Entered by mistake"),

    /** Anything else. A note is required (V32 CHECK). */
    OTHER("Other");

    /** How the reason reads in the notification and the audit entry. */
    private final String label;

    RequestCancellationReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** True for the one reason that must be explained in a note. */
    public boolean requiresNote() {
        return this == OTHER;
    }

    /** The reason with exactly this name, or empty, so an unknown one is a 400 on its own field. */
    public static Optional<RequestCancellationReason> fromName(String name) {
        return Arrays.stream(values()).filter(reason -> reason.name().equals(name)).findFirst();
    }

    /** Every name, comma-separated, for the message refusing an unknown one. */
    public static String allNames() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
