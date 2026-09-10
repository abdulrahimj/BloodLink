package com.bloodlink.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.donation.dto.DonationRecordedResponse;
import com.bloodlink.donation.dto.RecordDonationPayload;
import com.bloodlink.inventory.InventoryService;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.NotFoundException;

/**
 * The three effects of recording a donation, and the two rules that are easiest
 * to get wrong here: the stored blood group must be the one tested today rather
 * than the donor's profile value (Business Rule 4), and the donor's eligibility
 * clock must actually be reset, or the appeal filter will invite them straight
 * back.
 */
@ExtendWith(MockitoExtension.class)
class DonationServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID DONOR_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

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
        return new RecordDonationPayload(DONOR_ID, testedGroup, units, null);
    }

    /** Stands in for the real accumulation, which InventoryService owns and its
     *  own tests already cover — here we only care what it was asked to do. */
    private void stubInventoryAccepting(int resultingUnits) {
        when(inventoryService.addBatch(any(), any(), any(), any()))
            .thenAnswer(inv -> new InventoryItemResponse(
                inv.getArgument(1), resultingUnits, LocalDate.now().plusDays(30),
                Instant.now(), StockStatus.SUFFICIENT
            ));
    }

    private void stubDonorAndBank(Donor donor) {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());
        when(donorRepository.findById(DONOR_ID)).thenReturn(Optional.of(donor));
        when(donationRepository.save(any(Donation.class))).thenAnswer(inv -> {
            Donation d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
    }

    // ---------- the three effects ----------

    @Test
    void recordingWritesTheDonationResetsTheDonorsClockAndAddsTheUnitsToStock() {
        Donor donor = donorWithProfileGroup(LocalDate.now().minusYears(1));
        stubDonorAndBank(donor);
        stubInventoryAccepting(46);

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
        stubDonorAndBank(donor);
        stubInventoryAccepting(12);

        service.recordDonation(payload("O-", 1), principal());

        ArgumentCaptor<Donation> donationCaptor = ArgumentCaptor.forClass(Donation.class);
        verify(donationRepository).save(donationCaptor.capture());
        assertThat(donationCaptor.getValue().getBloodType()).isEqualTo(BloodType.O_NEGATIVE);

        // The units must land against O- stock too, not A+.
        verify(inventoryService).addBatch(eq(BANK_ID), eq("O-"), any(), any());

        // And the profile value is left exactly as it was — recording a donation
        // is not a back door for editing the donor's record.
        assertThat(donor.getBloodType()).isEqualTo(BloodType.A_POSITIVE);
    }

    @Test
    void unitsEnterStockWithTheDefaultShelfLife() {
        Donor donor = donorWithProfileGroup(null);
        stubDonorAndBank(donor);
        stubInventoryAccepting(3);

        service.recordDonation(payload("AB-", 2), principal());

        ArgumentCaptor<AddBatchRequest> batchCaptor = ArgumentCaptor.forClass(AddBatchRequest.class);
        verify(inventoryService).addBatch(eq(BANK_ID), eq("AB-"), batchCaptor.capture(), any());

        assertThat(batchCaptor.getValue().unitsToAdd()).isEqualTo(2);
        assertThat(batchCaptor.getValue().expiryDate())
            .isEqualTo(LocalDate.now().plusDays(DonationService.SHELF_LIFE_DAYS));
    }

    // ---------- eligibility is documented, not enforced ----------

    @Test
    void aDonationFromADonorInsideTheirRecoveryWindowIsStillRecorded() {
        // The draw has already happened. Refusing the record would leave the units
        // unaccounted for on the shelf and the donor's history wrong — see the
        // reasoning on recordDonation. The screen warns; the service records.
        Donor recentDonor = donorWithProfileGroup(LocalDate.now().minusDays(10));
        assertThat(recentDonor.isEligibleToDonate()).isFalse();
        stubDonorAndBank(recentDonor);
        stubInventoryAccepting(20);

        DonationRecordedResponse response = service.recordDonation(payload("O+", 1), principal());

        assertThat(response.donationId()).isNotNull();
        assertThat(recentDonor.getLastDonationDate()).isEqualTo(LocalDate.now());
    }

    // ---------- failure ----------

    @Test
    void anUnknownDonorIsRefusedAndNothingIsWritten() {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());
        when(donorRepository.findById(DONOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordDonation(payload("O+", 1), principal()))
            .isInstanceOf(NotFoundException.class);

        verify(donationRepository, never()).save(any());
        verify(inventoryService, never()).addBatch(any(), any(), any(), any());
    }

    @Test
    void anUnrecognisedBloodGroupIsRefusedBeforeAnythingIsWritten() {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());

        assertThatThrownBy(() -> service.recordDonation(payload("Z+", 1), principal()))
            .isInstanceOf(IllegalArgumentException.class);

        verify(donationRepository, never()).save(any());
        verify(inventoryService, never()).addBatch(any(), any(), any(), any());
    }
}
