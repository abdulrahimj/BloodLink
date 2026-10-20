package com.bloodlink.appeal.dto;

import java.util.UUID;

import com.bloodlink.domain.enums.BloodType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/appeals: no recipient list or sender name; recipients come from the blood group and the sender
 * is the caller's own bank, so a hospital can never appear as an appeal's source (Business Rule 1).
 */
public record DonationAppealPayload(

    /** Clinical label ("A+", "O-") rather than the enum constant, matching how blood
     *  type crosses the wire everywhere else in this API (see BloodTypeConverter). */
    @NotBlank(message = "Blood group is required")
    @Pattern(regexp = BloodType.LABEL_PATTERN, message = "Enter a valid blood group, e.g. \"O+\"")
    String bloodType,

    /**
     * Optional free text appended to the appeal, bounded so an over-long note is a readable 400.
     */
    @Size(max = 280, message = "The note must be 280 characters or fewer")
    String note,

    /**
     * Which bank is appealing: ignored for BLOOD_BANK_STAFF (their own bank is used),
     * required only for ADMIN, who may act for any bank.
     */
    UUID bloodBankId
) {}
