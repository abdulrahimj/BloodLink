package com.bloodlink.domain.enums;

// CLAUDE.md specifies Donor.gender as an enum but does not enumerate its values.
// PREFER_NOT_TO_SAY was added alongside MALE / FEMALE so the enum covers every option
// the Register screen offers. Kept in step with the CHECK constraint in V2.
public enum Gender {
    MALE,
    FEMALE,
    PREFER_NOT_TO_SAY
}
