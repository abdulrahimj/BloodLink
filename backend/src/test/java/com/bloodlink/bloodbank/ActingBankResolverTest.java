package com.bloodlink.bloodbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.NotFoundException;

/**
 * Business Rule 8 as behaviour: which bank the caller acts as, for the services that ask this class
 * (RequestService, AppealService, DonationService), tested directly in one place.
 */
@ExtendWith(MockitoExtension.class)
class ActingBankResolverTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID BANK_ID = UUID.randomUUID();

    @Mock private UserRepository userRepository;
    @Mock private BloodBankRepository bloodBankRepository;

    private ActingBankResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ActingBankResolver(userRepository, bloodBankRepository);
    }

    private static AuthenticatedUser principal(Role role) {
        return new AuthenticatedUser(USER_ID, "+232 76 000 001", "hash", role);
    }

    private static BloodBank bank(UUID id) {
        BloodBank bank = new BloodBank();
        bank.setId(id);
        bank.setName("Makeni Regional Blood Bank");
        return bank;
    }

    // ---------- BLOOD_BANK_STAFF ----------

    @Test
    void bankStaffAlwaysActAsTheirOwnBankRegardlessOfWhatWasRequested() {
        User staff = new User();
        staff.setId(USER_ID);
        staff.setBloodBank(bank(BANK_ID));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff));

        UUID someOtherBankId = UUID.randomUUID();
        BloodBank resolved = resolver.resolve(someOtherBankId, principal(Role.BLOOD_BANK_STAFF), "act");

        // The body named a different bank; the staff member's own row wins anyway.
        assertThat(resolved.getId()).isEqualTo(BANK_ID);
    }

    @Test
    void bankStaffWithNoAssignedBankHaveAccessToNone() {
        User staff = new User();
        staff.setId(USER_ID);
        staff.setBloodBank(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> resolver.resolve(BANK_ID, principal(Role.BLOOD_BANK_STAFF), "act"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("not assigned to a blood bank");
    }

    @Test
    void anAccountThatNoLongerExistsIsRefusedRatherThanCrashing() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(BANK_ID, principal(Role.BLOOD_BANK_STAFF), "act"))
            .isInstanceOf(NotFoundException.class);
    }

    // ---------- ADMIN ----------

    @Test
    void anAdministratorMustNameABankAndCannotActWithoutOne() {
        assertThatThrownBy(() -> resolver.resolve(null, principal(Role.ADMIN), "act"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be named");
    }

    @Test
    void anAdministratorNamingARealBankActsAsIt() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));

        BloodBank resolved = resolver.resolve(BANK_ID, principal(Role.ADMIN), "act");

        assertThat(resolved.getId()).isEqualTo(BANK_ID);
    }

    @Test
    void anAdministratorNamingAnUnknownBankIsRefused() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(BANK_ID, principal(Role.ADMIN), "act"))
            .isInstanceOf(NotFoundException.class);
    }

    // ---------- everyone else ----------

    @Test
    void hospitalStaffMayNotActAsAnyBank() {
        assertThatThrownBy(() -> resolver.resolve(BANK_ID, principal(Role.HOSPITAL_STAFF), "act on a blood request"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Only blood bank staff can act on a blood request");
    }

    @Test
    void donorsMayNotActAsAnyBank() {
        assertThatThrownBy(() -> resolver.resolve(BANK_ID, principal(Role.DONOR), "send a donation appeal"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Only blood bank staff can send a donation appeal");
    }

    // ---------- resolveForStockChange: Business Rule 8, Stages 3b and 3b-2 ----------

    @Test
    void forAStockChangeActiveBankStaffActAsTheirOwnBank() {
        User staff = new User();
        staff.setId(USER_ID);
        staff.setBloodBank(bank(BANK_ID));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff));

        assertThat(resolver.resolveForStockChange(principal(Role.BLOOD_BANK_STAFF), "record a donation").getId())
            .isEqualTo(BANK_ID);
    }

    /** No ADMIN branch at all: an administrator cannot change stock on any bank's
     *  behalf, and nothing is looked up — not the account, not a bank. */
    @Test
    void forAStockChangeAnAdministratorHospitalStaffAndDonorsAreRefusedBeforeAnythingIsRead() {
        for (Role role : new Role[] { Role.ADMIN, Role.HOSPITAL_STAFF, Role.DONOR }) {
            assertThatThrownBy(() -> resolver.resolveForStockChange(principal(role), "record a donation"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only the blood bank's own staff can record a donation.");
        }
        verifyNoInteractions(userRepository, bloodBankRepository);
    }

    @Test
    void forAStockChangeASuspendedOrUnassignedStaffAccountIsRefused() {
        User suspended = new User();
        suspended.setId(USER_ID);
        suspended.setBloodBank(bank(BANK_ID));
        suspended.setStatus(AccountStatus.SUSPENDED);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(suspended));
        assertThatThrownBy(() -> resolver.resolveForStockChange(principal(Role.BLOOD_BANK_STAFF), "act"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("This account is suspended.");

        User unassigned = new User();
        unassigned.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(unassigned));
        assertThatThrownBy(() -> resolver.resolveForStockChange(principal(Role.BLOOD_BANK_STAFF), "act"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("not assigned to a blood bank");
    }
}
