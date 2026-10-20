package com.bloodlink.donor.dto;

import java.time.LocalDate;

/**
 * One row of GET /api/donors/me/donations, most recent first; bloodType is the group tested at that donation
 * (Business Rule 4), not the profile value.
 */
public record DonationHistoryItem(
    LocalDate donationDate,
    String bloodType,
    int unitsDonated,
    String bloodBankName
) {}
