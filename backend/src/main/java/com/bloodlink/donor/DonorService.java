package com.bloodlink.donor;

import java.time.LocalDate;
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
 * Read side of the donor's own profile: dashboard summary and donation history,
 * always scoped to the caller's own donor row.
 */
@Service
public class DonorService {

    /** Whole blood separates into components — red cells, plasma, platelets —
     *  each of which can go to a different patient, hence "up to 3 per donation". */
    private static final int LIVES_PER_UNIT = 3;

    private final DonorRepository donorRepository;
    private final DonationRepository donationRepository;

    DonorService(DonorRepository donorRepository, DonationRepository donationRepository) {
        this.donorRepository = donorRepository;
        this.donationRepository = donationRepository;
    }

    /**
     * Assembles the Donor Portal dashboard; eligibility comes from Donor.getEligibilityStatus() (Business Rule 2),
     * the rest from the donation history.
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
            // Business Rule 2's date arithmetic lives on the entity, so this and
            // the blood bank's donor lookup cannot drift apart.
            donor.eligibleFrom(),
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
     * The Donor row behind an authenticated user; throws so a caller with no donor profile
     * (e.g. an ADMIN) gets one consistent 404.
     */
    private Donor findDonorForUser(UUID userId) {
        return donorRepository.findByUserId(userId)
            .orElseThrow(() -> new NotFoundException("No donor profile is associated with this account."));
    }
}
