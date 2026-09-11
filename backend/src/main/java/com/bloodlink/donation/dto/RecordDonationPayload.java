package com.bloodlink.donation.dto;

import java.util.UUID;

import com.bloodlink.domain.enums.BloodType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/donations — a blood bank recording a donation it has just taken.
 *
 * Carries no donation date. The date is always today, set by the server: this
 * records a donation as it happens, and letting a client name the date would
 * open a gap between Donation.donationDate and the Donor.lastDonationDate the
 * same action writes — two fields that must agree, since Business Rule 2's
 * whole eligibility calculation hangs off the second one.
 */
public record RecordDonationPayload(

    @NotNull(message = "A donor must be selected")
    UUID donorId,

    /**
     * The group as tested today, and the reason this field exists at all.
     *
     * Business Rule 4: blood type is re-verified by testing at every donation,
     * and the donation records what that test said. It is required rather than
     * optional precisely so it cannot quietly fall back to the donor's stored
     * profile group — a donor typed wrongly once would otherwise have that error
     * copied onto every donation they ever make, and into the stock issued from
     * it. The staff member must state today's result.
     */
    @NotBlank(message = "The tested blood group is required")
    @Pattern(regexp = BloodType.LABEL_PATTERN, message = "Enter a valid blood group, e.g. \"O+\"")
    String bloodType,

    /**
     * Bounded at 10 as a typo guard, not a clinical rule: one whole-blood
     * donation is a single unit, and an apheresis session a small number, so a
     * three-digit figure here is a slipped keystroke that would otherwise be
     * added straight into issuable stock.
     */
    @NotNull(message = "Units donated is required")
    @Min(value = 1, message = "At least 1 unit must be recorded")
    @Max(value = 10, message = "That is more units than one donation can produce — please check the figure")
    Integer unitsDonated,

    /**
     * Which bank took the donation. Ignored for BLOOD_BANK_STAFF — their bank
     * comes from their own User row, never from the body — and required only for
     * ADMIN, who belongs to no single bank. The same split ActingBankResolver
     * already applies to inventory writes and donation appeals.
     */
    UUID bloodBankId
) {}
