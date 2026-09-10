package com.bloodlink.donor.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.Donor;
import com.bloodlink.domain.enums.EligibilityStatus;

/**
 * One donor, as the blood bank staff member recording their donation sees them.
 *
 * Deliberately narrow. This is the only place in the system where one person's
 * details are shown to somebody who is not that person, so it carries exactly
 * what is needed to confirm "this is the donor standing in front of me and here
 * is what I must know before drawing blood" — and nothing else. No address, no
 * email, no donation history.
 *
 * {@code profileBloodType} is on the record for recognition only, and is
 * explicitly *not* what gets stored against the donation. Business Rule 4 is
 * that the group is re-tested at every donation and the donation records what
 * the test said, so the staff member enters that separately; this field exists
 * so a mismatch between the profile and today's test is visible to a human
 * rather than silently overwritten. It is null for a donor who has never been
 * typed.
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
