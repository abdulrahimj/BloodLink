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
