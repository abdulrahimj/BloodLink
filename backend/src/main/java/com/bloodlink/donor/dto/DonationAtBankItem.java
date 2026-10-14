package com.bloodlink.donor.dto;

import java.time.LocalDate;

/**
 * One row of a donor's donation history at one bank only — GET
 * /api/donors/roster/{donorId}/donations (Business Rule 21).
 *
 * No {@code bloodBankName} field, unlike {@code DonationHistoryItem} (the
 * donor's own, citywide history): every row here is already known to be at
 * this bank, so naming it on each row would only repeat what the page it is
 * shown on already says once.
 *
 * {@code bloodType} is {@code Donation.bloodType} — tested at that specific
 * donation (Business Rule 4) — not the donor's profile group.
 */
public record DonationAtBankItem(
    LocalDate donationDate,
    int unitsDonated,
    String bloodType
) {
}
