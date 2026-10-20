package com.bloodlink.donor.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.Donor;

/**
 * One row of a bank's donor roster (Business Rule 21), deliberately narrow: no address, email or date of birth.
 * lastDonationDate is from any bank, and the page labels it so.
 */
public record DonorRosterEntryResponse(
    UUID donorId,
    String fullName,
    /** The donor's own profile group, or null if never typed (Business Rule 4
     *  means this is not necessarily what any one donation recorded). */
    String bloodType,
    String phone,
    /** Most recent donation at any bank; null only if the profile's is (not expected for a roster row). */
    LocalDate lastDonationDate,
    BankDonorEligibility eligibility,
    /** Null when already eligible — the date eligibility began or begins
     *  (Business Rule 2), read from {@code Donor.eligibleFrom()}. */
    LocalDate eligibleFrom,
    /** How many Donation rows this donor has at this specific bank. */
    long donationsHere
) {

    /**
     * Builds one row from a Donor and its separately computed donationsHere count;
     * must run in the loading transaction, as donor.getUser() is lazy.
     */
    public static DonorRosterEntryResponse from(Donor donor, long donationsHere) {
        return new DonorRosterEntryResponse(
            donor.getId(),
            donor.getUser().getFullName(),
            donor.getBloodType() == null ? null : donor.getBloodType().getLabel(),
            donor.getUser().getPhone(),
            donor.getLastDonationDate(),
            // Business Rule 2's one calculation, read off the entity — never
            // re-derived here.
            BankDonorEligibility.from(donor.getEligibilityStatus()),
            donor.eligibleFrom(),
            donationsHere
        );
    }
}
