package com.bloodlink.donor;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.domain.Donation;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.enums.EligibilityStatus;
import com.bloodlink.donor.dto.DonationHistoryItem;
import com.bloodlink.donor.dto.DonorProfileResponse;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.web.error.NotFoundException;

/**
 * Read side of the donor's own profile: dashboard summary and donation history.
 * Everything here is scoped to the caller's own donor row — there is no path in
 * this class that accepts or resolves a different donor's id.
 */
@Service
public class DonorService {

    /** Whole blood separates into components — red cells, plasma, platelets —
     *  each of which can go to a different patient, hence "up to 3 per donation". */
    private static final int LIVES_PER_UNIT = 3;

    /** Business Rule 2: eligible once more than 90 days have passed, i.e. from day 91. */
    private static final int ELIGIBILITY_WINDOW_DAYS = 90;

    private final DonorRepository donorRepository;
    private final DonationRepository donationRepository;

    DonorService(DonorRepository donorRepository, DonationRepository donationRepository) {
        this.donorRepository = donorRepository;
        this.donationRepository = donationRepository;
    }

    /**
     * Assembles the Donor Portal dashboard for the signed-in user.
     *
     * Eligibility is delegated to Donor.getEligibilityStatus() so this stays the one
     * place that rule is evaluated (Business Rule 2); everything else here is
     * aggregated from the donor's Donation history.
     */
    @Transactional(readOnly = true)
    public DonorProfileResponse getMyProfile(UUID userId) {
        Donor donor = findDonorForUser(userId);
        List<Donation> donations = donationRepository.findByDonorIdOrderByDonationDateDesc(donor.getId());

        int totalUnits = donations.stream().mapToInt(Donation::getUnitsDonated).sum();
        // Sorted most-recent-first, so the earliest donation is the last element.
        LocalDate firstDonationDate = donations.isEmpty()
            ? null
            : donations.get(donations.size() - 1).getDonationDate();

        return new DonorProfileResponse(
            donor.getId(),
            donor.getUser().getFullName(),
            donor.getUser().getEmail(),
            donor.getUser().getPhone(),
            donor.getBloodType() == null ? null : donor.getBloodType().getLabel(),
            donor.getAddress(),
            donor.getEligibilityStatus(),
            donor.getLastDonationDate(),
            eligibleSince(donor),
            donations.size(),
            totalUnits,
            totalUnits * LIVES_PER_UNIT,
            firstDonationDate
        );
    }

    /** Full donation history for the signed-in user, most recent first. */
    @Transactional(readOnly = true)
    public List<DonationHistoryItem> getMyDonationHistory(UUID userId) {
        Donor donor = findDonorForUser(userId);
        return donationRepository.findByDonorIdOrderByDonationDateDesc(donor.getId()).stream()
            .map(donation -> new DonationHistoryItem(
                donation.getDonationDate(),
                donation.getBloodType().getLabel(),
                donation.getUnitsDonated(),
                donation.getBloodBank().getName()
            ))
            .toList();
    }

    /**
     * The date eligibility began (already eligible, if in the past) or begins.
     *
     * A donor who has never given blood is eligible from the day they registered —
     * User.createdAt is the closest thing to that date the schema records.
     * Otherwise it is exactly 91 days after the last donation: the first day
     * Donor.getEligibilityStatus() reads as ELIGIBLE.
     */
    private LocalDate eligibleSince(Donor donor) {
        if (donor.getLastDonationDate() == null) {
            return donor.getUser().getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return donor.getLastDonationDate().plusDays(ELIGIBILITY_WINDOW_DAYS + 1);
    }

    /**
     * Resolves the Donor row behind an authenticated user.
     * Throws rather than returning null/Optional so both endpoints get one
     * consistent 404 when the caller has no donor profile — an ADMIN token reaching
     * a donor-only endpoint's URL pattern (permitted by role, but has no such row).
     */
    private Donor findDonorForUser(UUID userId) {
        return donorRepository.findByUserId(userId)
            .orElseThrow(() -> new NotFoundException("No donor profile is associated with this account."));
    }
}
