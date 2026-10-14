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
 * A blood bank's own donor roster, and each roster donor's history at that
 * bank — Business Rule 21.
 *
 * "This bank's donors" is not a stored relationship (donors register on their
 * own, with no blood-bank link); it means donors with at least one
 * {@code Donation} row at this bank, the same reading
 * {@code DonationRepository.countDistinctDonorsByBloodBankId} already gives
 * the Dashboard's "Registered Donors" stat. Which bank "this" is comes from
 * {@link ActingBankResolver} exactly as every other bank-scoped write already
 * resolves it — bank staff act as their own bank, an ADMIN must name one.
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
     * One page of the roster, filtered and sorted exactly as the Donors page's
     * controls ask (Business Rule 21). Every parameter but {@code principal}
     * has already been validated by {@link DonorRosterController} by the time
     * this runs (page/size bounds, blood-group label, eligibility and sort
     * values) — this method only has to turn them into query parameters.
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

        // Both patterns escape % and _ (LikePattern), so a typed "%" matches only a
        // literal "%" rather than every donor; the phone pattern also maps a local
        // "0…" fragment to the stored "+232…" form (PhoneNumber.searchFragment).
        String trimmedQ = normalizeSearch(q);
        String namePattern = trimmedQ == null ? null : LikePattern.contains(trimmedQ.toLowerCase());
        String phonePattern = trimmedQ == null ? null : LikePattern.contains(PhoneNumber.searchFragment(trimmedQ));

        // null = either; true = Business Rule 2's ELIGIBLE; false = IN_RECOVERY.
        // DonorRosterController's @Pattern already refused anything else.
        Boolean wantEligible = eligibility == null ? null : "ELIGIBLE".equals(eligibility);
        // Borrows the one constant the 90-day rule is built from (Donor.
        // ELIGIBILITY_WINDOW_DAYS) to produce a date to filter by; the
        // response's own eligibility fields never come from this comparison —
        // see DonorRepository.ROSTER_FILTER's note.
        LocalDate eligibilityCutoff = LocalDate.now().minusDays(Donor.ELIGIBILITY_WINDOW_DAYS);

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
     * One donor's donation history at this bank only, newest first, capped at
     * 50 (Business Rule 21).
     *
     * A 404 — the same one, whatever the reason — when there is nothing to
     * show: an id that matches no donor at all, and an id that matches a real
     * donor who simply never gave at this bank, are indistinguishable in the
     * response on purpose. Confirming the second case would tell a bank
     * employee that someone is a registered donor elsewhere, which is exactly
     * the kind of fact this bank has no business relationship to learn.
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
     * donationsHere for a whole page of donors in one query, keyed by donor id
     * — see DonationRepository.countByBloodBankIdAndDonorIdIn for why this is
     * batched rather than queried per row.
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
