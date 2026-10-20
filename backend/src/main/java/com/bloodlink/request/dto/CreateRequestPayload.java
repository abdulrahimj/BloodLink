package com.bloodlink.request.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.UrgencyLevel;

/**
 * POST /api/requests (Submit Blood Request): no hospital id, which comes from the caller's own User row,
 * and no status, since every request starts PENDING.
 */
public record CreateRequestPayload(

    /** Clinical label ("A+", "O-") rather than the enum constant, matching how blood
     *  type crosses the wire everywhere else in this API (see BloodTypeConverter). */
    @NotBlank(message = "Blood group is required")
    @Pattern(regexp = BloodType.LABEL_PATTERN, message = "Enter a valid blood group, e.g. \"O+\"")
    String bloodType,

    /**
     * Capped at 500 as a typo guard, not a clinical rule; a larger need should be raised as several requests.
     */
    @NotNull(message = "Units required is required")
    @Min(value = 1, message = "At least 1 unit must be requested")
    @Max(value = 500, message = "That is more units than a single request should carry — please check the figure")
    Integer unitsRequested,

    @NotNull(message = "Urgency level is required")
    UrgencyLevel urgencyLevel,

    /** Optional ward/patient reference, bounded to V7's column width so an over-long value is a readable 400. */
    @Size(max = 255, message = "Ward / patient reference must be 255 characters or fewer")
    String patientReference
) {}
