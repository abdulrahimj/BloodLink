package com.bloodlink.donor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.EligibilityStatus;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.donor.dto.DonationHistoryItem;
import com.bloodlink.donor.dto.DonorProfileResponse;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.web.error.NotFoundException;

/**
 * Covers the arithmetic CLAUDE.md pins down exactly: eligibility (Business Rule 2),
 * and that Donation.bloodType — not Donor.bloodType — is what history reports
 * (Business Rule 4).
 */
@ExtendWith(MockitoExtension.class)
class DonorServiceTest {

    @Mock private DonorRepository donorRepository;
    @Mock private DonationRepository donationRepository;

    // Built in @BeforeEach, not as a field initializer: field initializers run before
    // MockitoExtension injects the @Mock fields, which would leave both null here.
    private DonorService service;

    @BeforeEach
    void setUp() {
        service = new DonorService(donorRepository, donationRepository);
    }

    private static User user(BloodType unused) {
        User user = new User();
        user.setFullName("Aminata Kamara");
        user.setEmail("aminata@example.com");
        user.setPhone("+232 76 000 001");
        user.setCreatedAt(Instant.parse("2025-01-10T00:00:00Z"));
        return user;
    }

    private static Donor donor(UUID donorId, LocalDate lastDonationDate, BloodType bloodType) {
        Donor donor = new Donor();
        donor.setId(donorId);
        donor.setUser(user(bloodType));
        donor.setBloodType(bloodType);
        donor.setGender(Gender.FEMALE);
        donor.setAddress("Wusum, Makeni");
        donor.setLastDonationDate(lastDonationDate);
        return donor;
    }

    private static Donation donation(LocalDate date, int units, BloodType bloodType, String bankName) {
        BloodBank bank = new BloodBank();
        bank.setName(bankName);

        Donation donation = new Donation();
        donation.setDonationDate(date);
        donation.setUnitsDonated(units);
        donation.setBloodType(bloodType);
        donation.setBloodBank(bank);
        return donation;
    }

    @Test
    void donorWithNoDonationsIsEligibleSinceRegistration() {
        UUID userId = UUID.randomUUID();
        UUID donorId = UUID.randomUUID();
        Donor donor = donor(donorId, null, BloodType.O_POSITIVE);
        when(donorRepository.findByUserId(userId)).thenReturn(Optional.of(donor));
        when(donationRepository.findByDonorIdOrderByDonationDateDesc(donorId)).thenReturn(List.of());

        DonorProfileResponse profile = service.getMyProfile(userId);

        assertThat(profile.eligibilityStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
        assertThat(profile.eligibleSince()).isEqualTo(LocalDate.of(2025, 1, 10));
        assertThat(profile.totalDonations()).isZero();
        assertThat(profile.livesImpacted()).isZero();
        assertThat(profile.firstDonationDate()).isNull();
    }

    @Test
    void donorInsideNinetyDayWindowIsIneligible() {
        UUID userId = UUID.randomUUID();
        UUID donorId = UUID.randomUUID();
        LocalDate lastDonation = LocalDate.now().minusDays(30);
        Donor donor = donor(donorId, lastDonation, BloodType.A_NEGATIVE);
        when(donorRepository.findByUserId(userId)).thenReturn(Optional.of(donor));
        when(donationRepository.findByDonorIdOrderByDonationDateDesc(donorId))
            .thenReturn(List.of(donation(lastDonation, 1, BloodType.A_NEGATIVE, "Makeni Regional Blood Bank")));

        DonorProfileResponse profile = service.getMyProfile(userId);

        assertThat(profile.eligibilityStatus()).isEqualTo(EligibilityStatus.INELIGIBLE);
        // Day 91 after the last donation — the first day getEligibilityStatus() flips.
        assertThat(profile.eligibleSince()).isEqualTo(lastDonation.plusDays(91));
    }

    @Test
    void donorPastNinetyDayWindowIsEligibleAgain() {
        UUID userId = UUID.randomUUID();
        UUID donorId = UUID.randomUUID();
        LocalDate lastDonation = LocalDate.now().minusDays(120);
        Donor donor = donor(donorId, lastDonation, BloodType.B_POSITIVE);
        when(donorRepository.findByUserId(userId)).thenReturn(Optional.of(donor));
        when(donationRepository.findByDonorIdOrderByDonationDateDesc(donorId))
            .thenReturn(List.of(donation(lastDonation, 1, BloodType.B_POSITIVE, "Holy Spirit Blood Bank")));

        DonorProfileResponse profile = service.getMyProfile(userId);

        assertThat(profile.eligibilityStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    @Test
    void livesImpactedIsTotalUnitsTimesThree() {
        UUID userId = UUID.randomUUID();
        UUID donorId = UUID.randomUUID();
        Donor donor = donor(donorId, LocalDate.of(2026, 5, 12), BloodType.O_POSITIVE);
        when(donorRepository.findByUserId(userId)).thenReturn(Optional.of(donor));
        when(donationRepository.findByDonorIdOrderByDonationDateDesc(donorId)).thenReturn(List.of(
            donation(LocalDate.of(2026, 5, 12), 1, BloodType.O_POSITIVE, "Makeni Regional Blood Bank"),
            donation(LocalDate.of(2026, 1, 18), 2, BloodType.O_POSITIVE, "Makeni Regional Blood Bank")
        ));

        DonorProfileResponse profile = service.getMyProfile(userId);

        assertThat(profile.totalDonations()).isEqualTo(2);
        assertThat(profile.totalUnitsDonated()).isEqualTo(3);
        assertThat(profile.livesImpacted()).isEqualTo(9);
        assertThat(profile.firstDonationDate()).isEqualTo(LocalDate.of(2026, 1, 18));
    }

    @Test
    void donationHistoryReportsTheTestedTypeNotTheProfileType() {
        UUID userId = UUID.randomUUID();
        UUID donorId = UUID.randomUUID();
        // Donor profile says O+, but Business Rule 4: the donation record is what
        // was verified by testing, and must not silently follow the profile.
        Donor donor = donor(donorId, LocalDate.of(2026, 5, 12), BloodType.O_POSITIVE);
        when(donorRepository.findByUserId(userId)).thenReturn(Optional.of(donor));
        when(donationRepository.findByDonorIdOrderByDonationDateDesc(donorId)).thenReturn(List.of(
            donation(LocalDate.of(2026, 5, 12), 1, BloodType.O_NEGATIVE, "Holy Spirit Blood Bank")
        ));

        List<DonationHistoryItem> history = service.getMyDonationHistory(userId);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).bloodType()).isEqualTo("O-");
        assertThat(history.get(0).bloodBankName()).isEqualTo("Holy Spirit Blood Bank");
    }

    @Test
    void unknownBloodGroupIsReportedAsNullNotGuessed() {
        UUID userId = UUID.randomUUID();
        UUID donorId = UUID.randomUUID();
        Donor donor = donor(donorId, null, null); // "I don't know yet" at registration
        when(donorRepository.findByUserId(userId)).thenReturn(Optional.of(donor));
        when(donationRepository.findByDonorIdOrderByDonationDateDesc(donorId)).thenReturn(List.of());

        assertThat(service.getMyProfile(userId).bloodType()).isNull();
    }

    @Test
    void accountWithNoDonorRowIsReportedAsNotFound() {
        UUID userId = UUID.randomUUID();
        when(donorRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyProfile(userId))
            .isInstanceOf(NotFoundException.class);
    }
}
