package com.bloodlink.domain.enums;

import java.util.stream.Stream;

/**
 * The eight ABO/Rh blood groups, stored as their clinical label ("A+", "O-") via BloodTypeConverter.
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
     * Matches exactly the eight labels above; the one pattern every DTO's blood-type @Pattern uses,
     * so early field-level 400s can never drift from the enum.
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
