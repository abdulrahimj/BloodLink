package com.bloodlink.donor.dto;

import java.time.LocalDate;

/**
 * One row of a donor's history at one bank (Business Rule 21); no bank name since every row is this bank.
 * bloodType is the group tested at that donation (Business Rule 4).
 */
public record DonationAtBankItem(
    LocalDate donationDate,
    int unitsDonated,
    String bloodType
) {
}
