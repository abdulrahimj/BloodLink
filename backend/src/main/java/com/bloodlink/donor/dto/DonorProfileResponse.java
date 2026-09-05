package com.bloodlink.donor.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.bloodlink.domain.enums.EligibilityStatus;

/**
 * Everything the Donor Portal dashboard needs in one call — GET /api/donors/me.
 *
 * bloodType is the wire label ("A+", "O-") or null when the donor registered
 * without knowing their group ("I don't know yet"); Business Rule 4 means it is
 * only ever established from a tested Donation, never edited directly.
 */
public record DonorProfileResponse(
    UUID id,
    String fullName,
    String email,
    String phone,
    String bloodType,
    String address,

    // Business Rule 2: derived from lastDonationDate, never stored.
    EligibilityStatus eligibilityStatus,
    LocalDate lastDonationDate,
    /** The date eligibility began (or begins): lastDonationDate + 91 days, or the
     *  donor's registration date if they have never donated. Null is never eligible. */
    LocalDate eligibleSince,

    int totalDonations,
    /** Sum of Donation.unitsDonated across every donation on record. */
    int totalUnitsDonated,
    /** unitsDonated * 3 — one unit of whole blood separates into components that can
     *  support up to three patients (red cells, plasma, platelets). */
    int livesImpacted,
    /** The earliest donation on record, for "donor since" framing; null if none yet. */
    LocalDate firstDonationDate
) {}
