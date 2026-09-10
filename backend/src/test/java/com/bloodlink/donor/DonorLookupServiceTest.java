package com.bloodlink.donor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.EligibilityStatus;
import com.bloodlink.donor.dto.DonorLookupResponse;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.web.error.NotFoundException;

/**
 * The lookup a blood bank runs before recording a donation. What matters here is
 * that it surfaces the donor's eligibility honestly — the Record Donation screen
 * shows that to the staff member as the one fact they must weigh before drawing
 * blood — and that the profile blood group is carried as reference only.
 */
@ExtendWith(MockitoExtension.class)
class DonorLookupServiceTest {

    private static final String PHONE = "+232 76 000 001";

    @Mock private DonorRepository donorRepository;

    private DonorLookupService service;

    @BeforeEach
    void setUp() {
        service = new DonorLookupService(donorRepository);
    }

    private static Donor donor(BloodType profileGroup, LocalDate lastDonationDate) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName("Rahim Barrie");
        user.setPhone(PHONE);
        user.setCreatedAt(Instant.now().minusSeconds(86_400));

        Donor donor = new Donor();
        donor.setId(UUID.randomUUID());
        donor.setUser(user);
        donor.setBloodType(profileGroup);
        donor.setLastDonationDate(lastDonationDate);
        return donor;
    }

    @Test
    void anEligibleDonorIsReturnedWithTheirProfileGroupForReference() {
        when(donorRepository.findByUserPhoneWithUser(PHONE))
            .thenReturn(Optional.of(donor(BloodType.A_POSITIVE, LocalDate.now().minusDays(200))));

        DonorLookupResponse found = service.lookupByPhone(PHONE);

        assertThat(found.fullName()).isEqualTo("Rahim Barrie");
        assertThat(found.eligibilityStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
        // Carried so a staff member can spot a mismatch against today's test —
        // never used as the value stored on the donation (Business Rule 4).
        assertThat(found.profileBloodType()).isEqualTo("A+");
    }

    @Test
    void aDonorInsideTheirRecoveryWindowReportsWhenTheyBecomeEligibleAgain() {
        LocalDate lastDonation = LocalDate.now().minusDays(10);
        when(donorRepository.findByUserPhoneWithUser(PHONE))
            .thenReturn(Optional.of(donor(BloodType.O_NEGATIVE, lastDonation)));

        DonorLookupResponse found = service.lookupByPhone(PHONE);

        assertThat(found.eligibilityStatus()).isEqualTo(EligibilityStatus.INELIGIBLE);
        assertThat(found.lastDonationDate()).isEqualTo(lastDonation);
        // Day 91, matching what the donor's own dashboard tells them.
        assertThat(found.eligibleFrom()).isEqualTo(lastDonation.plusDays(91));
    }

    @Test
    void aDonorWhoHasNeverBeenTypedHasNoProfileGroup() {
        when(donorRepository.findByUserPhoneWithUser(PHONE))
            .thenReturn(Optional.of(donor(null, null)));

        DonorLookupResponse found = service.lookupByPhone(PHONE);

        assertThat(found.profileBloodType()).isNull();
        assertThat(found.lastDonationDate()).isNull();
        assertThat(found.eligibilityStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    @Test
    void surroundingWhitespaceIsTrimmedBeforeMatching() {
        when(donorRepository.findByUserPhoneWithUser(PHONE))
            .thenReturn(Optional.of(donor(BloodType.O_POSITIVE, null)));

        service.lookupByPhone("  " + PHONE + "  ");

        verify(donorRepository).findByUserPhoneWithUser(PHONE);
    }

    @Test
    void anUnknownPhoneNumberIsA404WithSomethingActionableToDo() {
        when(donorRepository.findByUserPhoneWithUser(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lookupByPhone(PHONE))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("register the donor first");
    }
}
