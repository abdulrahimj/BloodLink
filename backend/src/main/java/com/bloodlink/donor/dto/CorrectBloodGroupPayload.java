package com.bloodlink.donor.dto;

import com.bloodlink.domain.enums.BloodType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/donors/{donorId}/blood-group: a manual correction after a test, no donation (Business Rule 28).
 * Both fields required; the note says how or where the test was done and is kept in the audit entry.
 */
public record CorrectBloodGroupPayload(

    @NotBlank(message = "Choose the tested blood group")
    @Pattern(regexp = BloodType.LABEL_PATTERN, message = "Enter a valid blood group, e.g. \"O+\"")
    String bloodType,

    @NotBlank(message = "Add a note saying how or where the test was done")
    @Size(max = 500, message = "The note must be 500 characters or fewer")
    String note
) {}
