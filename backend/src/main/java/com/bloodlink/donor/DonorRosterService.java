package com.bloodlink.donor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.common.LikePattern;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.donor.dto.DonationAtBankItem;
import com.bloodlink.donor.dto.DonorRosterEntryResponse;
import com.bloodlink.donor.dto.DonorRosterPageResponse;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonationRepository.DonorDonationCount;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.NotFoundException;

/**
 * A bank's donor roster (Business Rule 21): donors with at least one donation at this bank,
 * the same reading as the Dashboard's Registered Donors stat; the bank comes from ActingBankResolver.
 */
@Service
public class DonorRosterService {

    private final DonorRepository donorRepository;
    private final DonationRepository donationRepository;
    private final ActingBankResolver actingBankResolver;

    DonorRosterService(
        DonorRepository donorRepository,
        DonationRepository donationRepository,
        ActingBankResolver actingBankResolver
    ) {
        this.donorRepository = donorRepository;
        this.donationRepository = donationRepository;
        this.actingBankResolver = actingBankResolver;
    }

    /**
     * One page of the roster, filtered and sorted as the Donors page asks; every parameter but principal
     * was validated by DonorRosterController.
     */
    @Transactional(readOnly = true)
    public DonorRosterPageResponse listRoster(
        int page,
        int size,
        String q,
        String bloodTypeLabel,
        String eligibility,
        String sort,
        UUID requestedBankId,
        AuthenticatedUser principal
    ) {
        BloodBank bank = actingBankResolver.resolve(requestedBankId, principal, "view this bank's donor roster");

        // Propagates IllegalArgumentException for an unrecognised label -> 400,
        // same as every other blood-type-taking endpoint.
        BloodType bloodType = bloodTypeLabel == null ? null : BloodType.fromLabel(bloodTypeLabel);

        // Both patterns escape % and _ (LikePattern); the phone pattern also maps a local "0…" fragment
        // to the stored "+232…" form.
        String trimmedQ = normalizeSearch(q);
        String namePattern = trimmedQ == null ? null : LikePattern.contains(trimmedQ.toLowerCase());
        String phonePattern = trimmedQ == null ? null : LikePattern.contains(PhoneNumber.searchFragment(trimmedQ));

        // null = either; true = Business Rule 2's ELIGIBLE; false = IN_RECOVERY.
        // DonorRosterController's @Pattern already refused anything else.
        Boolean wantEligible = eligibility == null ? null : "ELIGIBLE".equals(eligibility);
        // The 90-day cutoff date to filter by (Donor.eligibilityCutoff); the response's eligibility fields
        // never come from this comparison.
        LocalDate eligibilityCutoff = Donor.eligibilityCutoff(LocalDate.now());

        PageRequest pageable = PageRequest.of(page, size);
        Page<Donor> donorPage = "name".equals(sort)
            ? donorRepository.findRosterAtBankByName(
                bank.getId(), trimmedQ, namePattern, phonePattern, bloodType, wantEligible, eligibilityCutoff, pageable)
            : donorRepository.findRosterAtBankByLastDonation(
                bank.getId(), trimmedQ, namePattern, phonePattern, bloodType, wantEligible, eligibilityCutoff, pageable);

        Map<UUID, Long> donationsHereByDonor = countDonationsHere(bank.getId(), donorPage.getContent());

        List<DonorRosterEntryResponse> content = donorPage.getContent().stream()
            .map(donor -> DonorRosterEntryResponse.from(donor, donationsHereByDonor.getOrDefault(donor.getId(), 0L)))
            .toList();

        return new DonorRosterPageResponse(
            content, donorPage.getNumber(), donorPage.getSize(), donorPage.getTotalElements(), donorPage.getTotalPages());
    }

    /**
     * One donor's history at this bank only, newest first, capped at 50 (Business Rule 21).
     * The same 404 for an unknown donor and one who never gave here, so nothing leaks about other banks.
     */
    @Transactional(readOnly = true)
    public List<DonationAtBankItem> getDonationHistoryAtBank(
        UUID donorId, UUID requestedBankId, AuthenticatedUser principal
    ) {
        BloodBank bank = actingBankResolver.resolve(
            requestedBankId, principal, "view a donor's history at this bank");

        List<DonationAtBankItem> history = donationRepository
            .findTop50ByDonorIdAndBloodBankIdOrderByDonationDateDesc(donorId, bank.getId())
            .stream()
            .map(donation -> new DonationAtBankItem(
                donation.getDonationDate(), donation.getUnitsDonated(), donation.getBloodType().getLabel()))
            .toList();

        if (history.isEmpty()) {
            throw new NotFoundException("No donor found for this id.");
        }
        return history;
    }

    /**
     * donationsHere for a whole page of donors in one query, keyed by donor id.
     */
    private Map<UUID, Long> countDonationsHere(UUID bloodBankId, List<Donor> donors) {
        if (donors.isEmpty()) {
            return Map.of();
        }
        List<UUID> donorIds = donors.stream().map(Donor::getId).toList();
        return donationRepository.countByBloodBankIdAndDonorIdIn(bloodBankId, donorIds).stream()
            .collect(Collectors.toMap(DonorDonationCount::getDonorId, DonorDonationCount::getTotal));
    }

    /** Blank and null both mean "no search typed". */
    private static String normalizeSearch(String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
