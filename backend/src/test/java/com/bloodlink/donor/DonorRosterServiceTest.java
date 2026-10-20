package com.bloodlink.donor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.donor.dto.BankDonorEligibility;
import com.bloodlink.donor.dto.DonationAtBankItem;
import com.bloodlink.donor.dto.DonorRosterPageResponse;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonationRepository.DonorDonationCount;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.NotFoundException;

/**
 * DonorRosterService in isolation (Business Rule 21): sort routing, search patterns, filters, donationsHere batching
 * and the single 404 for history. ActingBankResolver is mocked (tested in ActingBankResolverTest).
 */
@ExtendWith(MockitoExtension.class)
class DonorRosterServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private DonorRepository donorRepository;
    @Mock private DonationRepository donationRepository;
    @Mock private ActingBankResolver actingBankResolver;

    private DonorRosterService service;
    private BloodBank bank;
    private AuthenticatedUser staffPrincipal;

    @BeforeEach
    void setUp() {
        service = new DonorRosterService(donorRepository, donationRepository, actingBankResolver);

        bank = new BloodBank();
        bank.setId(BANK_ID);
        bank.setName("Makeni Regional Blood Bank");

        staffPrincipal = new AuthenticatedUser(USER_ID, "+23276900001", "hash", Role.BLOOD_BANK_STAFF);
        when(actingBankResolver.resolve(any(), eq(staffPrincipal), any())).thenReturn(bank);
    }

    private Donor donorWithLastDonation(LocalDate lastDonationDate) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName("Aminata Sesay");
        user.setPhone("+23276900099");
        user.setCreatedAt(Instant.now());

        Donor donor = new Donor();
        donor.setId(UUID.randomUUID());
        donor.setUser(user);
        donor.setBloodType(BloodType.O_NEGATIVE);
        donor.setLastDonationDate(lastDonationDate);
        return donor;
    }

    /** Stubs only the sort query a test uses, since strict stubbing fails on unused stubs. */
    private void stubEmptyRosterByLastDonation() {
        when(donorRepository.findRosterAtBankByLastDonation(
            any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(Page.empty());
    }

    private void stubEmptyRosterByName() {
        when(donorRepository.findRosterAtBankByName(
            any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(Page.empty());
    }

    // ---------- which bank, which query ----------

    @Test
    void theResolvedBanksIdIsUsedForTheQueryAndTheRequestedIdIsPassedThroughToTheResolver() {
        stubEmptyRosterByLastDonation();
        UUID requestedByAdmin = UUID.randomUUID();

        service.listRoster(0, 20, null, null, null, "lastDonation", requestedByAdmin, staffPrincipal);

        // Whatever the caller named goes to ActingBankResolver unchanged, and the service acts on the bank it returns.
        verify(actingBankResolver).resolve(eq(requestedByAdmin), eq(staffPrincipal), any());
        verify(donorRepository).findRosterAtBankByLastDonation(
            eq(BANK_ID), any(), any(), any(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void sortByNameCallsTheNameOrderedQueryInstead() {
        stubEmptyRosterByName();

        service.listRoster(0, 20, null, null, null, "name", null, staffPrincipal);

        verify(donorRepository).findRosterAtBankByName(
            eq(BANK_ID), any(), any(), any(), any(), any(), any(), any(Pageable.class));
        verify(donorRepository, never()).findRosterAtBankByLastDonation(
            any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void anythingOtherThanNameDefaultsToTheLastDonationOrderedQuery() {
        stubEmptyRosterByLastDonation();

        service.listRoster(0, 20, null, null, null, "lastDonation", null, staffPrincipal);

        verify(donorRepository).findRosterAtBankByLastDonation(
            any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
    }

    // ---------- search: name and phone, several typed forms ----------

    @Test
    void aPlainSearchTermBecomesALowercasedContainsPatternForTheName() {
        stubEmptyRosterByLastDonation();

        service.listRoster(0, 20, "Aminata", null, null, "lastDonation", null, staffPrincipal);

        ArgumentCaptor<String> namePattern = ArgumentCaptor.forClass(String.class);
        verify(donorRepository).findRosterAtBankByLastDonation(
            any(), eq("Aminata"), namePattern.capture(), any(), any(), any(), any(), any(Pageable.class));
        assertThat(namePattern.getValue()).isEqualTo("%aminata%");
    }

    @Test
    void aPhoneTypedInLocalFormIsRewrittenToTheStoredInternationalPrefix() {
        stubEmptyRosterByLastDonation();

        service.listRoster(0, 20, "076900001", null, null, "lastDonation", null, staffPrincipal);

        ArgumentCaptor<String> phonePattern = ArgumentCaptor.forClass(String.class);
        verify(donorRepository).findRosterAtBankByLastDonation(
            any(), any(), any(), phonePattern.capture(), any(), any(), any(), any(Pageable.class));
        assertThat(phonePattern.getValue()).isEqualTo("%+23276900001%");
    }

    @Test
    void aPhoneTypedInInternationalFormWithSpacesMatchesTheSameStoredValue() {
        stubEmptyRosterByLastDonation();

        service.listRoster(0, 20, "+232 76 900 001", null, null, "lastDonation", null, staffPrincipal);

        ArgumentCaptor<String> phonePattern = ArgumentCaptor.forClass(String.class);
        verify(donorRepository).findRosterAtBankByLastDonation(
            any(), any(), any(), phonePattern.capture(), any(), any(), any(), any(Pageable.class));
        assertThat(phonePattern.getValue()).isEqualTo("%+23276900001%");
    }

    @Test
    void aBarePhoneFragmentWithNoLeadingZeroIsMatchedExactlyAsTyped() {
        stubEmptyRosterByLastDonation();

        // A partial number a staff member half-remembers; normalize would reject it, the search must not.
        service.listRoster(0, 20, "900", null, null, "lastDonation", null, staffPrincipal);

        ArgumentCaptor<String> phonePattern = ArgumentCaptor.forClass(String.class);
        verify(donorRepository).findRosterAtBankByLastDonation(
            any(), any(), any(), phonePattern.capture(), any(), any(), any(), any(Pageable.class));
        assertThat(phonePattern.getValue()).isEqualTo("%900%");
    }

    @Test
    void percentAndUnderscoreInTheSearchAreEscapedSoTheyMatchOnlyThemselves() {
        stubEmptyRosterByLastDonation();

        // Before the shared LikePattern helper, "%" here matched every donor.
        service.listRoster(0, 20, "%_", null, null, "lastDonation", null, staffPrincipal);

        ArgumentCaptor<String> namePattern = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> phonePattern = ArgumentCaptor.forClass(String.class);
        verify(donorRepository).findRosterAtBankByLastDonation(
            any(), any(), namePattern.capture(), phonePattern.capture(), any(), any(), any(), any(Pageable.class));
        assertThat(namePattern.getValue()).isEqualTo("%\\%\\_%");
        assertThat(phonePattern.getValue()).isEqualTo("%\\%\\_%");
    }

    @Test
    void blankSearchIsTreatedAsNoSearchAtAll() {
        stubEmptyRosterByLastDonation();

        service.listRoster(0, 20, "   ", null, null, "lastDonation", null, staffPrincipal);

        verify(donorRepository).findRosterAtBankByLastDonation(
            any(), isNull(), isNull(), isNull(), any(), any(), any(), any(Pageable.class));
    }

    // ---------- eligibility and blood-group filters ----------

    @Test
    void eligibleMapsToTrueAndInRecoveryMapsToFalse() {
        stubEmptyRosterByLastDonation();

        service.listRoster(0, 20, null, null, "ELIGIBLE", "lastDonation", null, staffPrincipal);
        verify(donorRepository).findRosterAtBankByLastDonation(
            any(), any(), any(), any(), any(), eq(true), any(), any(Pageable.class));

        service.listRoster(0, 20, null, null, "IN_RECOVERY", "lastDonation", null, staffPrincipal);
        verify(donorRepository).findRosterAtBankByLastDonation(
            any(), any(), any(), any(), any(), eq(false), any(), any(Pageable.class));
    }

    @Test
    void noEligibilityFilterIsPassedThroughAsNull() {
        stubEmptyRosterByLastDonation();

        service.listRoster(0, 20, null, null, null, "lastDonation", null, staffPrincipal);

        verify(donorRepository).findRosterAtBankByLastDonation(
            any(), any(), any(), any(), any(), isNull(), any(), any(Pageable.class));
    }

    @Test
    void aBloodGroupLabelIsParsedToTheMatchingEnum() {
        stubEmptyRosterByLastDonation();

        service.listRoster(0, 20, null, "O-", null, "lastDonation", null, staffPrincipal);

        verify(donorRepository).findRosterAtBankByLastDonation(
            any(), any(), any(), any(), eq(BloodType.O_NEGATIVE), any(), any(), any(Pageable.class));
    }

    // ---------- donationsHere: batched, not per-row ----------

    @Test
    void donationsHereIsReadFromTheBatchedCountForEachDonorOnThePage() {
        Donor donor = donorWithLastDonation(LocalDate.now().minusDays(10));
        when(donorRepository.findRosterAtBankByLastDonation(
            any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(donor)));
        when(donationRepository.countByBloodBankIdAndDonorIdIn(eq(BANK_ID), anyList()))
            .thenReturn(List.of(countRow(donor.getId(), 3)));

        DonorRosterPageResponse page = service.listRoster(
            0, 20, null, null, null, "lastDonation", null, staffPrincipal);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).donorId()).isEqualTo(donor.getId());
        assertThat(page.content().get(0).donationsHere()).isEqualTo(3);
        // Exactly one batched call for the whole page, never one per donor.
        verify(donationRepository, org.mockito.Mockito.times(1))
            .countByBloodBankIdAndDonorIdIn(any(), anyList());
    }

    @Test
    void aDonorMissingFromTheBatchedCountDefaultsToZeroRatherThanFailing() {
        Donor donor = donorWithLastDonation(LocalDate.now().minusDays(10));
        when(donorRepository.findRosterAtBankByLastDonation(
            any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(donor)));
        when(donationRepository.countByBloodBankIdAndDonorIdIn(any(), anyList())).thenReturn(List.of());

        DonorRosterPageResponse page = service.listRoster(
            0, 20, null, null, null, "lastDonation", null, staffPrincipal);

        assertThat(page.content().get(0).donationsHere()).isZero();
    }

    @Test
    void eligibilityOnEachRowComesFromTheDonorEntityNotFromTheFilter() {
        // Still inside the 90-day window — IN_RECOVERY on the entity itself,
        // regardless of what (if anything) this call filtered by.
        Donor recovering = donorWithLastDonation(LocalDate.now().minusDays(5));
        when(donorRepository.findRosterAtBankByLastDonation(
            any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(recovering)));
        when(donationRepository.countByBloodBankIdAndDonorIdIn(any(), anyList()))
            .thenReturn(List.of(countRow(recovering.getId(), 1)));

        DonorRosterPageResponse page = service.listRoster(
            0, 20, null, null, null, "lastDonation", null, staffPrincipal);

        assertThat(page.content().get(0).eligibility()).isEqualTo(BankDonorEligibility.IN_RECOVERY);
        assertThat(page.content().get(0).eligibleFrom()).isEqualTo(recovering.eligibleFrom());
    }

    private static DonorDonationCount countRow(UUID donorId, long total) {
        return new DonorDonationCount() {
            @Override public UUID getDonorId() { return donorId; }
            @Override public long getTotal() { return total; }
        };
    }

    // ---------- donation history at this bank ----------

    @Test
    void historyMapsEveryDonationRowAtThisBankNewestFirst() {
        BloodBank sameBank = bank;
        Donation d1 = donation(sameBank, LocalDate.now().minusDays(1), 1, BloodType.O_NEGATIVE);
        when(donationRepository.findTop50ByDonorIdAndBloodBankIdOrderByDonationDateDesc(any(), eq(BANK_ID)))
            .thenReturn(List.of(d1));

        List<DonationAtBankItem> history = service.getDonationHistoryAtBank(
            UUID.randomUUID(), null, staffPrincipal);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).bloodType()).isEqualTo("O-");
        assertThat(history.get(0).unitsDonated()).isEqualTo(1);
    }

    @Test
    void noDonationAtThisBankIs404WhetherTheDonorExistsElsewhereOrNotAtAll() {
        when(donationRepository.findTop50ByDonorIdAndBloodBankIdOrderByDonationDateDesc(any(), eq(BANK_ID)))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.getDonationHistoryAtBank(UUID.randomUUID(), null, staffPrincipal))
            .isInstanceOf(NotFoundException.class);
    }

    private static Donation donation(BloodBank bank, LocalDate date, int units, BloodType type) {
        Donation donation = new Donation();
        donation.setBloodBank(bank);
        donation.setDonationDate(date);
        donation.setUnitsDonated(units);
        donation.setBloodType(type);
        return donation;
    }
}
