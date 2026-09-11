package com.bloodlink.appeal.dto;

import java.util.UUID;

import com.bloodlink.domain.enums.BloodType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/appeals — a blood bank asking donors of one group to come in.
 *
 * Deliberately carries no recipient list and no sender name. Who receives the
 * appeal is derived from the blood group (every donor recorded as having it), and
 * who it comes from is the caller's own blood bank, read from their User row. That
 * is Business Rule 1 made unbreakable rather than merely documented: there is no
 * field on this payload through which a hospital could name itself as the source of
 * an appeal, because appeals flow Blood Bank -> Donor and never Hospital -> Donor.
 */
public record DonationAppealPayload(

    /** Clinical label ("A+", "O-") rather than the enum constant, matching how blood
     *  type crosses the wire everywhere else in this API (see BloodTypeConverter). */
    @NotBlank(message = "Blood group is required")
    @Pattern(regexp = BloodType.LABEL_PATTERN, message = "Enter a valid blood group, e.g. \"O+\"")
    String bloodType,

    /**
     * Optional free text appended to the appeal — "Donation drive at the lab this
     * Saturday, 9am to 4pm", say. Bounded so an over-long value is a readable 400
     * rather than an oversized message in every recipient's bell.
     */
    @Size(max = 280, message = "The note must be 280 characters or fewer")
    String note,

    /**
     * Which bank is appealing. Ignored for BLOOD_BANK_STAFF — their bank comes from
     * their own User row, never from the body — and required only for ADMIN, who
     * belongs to no single bank but may act for any. The same split
     * InventoryService and RequestService already apply.
     */
    UUID bloodBankId
) {}
