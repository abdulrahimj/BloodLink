package com.bloodlink.donor.dto;

import com.bloodlink.domain.enums.EligibilityStatus;

/**
 * Eligibility as the Donors page shows it (Business Rule 21) — deliberately not
 * the same enum {@link EligibilityStatus} already uses elsewhere.
 *
 * {@code EligibilityStatus} is {@code ELIGIBLE}/{@code INELIGIBLE}, and renaming
 * it to fit this screen's wording would ripple into every other place that
 * already reads it (the donor's own dashboard, the Record Donation lookup).
 * This is a second, narrow vocabulary for one screen, not a second calculation:
 * {@link #from(EligibilityStatus)} is the only place that translates between
 * them, and it is fed by {@code Donor.getEligibilityStatus()} — the single
 * place the 90-day rule (Business Rule 2) is ever evaluated.
 */
public enum BankDonorEligibility {

    /** More than 90 days since their last donation, or they have never donated. */
    ELIGIBLE,

    /** Still inside the 90-day recovery window. */
    IN_RECOVERY;

    public static BankDonorEligibility from(EligibilityStatus status) {
        return status == EligibilityStatus.ELIGIBLE ? ELIGIBLE : IN_RECOVERY;
    }
}
