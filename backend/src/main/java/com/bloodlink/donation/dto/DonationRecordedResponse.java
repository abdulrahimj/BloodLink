package com.bloodlink.donation.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.enums.StockStatus;

/**
 * The receipt for a recorded donation, including the group's usable stock after the new batch,
 * so the screen can confirm the stock half of the action happened.
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
