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
 * POST /api/requests — the Hospital Portal's "Submit Blood Request" form.
 *
 * Deliberately carries no hospital id. Which hospital a request belongs to is taken
 * from the signed-in staff member's own User row, never from the request body: a
 * client-supplied hospital id would let any hospital raise requests in another
 * hospital's name, and CLAUDE.md is explicit that role-gated behaviour must be
 * enforced server-side rather than trusted from the frontend.
 *
 * Status is not accepted either — every request begins PENDING (Business Rule 5),
 * and so is the fulfilling blood bank, which is unknown until a bank approves
 * (Business Rule 6).
 */
public record CreateRequestPayload(

    /** Clinical label ("A+", "O-") rather than the enum constant, matching how blood
     *  type crosses the wire everywhere else in this API (see BloodTypeConverter). */
    @NotBlank(message = "Blood group is required")
    @Pattern(regexp = BloodType.LABEL_PATTERN, message = "Enter a valid blood group, e.g. \"O+\"")
    String bloodType,

    /**
     * Bounded at 500 as a typo guard, not a clinical rule — matching how
     * RecordDonationPayload.unitsDonated bounds a slipped keystroke rather than
     * expressing a real clinical limit. A single request for more units than a
     * blood bank stocks citywide is almost certainly a data-entry error, and one
     * genuinely needing more should be raised as more than one request.
     */
    @NotNull(message = "Units required is required")
    @Min(value = 1, message = "At least 1 unit must be requested")
    @Max(value = 500, message = "That is more units than a single request should carry — please check the figure")
    Integer unitsRequested,

    @NotNull(message = "Urgency level is required")
    UrgencyLevel urgencyLevel,

    /** Optional: "Maternity Ward — Bed 12", or nothing at all. Bounded to the
     *  column width from V7 so an over-long value is a readable 400 rather than a
     *  database error. */
    @Size(max = 255, message = "Ward / patient reference must be 255 characters or fewer")
    String patientReference
) {}
