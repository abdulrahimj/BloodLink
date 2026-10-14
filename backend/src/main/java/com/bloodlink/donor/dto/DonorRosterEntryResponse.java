package com.bloodlink.donor.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.Donor;

/**
 * One row of a blood bank's donor roster — GET /api/donors/roster (Business
 * Rule 21).
 *
 * Deliberately narrow, the same principle {@link DonorLookupResponse} already
 * states for the Record Donation lookup: this is a bank employee looking at
 * someone else's details, so the row carries only what the screen needs and
 * nothing a donor would reasonably expect to stay private from staff who have
 * never drawn their blood. No address, no email, no date of birth.
 *
 * {@code lastDonationDate} is {@code Donor.lastDonationDate} — any bank, not
 * only this one, which is why the Donors page labels the column accordingly
 * rather than implying it is specific to this facility.
 */
public record DonorRosterEntryResponse(
    UUID donorId,
    String fullName,
    /** The donor's own profile group, or null if never typed (Business Rule 4
     *  means this is not necessarily what any one donation recorded). */
    String bloodType,
    String phone,
    /** Most recent donation on record, at any bank; null if that is also null
     *  on the donor's own profile (should not happen for a row in this list,
     *  since being in it means a donation exists, but the field is read
     *  straight off the entity rather than assumed). */
    LocalDate lastDonationDate,
    BankDonorEligibility eligibility,
    /** Null when already eligible — the date eligibility began or begins
     *  (Business Rule 2), read from {@code Donor.eligibleFrom()}. */
    LocalDate eligibleFrom,
    /** How many Donation rows this donor has at this specific bank. */
    long donationsHere
) {

    /**
     * Builds one row from a loaded Donor entity and its donationsHere count,
     * computed separately by the caller (DonorRosterService) over the whole
     * page at once. Must run inside the transaction that loaded {@code donor}:
     * {@code donor.getUser()} is a lazy association.
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
