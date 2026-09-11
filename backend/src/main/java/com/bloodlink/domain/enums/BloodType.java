package com.bloodlink.domain.enums;

import java.util.stream.Stream;

/**
 * The eight ABO/Rh blood groups. The database stores the clinical label
 * ("A+", "O-", ...) exactly as given in CLAUDE.md; {@code BloodTypeConverter}
 * maps between that label and this enum.
 */
public enum BloodType {

    A_POSITIVE("A+"),
    A_NEGATIVE("A-"),
    B_POSITIVE("B+"),
    B_NEGATIVE("B-"),
    O_POSITIVE("O+"),
    O_NEGATIVE("O-"),
    AB_POSITIVE("AB+"),
    AB_NEGATIVE("AB-");

    /**
     * Matches exactly the eight labels above, fully anchored (@Pattern requires a
     * whole-string match). The single source for every request DTO's blood-type
     * field ({@code @Pattern(regexp = BloodType.LABEL_PATTERN, ...)}), so that
     * catching a garbage value early — with a clear, field-level 400 rather than
     * BloodType.fromLabel's generic "Unknown blood type: …" message further down
     * in the service — cannot silently fall out of step with the enum itself if a
     * blood group were ever added or renamed. An annotation attribute must be a
     * compile-time constant, which is exactly what this is.
     */
    public static final String LABEL_PATTERN = "A[+-]|B[+-]|AB[+-]|O[+-]";

    private final String label;

    BloodType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static BloodType fromLabel(String label) {
        return Stream.of(values())
            .filter(type -> type.label.equals(label))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown blood type: " + label));
    }
}
