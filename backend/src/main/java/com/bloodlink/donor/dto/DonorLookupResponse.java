package com.bloodlink.donor.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.Donor;
import com.bloodlink.domain.enums.EligibilityStatus;

/**
 * One donor as the staff member recording their donation sees them: deliberately narrow (no address, email or history).
 * profileBloodType is for recognition only, never stored on the donation (Business Rule 4).
 */
public record DonorLookupResponse(
    UUID donorId,
    String fullName,
    String phone,
    String profileBloodType,
    EligibilityStatus eligibilityStatus,
    /** Null if they have never donated. */
    LocalDate lastDonationDate,
    /** The date they became, or become, eligible again (Business Rule 2). */
    LocalDate eligibleFrom
) {

    /** Safe to call inside a transaction only — donor.user is a lazy association
     *  and this touches it (open-in-view is off). */
    public static DonorLookupResponse from(Donor donor) {
        return new DonorLookupResponse(
            donor.getId(),
            donor.getUser().getFullName(),
            donor.getUser().getPhone(),
            donor.getBloodType() == null ? null : donor.getBloodType().getLabel(),
            donor.getEligibilityStatus(),
            donor.getLastDonationDate(),
            donor.eligibleFrom()
        );
    }
}
