package com.bloodlink.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.bloodlink.admin.dto.AdminUserResponse;
import com.bloodlink.admin.dto.CreateStaffAccountPayload;
import com.bloodlink.admin.dto.CreatedAccountResponse;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Covers what the Admin Console's user table actually needs to be trustworthy:
 * every role's facility link mapped correctly (or honestly absent), and the one
 * safety rail on suspension — an administrator cannot lock themselves out with
 * no one left to undo it.
 *
 * And, since Business Rule 14, account creation: which role may be created here,
 * which facility each role must and must not carry, and — the one this class was
 * most careful about — that the password handed to the administrator actually
 * verifies against the hash that was stored. That assertion runs the real
 * BCryptPasswordEncoder rather than a mock, because the failure it exists to catch
 * is precisely the one a mock cannot see: a hash that looks like a hash, is stored
 * without complaint, and matches nothing. This project has had that bug once
 * already, from a hand-written hash, and it surfaced only as an account that would
 * not log in.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID BANK_ID = UUID.randomUUID();

    @Mock private UserRepository userRepository;
    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private HospitalRepository hospitalRepository;

    /** The real encoder, not a mock — see the class comment. */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userRepository, bloodBankRepository, hospitalRepository, passwordEncoder);
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

    // ---------- creation (Business Rule 14) ----------

    /** Stands in for the database assigning the primary key and created_at. */
    private void stubSaveAssigningId() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setCreatedAt(Instant.now());
            return saved;
        });
    }

    /** Stubbed one facility at a time, not both together: this class runs under
     *  Mockito's default strict stubs, which report a stub no test used — and that
     *  strictness is worth keeping. */
    private void stubHospitalExists() {
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        hospital.setName("Makeni Regional Hospital");
        when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
    }

    private void stubBankExists() {
        BloodBank bank = new BloodBank();
        bank.setId(BANK_ID);
        bank.setName("Makeni Regional Blood Bank");
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank));
    }

    private static CreateStaffAccountPayload payload(Role role, UUID bloodBankId, UUID hospitalId) {
        return new CreateStaffAccountPayload(
            "Isatu Bangura", "076 900 005", "Isatu@Example.com", role, bloodBankId, hospitalId
        );
    }

    /** The User handed to save() — what would actually be written to the database. */
    private User savedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void aHospitalStaffAccountIsCreatedAndLinkedToItsHospital() {
        stubSaveAssigningId();
        stubHospitalExists();

        CreatedAccountResponse created = service.createStaffAccount(
            payload(Role.HOSPITAL_STAFF, null, HOSPITAL_ID)
        );

        assertThat(created.account().role()).isEqualTo(Role.HOSPITAL_STAFF);
        assertThat(created.account().hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(created.account().hospitalName()).isEqualTo("Makeni Regional Hospital");
        assertThat(created.account().bloodBankId()).isNull();
        // Usable the moment the password is handed over — an account created
        // suspended would be a staff member holding credentials that do not work.
        assertThat(created.account().status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void aBloodBankStaffAccountIsCreatedAndLinkedToItsBank() {
        stubSaveAssigningId();
        stubBankExists();

        CreatedAccountResponse created = service.createStaffAccount(
            payload(Role.BLOOD_BANK_STAFF, BANK_ID, null)
        );

        assertThat(created.account().role()).isEqualTo(Role.BLOOD_BANK_STAFF);
        assertThat(created.account().bloodBankId()).isEqualTo(BANK_ID);
        assertThat(created.account().bloodBankName()).isEqualTo("Makeni Regional Blood Bank");
        assertThat(created.account().hospitalId()).isNull();
    }

    @Test
    void anAdministratorAccountIsCreatedWithNoFacilityAtAll() {
        stubSaveAssigningId();

        CreatedAccountResponse created = service.createStaffAccount(payload(Role.ADMIN, null, null));

        // An administrator's authority is citywide, so having no facility is the
        // correct state here, not a missing value.
        assertThat(created.account().role()).isEqualTo(Role.ADMIN);
        assertThat(created.account().bloodBankId()).isNull();
        assertThat(created.account().hospitalId()).isNull();
    }

    @Test
    void aDonorAccountCannotBeCreatedHere() {
        // Donors self-register (Business Rule 8), and that path also writes the
        // linked Donor row this endpoint has no fields for. A DONOR User without
        // one would break every donor screen, so it is refused, not half-created.
        assertThatThrownBy(() -> service.createStaffAccount(payload(Role.DONOR, null, null)))
            .isInstanceOf(InvalidFieldException.class)
            .hasMessageContaining("registration");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void hospitalStaffWithoutAHospitalIsRefused() {
        assertThatThrownBy(() -> service.createStaffAccount(payload(Role.HOSPITAL_STAFF, null, null)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("hospitalId");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void bloodBankStaffWithoutABloodBankIsRefused() {
        assertThatThrownBy(() -> service.createStaffAccount(payload(Role.BLOOD_BANK_STAFF, null, null)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("bloodBankId");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void anAdministratorCannotBeGivenAFacility() {
        // Refused rather than ignored: silently dropping the id would accept a
        // request whose author believed something untrue about what they created.
        assertThatThrownBy(() -> service.createStaffAccount(payload(Role.ADMIN, BANK_ID, null)))
            .isInstanceOf(InvalidFieldException.class);
        assertThatThrownBy(() -> service.createStaffAccount(payload(Role.ADMIN, null, HOSPITAL_ID)))
            .isInstanceOf(InvalidFieldException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aStaffAccountCannotCarryTheOtherRolesFacility() {
        assertThatThrownBy(() -> service.createStaffAccount(payload(Role.HOSPITAL_STAFF, BANK_ID, HOSPITAL_ID)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("bloodBankId");

        assertThatThrownBy(() -> service.createStaffAccount(payload(Role.BLOOD_BANK_STAFF, BANK_ID, HOSPITAL_ID)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("hospitalId");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aFacilityIdThatMatchesNoRowIsA404() {
        when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createStaffAccount(payload(Role.HOSPITAL_STAFF, null, HOSPITAL_ID)))
            .isInstanceOf(NotFoundException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aDuplicatePhoneIsRefusedAndNothingIsWritten() {
        // Checked against the NORMALISED number (Business Rule 13): the payload
        // types it the local way, and the account it collides with is stored the
        // international way. Comparing the raw string would let the same person
        // hold two accounts on one number.
        when(userRepository.existsByPhone("+23276900005")).thenReturn(true);

        assertThatThrownBy(() -> service.createStaffAccount(payload(Role.ADMIN, null, null)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("phone number");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aDuplicateEmailIsRefusedAndNothingIsWritten() {
        when(userRepository.existsByEmail("isatu@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createStaffAccount(payload(Role.ADMIN, null, null)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("email address");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void thePhoneAndEmailAreStoredNormalised() {
        stubSaveAssigningId();

        service.createStaffAccount(payload(Role.ADMIN, null, null));

        User saved = savedUser();
        // The same rule registration applies, via the same shared function, so an
        // account created here can be signed into however the number is typed.
        assertThat(saved.getPhone()).isEqualTo("+23276900005");
        assertThat(saved.getEmail()).isEqualTo("isatu@example.com");
    }

    @Test
    void theGeneratedPasswordActuallyVerifiesAgainstTheStoredHash() {
        stubSaveAssigningId();

        CreatedAccountResponse created = service.createStaffAccount(payload(Role.ADMIN, null, null));
        User saved = savedUser();

        // The assertion this whole class exists for. Not "a hash was stored" —
        // a hash that verifies nothing looks exactly the same in the database.
        // This runs the real BCrypt comparison, which is what the login endpoint
        // will run when the new staff member types the password they were given.
        assertThat(passwordEncoder.matches(created.temporaryPassword(), saved.getPasswordHash())).isTrue();

        // And the plaintext is not what was stored: the hash is a BCrypt digest
        // ("$2" prefix), and nothing anywhere holds the password itself.
        assertThat(saved.getPasswordHash())
            .isNotEqualTo(created.temporaryPassword())
            .startsWith("$2");
    }

    @Test
    void aWrongPasswordDoesNotVerifyAgainstTheStoredHash() {
        stubSaveAssigningId();

        CreatedAccountResponse created = service.createStaffAccount(payload(Role.ADMIN, null, null));
        User saved = savedUser();

        // The control for the test above: proves that assertion is actually
        // checking something, rather than matches() returning true indiscriminately.
        assertThat(passwordEncoder.matches(created.temporaryPassword() + "x", saved.getPasswordHash())).isFalse();
    }

    @Test
    void everyAccountGetsItsOwnPassword() {
        stubSaveAssigningId();

        String first = service.createStaffAccount(payload(Role.ADMIN, null, null)).temporaryPassword();
        String second = service.createStaffAccount(payload(Role.ADMIN, null, null)).temporaryPassword();

        // Generated per account, never a shared default — one leaked password must
        // not be every staff account's password.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theResponseCarriesTheNewAccountSoTheConsoleNeedNotRefetchEveryUser() {
        stubSaveAssigningId();

        CreatedAccountResponse created = service.createStaffAccount(payload(Role.ADMIN, null, null));

        assertThat(created.account().id()).isNotNull();
        assertThat(created.account().fullName()).isEqualTo("Isatu Bangura");
        assertThat(created.temporaryPassword()).isNotBlank();
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
