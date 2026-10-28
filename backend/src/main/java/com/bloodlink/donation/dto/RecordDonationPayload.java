package com.bloodlink.donation.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.enums.BloodType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/donations: a donation being recorded as it happens; no date field, the server uses today
 * so Donation.donationDate and Donor.lastDonationDate always agree.
 */
public record RecordDonationPayload(

    @NotNull(message = "A donor must be selected")
    UUID donorId,

    /**
     * The group as tested today (Business Rule 4), required so it never falls back to the donor's profile group.
     */
    @NotBlank(message = "The tested blood group is required")
    @Pattern(regexp = BloodType.LABEL_PATTERN, message = "Enter a valid blood group, e.g. \"O+\"")
    String bloodType,

    /**
     * Capped at 10 as a typo guard, not a clinical rule: a three-digit figure is a slipped keystroke.
     */
    @NotNull(message = "Units donated is required")
    @Min(value = 1, message = "At least 1 unit must be recorded")
    @Max(value = 10, message = "That is more units than one donation can produce — please check the figure")
    Integer unitsDonated,

    /**
     * Ignored: the bank is always the recording staff member's own (resolveForStockChange; Business Rule 8).
     * Kept so a client that still sends it is not refused.
     */
    UUID bloodBankId,

    /**
     * Optional last usable day from the bag label; default donation date + 42 days.
     * If given, today to 365 days away (StockPolicy), else a 400; checked in DonationService.
     */
    LocalDate expiryDate,

    /**
     * True once staff confirm that a tested group differing from the profile's should replace it (Business Rule 28);
     * without it such a donation is a 409 with nothing written. Null means false.
     */
    Boolean confirmBloodGroupChange
) {

    /** Whether the profile change was confirmed; a missing flag is a no. */
    public boolean bloodGroupChangeConfirmed() {
        return Boolean.TRUE.equals(confirmBloodGroupChange);
    }
}
