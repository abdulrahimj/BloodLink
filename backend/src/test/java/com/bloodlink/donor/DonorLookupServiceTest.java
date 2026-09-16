package com.bloodlink.donor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.bloodlink.web.error.InvalidPhoneNumberException;
import com.bloodlink.web.error.NotFoundException;

/**
 * The lookup a blood bank runs before recording a donation. What matters here is
 * that it surfaces the donor's eligibility honestly — the Record Donation screen
 * shows that to the staff member as the one fact they must weigh before drawing
 * blood — and that the profile blood group is carried as reference only.
 */
@ExtendWith(MockitoExtension.class)
class DonorLookupServiceTest {

    /** The stored form — what every account's phone looks like in the database once
     *  PhoneNumber.normalize has run over it (and once V11 converted the older rows). */
    private static final String PHONE = "+23276000001";

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
    void theNumberIsNormalisedSoAnyWayOfTypingItFindsTheSameDonor() {
        when(donorRepository.findByUserPhoneWithUser(PHONE))
            .thenReturn(Optional.of(donor(BloodType.O_POSITIVE, null)));

        // The staff member has a donor standing in front of them reading out a
        // number; they should not also have to know which of the accepted formats
        // that person happened to register in. All three of these are one number.
        service.lookupByPhone("  +232 76 000 001  ");
        service.lookupByPhone("076000001");
        service.lookupByPhone("076 000 001");

        verify(donorRepository, times(3)).findByUserPhoneWithUser(PHONE);
    }

    @Test
    void aNumberThatIsNotAPhoneNumberIsRefusedRatherThanReportedAsNoSuchDonor() {
        // "That is not a phone number" and "no donor has that number" are different
        // problems: the first is a typo to correct here, the second sends the staff
        // member off to register the donor. Answering 404 to the first would start
        // that second errand for no reason.
        assertThatThrownBy(() -> service.lookupByPhone("07600000"))
            .isInstanceOf(InvalidPhoneNumberException.class);

        verify(donorRepository, never()).findByUserPhoneWithUser(anyString());
    }

    @Test
    void anUnknownPhoneNumberIsA404WithSomethingActionableToDo() {
        when(donorRepository.findByUserPhoneWithUser(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lookupByPhone(PHONE))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("register the donor first");
    }
}
