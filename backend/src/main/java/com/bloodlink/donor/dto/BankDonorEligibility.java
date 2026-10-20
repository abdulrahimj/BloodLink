package com.bloodlink.donor.dto;

import com.bloodlink.domain.enums.EligibilityStatus;

/**
 * Eligibility in the Donors page's wording (Business Rule 21), translated only by from(EligibilityStatus)
 * from Donor.getEligibilityStatus(); a second vocabulary, not a second calculation.
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
