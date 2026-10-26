package com.bloodlink.donor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.security.access.AccessDeniedException;

import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.EligibilityStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.donor.dto.DonorLookupResponse;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.InvalidPhoneNumberException;
import com.bloodlink.web.error.NotFoundException;

/**
 * The Record Donation lookup: reports the donor's eligibility honestly,
 * and carries the profile blood group as reference only.
 */
@ExtendWith(MockitoExtension.class)
class DonorLookupServiceTest {

    /** The stored form — what every account's phone looks like in the database once
     *  PhoneNumber.normalize has run over it (and once V11 converted the older rows). */
    private static final String PHONE = "+23276000001";

    @Mock private DonorRepository donorRepository;
    @Mock private UserRepository userRepository;
    @Mock private BloodBankRepository bloodBankRepository;

    private DonorLookupService service;
    /** An active blood bank staff member assigned to a bank: the only caller the lookup serves (Business Rule 8). */
    private AuthenticatedUser staff;

    @BeforeEach
    void setUp() {
        service = new DonorLookupService(donorRepository, new ActingBankResolver(userRepository, bloodBankRepository));
        User staffUser = account(Role.BLOOD_BANK_STAFF, AccountStatus.ACTIVE);
        staffUser.setBloodBank(new BloodBank());
        // Lenient: the refusal tests below use other principals and never read this account.
        lenient().when(userRepository.findById(staffUser.getId())).thenReturn(Optional.of(staffUser));
        staff = AuthenticatedUser.from(staffUser);
    }

    private static User account(Role role, AccountStatus status) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setPhone("+23276000099");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    // ---------- who may look a donor up ----------

    @Test
    void anAdministratorHospitalStaffOrADonorIsRefusedBeforeAnythingIsRead() {
        for (Role role : new Role[] { Role.ADMIN, Role.HOSPITAL_STAFF, Role.DONOR }) {
            AuthenticatedUser caller = AuthenticatedUser.from(account(role, AccountStatus.ACTIVE));
            assertThatThrownBy(() -> service.lookupByPhone(PHONE, caller))
                .as(role.name())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only the blood bank's own staff can look up donors.");
        }
        verifyNoInteractions(donorRepository, bloodBankRepository);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void aSuspendedOrUnassignedStaffMemberIsRefused() {
        User suspended = account(Role.BLOOD_BANK_STAFF, AccountStatus.SUSPENDED);
        suspended.setBloodBank(new BloodBank());
        User unassigned = account(Role.BLOOD_BANK_STAFF, AccountStatus.ACTIVE);
        when(userRepository.findById(suspended.getId())).thenReturn(Optional.of(suspended));
        when(userRepository.findById(unassigned.getId())).thenReturn(Optional.of(unassigned));

        assertThatThrownBy(() -> service.lookupByPhone(PHONE, AuthenticatedUser.from(suspended)))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.lookupByPhone(PHONE, AuthenticatedUser.from(unassigned)))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(donorRepository);
    }

    // ---------- what an allowed lookup returns ----------

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

        DonorLookupResponse found = service.lookupByPhone(PHONE, staff);

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

        DonorLookupResponse found = service.lookupByPhone(PHONE, staff);

        assertThat(found.eligibilityStatus()).isEqualTo(EligibilityStatus.INELIGIBLE);
        assertThat(found.lastDonationDate()).isEqualTo(lastDonation);
        // Day 91, matching what the donor's own dashboard tells them.
        assertThat(found.eligibleFrom()).isEqualTo(lastDonation.plusDays(91));
    }

    @Test
    void aDonorWhoHasNeverBeenTypedHasNoProfileGroup() {
        when(donorRepository.findByUserPhoneWithUser(PHONE))
            .thenReturn(Optional.of(donor(null, null)));

        DonorLookupResponse found = service.lookupByPhone(PHONE, staff);

        assertThat(found.profileBloodType()).isNull();
        assertThat(found.lastDonationDate()).isNull();
        assertThat(found.eligibilityStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
    }

    @Test
    void theNumberIsNormalisedSoAnyWayOfTypingItFindsTheSameDonor() {
        when(donorRepository.findByUserPhoneWithUser(PHONE))
            .thenReturn(Optional.of(donor(BloodType.O_POSITIVE, null)));

        // All three spellings are one number; staff shouldn't need to know which format the donor registered in.
        service.lookupByPhone("  +232 76 000 001  ", staff);
        service.lookupByPhone("076000001", staff);
        service.lookupByPhone("076 000 001", staff);

        verify(donorRepository, times(3)).findByUserPhoneWithUser(PHONE);
    }

    @Test
    void aNumberThatIsNotAPhoneNumberIsRefusedRatherThanReportedAsNoSuchDonor() {
        // Not a phone number (fix the typo) differs from no donor with that number (register them), so not a 404.
        assertThatThrownBy(() -> service.lookupByPhone("07600000", staff))
            .isInstanceOf(InvalidPhoneNumberException.class);

        verify(donorRepository, never()).findByUserPhoneWithUser(anyString());
    }

    @Test
    void anUnknownPhoneNumberIsA404WithSomethingActionableToDo() {
        when(donorRepository.findByUserPhoneWithUser(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lookupByPhone(PHONE, staff))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("register the donor first");
    }
}
