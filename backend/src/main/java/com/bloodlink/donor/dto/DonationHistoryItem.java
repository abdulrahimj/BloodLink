package com.bloodlink.donor.dto;

import java.time.LocalDate;

/**
 * One row of GET /api/donors/me/donations, most recent first.
 *
 * bloodType is Donation.bloodType — the group verified by testing at this specific
 * donation (Business Rule 4) — not the donor's profile value, which may differ or
 * be unset entirely.
 */
public record DonationHistoryItem(
    LocalDate donationDate,
    String bloodType,
    int unitsDonated,
    String bloodBankName
) {}
