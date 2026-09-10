package com.bloodlink.donation.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.enums.StockStatus;

/**
 * The receipt for a recorded donation.
 *
 * Reports the stock position of that blood group *after* the units were folded
 * in, not just the donation itself. Recording a donation is the one action in
 * this system that writes three things at once — a Donation row, the donor's
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
    /** This bank's total units of that group once the donation was added. */
    int stockAfter,
    /** The row's expiry after Business Rule 7's earlier-of-the-two accumulation. */
    LocalDate stockExpiryDate,
    StockStatus stockStatus
) {}
