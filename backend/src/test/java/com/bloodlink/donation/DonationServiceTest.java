package com.bloodlink.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.AppealAvailability;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.donation.dto.DonationRecordedResponse;
import com.bloodlink.donation.dto.RecordDonationPayload;
import com.bloodlink.inventory.InventoryService;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.stock.StockPolicy;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Recording a donation's three effects; the tested group is stored, not the profile's (Business Rule 4),
 * and a donor in recovery is refused with nothing written, boundary included (Business Rule 2).
 */
@ExtendWith(MockitoExtension.class)
class DonationServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID DONOR_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    @Mock private DonationRepository donationRepository;
    @Mock private DonorRepository donorRepository;
    @Mock private ActingBankResolver actingBankResolver;
    @Mock private InventoryService inventoryService;

    private DonationService service;

    @BeforeEach
    void setUp() {
        service = new DonationService(donationRepository, donorRepository, actingBankResolver, inventoryService);
    }

    // ---------- fixtures ----------

    private static BloodBank bank() {
        BloodBank bank = new BloodBank();
        bank.setId(BANK_ID);
        bank.setName("Makeni Regional Blood Bank");
        return bank;
    }

    private static AuthenticatedUser principal() {
        return new AuthenticatedUser(USER_ID, "+23276000002", "hash", Role.BLOOD_BANK_STAFF);
    }

    /** A donor whose stored profile group is A+ — deliberately different from the
     *  tested group used in the tests below, so the two cannot be confused. */
    private static Donor donorWithProfileGroup(LocalDate lastDonationDate) {
        User user = new User();
        user.setId(USER_ID);
        user.setFullName("Rahim Barrie");
        user.setCreatedAt(Instant.now());

        Donor donor = new Donor();
        donor.setId(DONOR_ID);
        donor.setUser(user);
        donor.setBloodType(BloodType.A_POSITIVE);
        donor.setLastDonationDate(lastDonationDate);
        return donor;
    }

    private static RecordDonationPayload payload(String testedGroup, int units) {
        return new RecordDonationPayload(DONOR_ID, testedGroup, units, null, null);
    }

    /** The lookup every path makes — the bank, then the donor under its lock. */
    private void stubBankAndDonor(Donor donor) {
        when(actingBankResolver.resolveForStockChange(any(), any())).thenReturn(bank());
        when(donorRepository.findByIdForUpdate(DONOR_ID)).thenReturn(Optional.of(donor));
    }

    /** Only the paths that get past the eligibility check reach the writes, so
     *  these stubs are set up separately rather than for every test. */
    private void stubWritesAccepting(int resultingUnits) {
        when(donationRepository.save(any(Donation.class))).thenAnswer(inv -> {
            Donation d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        when(inventoryService.receiveDonation(any(), any()))
            .thenAnswer(inv -> new InventoryItemResponse(
                ((Donation) inv.getArgument(0)).getBloodType().getLabel(), resultingUnits, LocalDate.now().plusDays(30),
                Instant.now(), StockStatus.SUFFICIENT, AppealAvailability.NO_SHORTAGE, 0, 0, 1, false
            ));
    }

    private void verifyNothingWasWritten() {
        verify(donationRepository, never()).save(any());
        verify(donorRepository, never()).save(any());
        verify(inventoryService, never()).receiveDonation(any(), any());
    }

    // ---------- the three effects ----------

    @Test
    void recordingWritesTheDonationResetsTheDonorsClockAndAddsTheUnitsToStock() {
        Donor donor = donorWithProfileGroup(LocalDate.now().minusYears(1));
        stubBankAndDonor(donor);
        stubWritesAccepting(46);

        DonationRecordedResponse response = service.recordDonation(payload("O+", 1), principal());

        // 1. the donation row
        ArgumentCaptor<Donation> donationCaptor = ArgumentCaptor.forClass(Donation.class);
        verify(donationRepository).save(donationCaptor.capture());
        Donation written = donationCaptor.getValue();
        assertThat(written.getUnitsDonated()).isEqualTo(1);
        assertThat(written.getDonationDate()).isEqualTo(LocalDate.now());
        assertThat(written.getBloodBank().getId()).isEqualTo(BANK_ID);

        // 2. the donor's eligibility clock
        verify(donorRepository).save(donor);
        assertThat(donor.getLastDonationDate()).isEqualTo(LocalDate.now());
        assertThat(donor.isEligibleToDonate()).isFalse();

        // 3. the units, reported back so the screen can confirm the stock half
        assertThat(response.stockAfter()).isEqualTo(46);
        assertThat(response.donorName()).isEqualTo("Rahim Barrie");
    }

    @Test
    void theStoredGroupIsTheOneTestedTodayNotTheDonorsProfileGroup() {
        // Business Rule 4: the donor's profile says A+, today's test says O-.
        // The donation — and the stock it creates — must follow the test.
        Donor donor = donorWithProfileGroup(null);
        stubBankAndDonor(donor);
        stubWritesAccepting(12);

        service.recordDonation(payload("O-", 1), principal());

        ArgumentCaptor<Donation> donationCaptor = ArgumentCaptor.forClass(Donation.class);
        verify(donationRepository).save(donationCaptor.capture());
        assertThat(donationCaptor.getValue().getBloodType()).isEqualTo(BloodType.O_NEGATIVE);

        // The units must land against O- stock too, not A+: the batch is made from
        // the donation row itself, which carries the tested group and this bank.
        ArgumentCaptor<Donation> batchSource = ArgumentCaptor.forClass(Donation.class);
        verify(inventoryService).receiveDonation(batchSource.capture(), any());
        assertThat(batchSource.getValue()).isSameAs(donationCaptor.getValue());
        assertThat(batchSource.getValue().getBloodType()).isEqualTo(BloodType.O_NEGATIVE);
        assertThat(batchSource.getValue().getBloodBank().getId()).isEqualTo(BANK_ID);

        // And the profile value is left exactly as it was — recording a donation
        // is not a back door for editing the donor's record.
        assertThat(donor.getBloodType()).isEqualTo(BloodType.A_POSITIVE);
    }

    @Test
    void unitsEnterStockWithTheDefaultShelfLife() {
        Donor donor = donorWithProfileGroup(null);
        stubBankAndDonor(donor);
        stubWritesAccepting(3);

        DonationRecordedResponse response = service.recordDonation(payload("AB-", 2), principal());

        // The batch is the donation's own: its units, and donation date + the 42-day
        // StockPolicy constant, since no expiry date was entered.
        LocalDate expected = LocalDate.now().plusDays(StockPolicy.DONATION_SHELF_LIFE_DAYS);
        ArgumentCaptor<Donation> donationCaptor = ArgumentCaptor.forClass(Donation.class);
        verify(inventoryService).receiveDonation(donationCaptor.capture(), eq(expected));
        assertThat(donationCaptor.getValue().getUnitsDonated()).isEqualTo(2);
        assertThat(response.batchExpiryDate()).isEqualTo(expected);
        // Checked against the same today .. today + 365 rule as Add New Batch.
        verify(inventoryService).requireAcceptableExpiry(expected);
    }

    @Test
    void anEnteredExpiryDateIsUsedInsteadOfTheDefault() {
        Donor donor = donorWithProfileGroup(null);
        stubBankAndDonor(donor);
        stubWritesAccepting(3);
        LocalDate onTheBagLabel = LocalDate.now().plusDays(35);

        DonationRecordedResponse response = service.recordDonation(
            new RecordDonationPayload(DONOR_ID, "AB-", 1, null, onTheBagLabel), principal());

        verify(inventoryService).receiveDonation(any(), eq(onTheBagLabel));
        assertThat(response.batchExpiryDate()).isEqualTo(onTheBagLabel);
    }

    /**
     * An expiry outside today to today + 365 is refused before anything is written or locked.
     */
    @Test
    void aRefusedExpiryDateWritesNothingAtAll() {
        when(actingBankResolver.resolveForStockChange(any(), any())).thenReturn(bank());
        LocalDate typo = LocalDate.now().plusYears(2);
        doThrow(new InvalidFieldException("expiryDate", "Expiry date must be within 365 days of today"))
            .when(inventoryService).requireAcceptableExpiry(typo);

        assertThatThrownBy(() -> service.recordDonation(
                new RecordDonationPayload(DONOR_ID, "O+", 1, null, typo), principal()))
            .isInstanceOf(InvalidFieldException.class);

        verifyNothingWasWritten();
        verify(donorRepository, never()).findByIdForUpdate(any());
    }

    // ---------- Business Rule 2: a hard block ----------

    @Test
    void aDonorInsideTheirRecoveryWindowIsRefusedAndNothingIsWritten() {
        LocalDate lastDonation = LocalDate.now().minusDays(10);
        Donor recentDonor = donorWithProfileGroup(lastDonation);
        stubBankAndDonor(recentDonor);

        assertThatThrownBy(() -> service.recordDonation(payload("O+", 1), principal()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Rahim Barrie cannot donate yet")
            .hasMessageContaining("90 days")
            // The date a donation becomes possible is quoted from eligibleFrom(),
            // the same calculation the donor's own dashboard shows them.
            .hasMessageContaining(LONG_DATE.format(recentDonor.eligibleFrom()));

        verifyNothingWasWritten();
        // Their eligibility clock is untouched — a refusal must not reset it.
        assertThat(recentDonor.getLastDonationDate()).isEqualTo(lastDonation);
    }

    @Test
    void theLastDayOfTheWindowIsStillRefused() {
        // "More than 90 days" is strict: day 90 is inside the window. An off-by-one
        // here would let a donor be drawn a day before they are ready.
        stubBankAndDonor(donorWithProfileGroup(LocalDate.now().minusDays(90)));

        assertThatThrownBy(() -> service.recordDonation(payload("O+", 1), principal()))
            .isInstanceOf(ConflictException.class);

        verifyNothingWasWritten();
    }

    @Test
    void theFirstEligibleDayIsAccepted() {
        // Day 91 — the date eligibleFrom() names — is the first day it goes through.
        stubBankAndDonor(donorWithProfileGroup(LocalDate.now().minusDays(91)));
        stubWritesAccepting(10);

        assertThat(service.recordDonation(payload("O+", 1), principal()).donationId()).isNotNull();
    }

    @Test
    void aDonorWhoHasNeverDonatedIsAccepted() {
        stubBankAndDonor(donorWithProfileGroup(null));
        stubWritesAccepting(10);

        assertThat(service.recordDonation(payload("O+", 1), principal()).donationId()).isNotNull();
    }

    // ---------- who may record a donation (Stage 3b-2) ----------

    /**
     * Recording a donation asks resolveForStockChange (no ADMIN branch); when it refuses,
     * no donor is read or locked and nothing is written.
     */
    @Test
    void aCallerTheStockChangeRuleRefusesCausesNoReadAndNoWrite() {
        when(actingBankResolver.resolveForStockChange(any(), eq("record a donation")))
            .thenThrow(new AccessDeniedException("Only the blood bank's own staff can record a donation."));
        AuthenticatedUser admin = new AuthenticatedUser(USER_ID, "+23276000009", "hash", Role.ADMIN);

        assertThatThrownBy(() -> service.recordDonation(
                new RecordDonationPayload(DONOR_ID, "O+", 1, BANK_ID, null), admin))
            .isInstanceOf(AccessDeniedException.class);

        verify(actingBankResolver, never()).resolve(any(), any(), any());
        verify(donorRepository, never()).findByIdForUpdate(any());
        verify(inventoryService, never()).requireAcceptableExpiry(any());
        verifyNothingWasWritten();
    }

    // ---------- other failures ----------

    @Test
    void anUnknownDonorIsRefusedAndNothingIsWritten() {
        when(actingBankResolver.resolveForStockChange(any(), any())).thenReturn(bank());
        when(donorRepository.findByIdForUpdate(DONOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordDonation(payload("O+", 1), principal()))
            .isInstanceOf(NotFoundException.class);

        verifyNothingWasWritten();
    }

    @Test
    void anUnrecognisedBloodGroupIsRefusedBeforeAnythingIsWritten() {
        when(actingBankResolver.resolveForStockChange(any(), any())).thenReturn(bank());

        assertThatThrownBy(() -> service.recordDonation(payload("Z+", 1), principal()))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNothingWasWritten();
    }
}
