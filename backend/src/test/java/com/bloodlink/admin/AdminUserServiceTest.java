package com.bloodlink.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bloodlink.admin.dto.AdminUserResponse;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Covers what the Admin Console's user table actually needs to be trustworthy:
 * every role's facility link mapped correctly (or honestly absent), and the one
 * safety rail on suspension — an administrator cannot lock themselves out with
 * no one left to undo it.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock private UserRepository userRepository;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userRepository);
    }

    private static AuthenticatedUser admin() {
        return new AuthenticatedUser(ADMIN_ID, "+232 76 000 099", "hash", Role.ADMIN);
    }

    private static User donor() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName("Rahim Barrie");
        user.setRole(Role.DONOR);
        user.setStatus(AccountStatus.ACTIVE);
        return user;
    }

    private static User bankStaff() {
        BloodBank bank = new BloodBank();
        bank.setId(UUID.randomUUID());
        bank.setName("Makeni Regional Blood Bank");

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName("Mohamed Kamara");
        user.setRole(Role.BLOOD_BANK_STAFF);
        user.setStatus(AccountStatus.ACTIVE);
        user.setBloodBank(bank);
        return user;
    }

    private static User hospitalStaff() {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Holy Spirit Hospital");

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName("Fatmata Conteh");
        user.setRole(Role.HOSPITAL_STAFF);
        user.setStatus(AccountStatus.SUSPENDED);
        user.setHospital(hospital);
        return user;
    }

    // ---------- listing ----------

    @Test
    void listingMapsEachRolesFacilityLinkOrLeavesItHonestlyAbsent() {
        when(userRepository.findAllWithFacilitiesOrderByFullNameAsc())
            .thenReturn(List.of(donor(), bankStaff(), hospitalStaff()));

        List<AdminUserResponse> users = service.listUsers();

        assertThat(users).hasSize(3);

        AdminUserResponse donorRow = users.get(0);
        assertThat(donorRow.role()).isEqualTo(Role.DONOR);
        assertThat(donorRow.bloodBankId()).isNull();
        assertThat(donorRow.hospitalId()).isNull();

        AdminUserResponse bankRow = users.get(1);
        assertThat(bankRow.bloodBankName()).isEqualTo("Makeni Regional Blood Bank");
        assertThat(bankRow.hospitalId()).isNull();

        AdminUserResponse hospitalRow = users.get(2);
        assertThat(hospitalRow.hospitalName()).isEqualTo("Holy Spirit Hospital");
        assertThat(hospitalRow.status()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(hospitalRow.bloodBankId()).isNull();
    }

    // ---------- suspend / activate ----------

    @Test
    void anAdministratorCanSuspendADifferentAccount() {
        User target = donor();
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUserResponse updated = service.updateStatus(target.getId(), AccountStatus.SUSPENDED, admin());

        assertThat(updated.status()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(target.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void anAdministratorCannotSuspendTheirOwnAccount() {
        assertThatThrownBy(() -> service.updateStatus(ADMIN_ID, AccountStatus.SUSPENDED, admin()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("own account");

        // Refused before any lookup or write — there is nothing to check the
        // target account for when the target account is the caller's own.
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void anAdministratorMayReactivateTheirOwnAccount() {
        // The guard is specifically against locking yourself out — reactivating
        // your own (already-active, in practice) account carries no such risk
        // and must not be swept up by the same rule.
        User self = donor();
        self.setId(ADMIN_ID);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(self));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUserResponse updated = service.updateStatus(ADMIN_ID, AccountStatus.ACTIVE, admin());

        assertThat(updated.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void suspendingAnUnknownUserIsA404() {
        when(userRepository.findById(OTHER_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(OTHER_USER_ID, AccountStatus.SUSPENDED, admin()))
            .isInstanceOf(NotFoundException.class);
    }
}
