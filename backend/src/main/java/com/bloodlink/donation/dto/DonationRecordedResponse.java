package com.bloodlink.donation.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.enums.StockStatus;

/**
 * The receipt for a recorded donation.
 *
 * Reports the usable stock position of that blood group *after* the donation's
 * batch was added, not just the donation itself. Recording a donation is the one
 * action in this system that writes three things at once — a Donation row, the donor's
 * last-donation date, and the bank's inventory — and the third is the one the
 * staff member cannot see from the form they just filled in. Handing back the
 * resulting figure lets the screen say "O+ is now 46 units" instead of leaving
 * them to go and check that the stock half of the action actually happened.
 */
public record DonationRecordedResponse(
    UUID donationId,
    UUID donorId,
    String donorName,
    /** The group as tested today (Business Rule 4), which is what was stored. */
    String bloodType,
    int unitsDonated,
    LocalDate donationDate,
    UUID bloodBankId,
    String bloodBankName,
    /** This bank's usable units of that group once the donation was added. */
    int stockAfter,
    /** The earliest expiry among this group's usable batches, the new one included. */
    LocalDate stockExpiryDate,
    StockStatus stockStatus,
    /** The expiry date given to this donation's own batch: as entered, or the default. */
    LocalDate batchExpiryDate
) {}
