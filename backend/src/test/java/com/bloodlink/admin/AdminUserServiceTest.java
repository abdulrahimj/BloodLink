package com.bloodlink.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.bloodlink.admin.dto.AdminUserPageResponse;
import com.bloodlink.admin.dto.AdminUserResponse;
import com.bloodlink.admin.dto.CreateStaffAccountPayload;
import com.bloodlink.admin.dto.CreatedAccountResponse;
import com.bloodlink.admin.dto.EditAccountPayload;
import com.bloodlink.audit.AuditLogService;
import com.bloodlink.booking.DonationBookingService;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.passwordreset.PasswordResetRequestService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.security.PasswordChangedEvent;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * The Admin Console's user rules: facility links per role, no self-suspension, and account creation (Business Rule 14).
 * Uses the real BCryptPasswordEncoder to prove the handed-out password verifies against the stored hash.
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
    @Mock private AuditLogService auditLogService;
    @Mock private DonationBookingService donationBookingService;
    @Mock private NotificationService notificationService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PasswordResetRequestService passwordResetRequestService;

    /** The real encoder, not a mock — see the class comment. */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(
            userRepository, bloodBankRepository, hospitalRepository, passwordEncoder, auditLogService, donationBookingService,
            notificationService, passwordResetRequestService, eventPublisher);
    }

    /** Creates an account as the test administrator — the actor the audit log records. */
    private CreatedAccountResponse create(CreateStaffAccountPayload payload) {
        return service.createStaffAccount(payload, admin());
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

    /** Stubbed one facility at a time, so Mockito's strict stubs still report unused stubs. */
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

        CreatedAccountResponse created = create(
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

        CreatedAccountResponse created = create(
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

        CreatedAccountResponse created = create(payload(Role.ADMIN, null, null));

        // An administrator's authority is citywide, so having no facility is the
        // correct state here, not a missing value.
        assertThat(created.account().role()).isEqualTo(Role.ADMIN);
        assertThat(created.account().bloodBankId()).isNull();
        assertThat(created.account().hospitalId()).isNull();
    }

    @Test
    void everyRoleAnAdministratorCreatesMustReplaceItsTemporaryPassword() {
        // Business Rule 27: the administrator has seen the password, so the holder must change it at first sign-in.
        stubSaveAssigningId();
        stubHospitalExists();
        stubBankExists();

        create(payload(Role.HOSPITAL_STAFF, null, HOSPITAL_ID));
        create(payload(Role.BLOOD_BANK_STAFF, BANK_ID, null));
        create(payload(Role.ADMIN, null, null));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository, org.mockito.Mockito.times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(User::getRole)
            .containsExactly(Role.HOSPITAL_STAFF, Role.BLOOD_BANK_STAFF, Role.ADMIN);
        assertThat(saved.getAllValues()).allMatch(User::isMustChangePassword);
    }

    @Test
    void aDonorAccountCannotBeCreatedHere() {
        // Donors self-register with a Donor row this endpoint cannot create, so DONOR is refused, not half-created.
        assertThatThrownBy(() -> create(payload(Role.DONOR, null, null)))
            .isInstanceOf(InvalidFieldException.class)
            .hasMessageContaining("registration");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void hospitalStaffWithoutAHospitalIsRefused() {
        assertThatThrownBy(() -> create(payload(Role.HOSPITAL_STAFF, null, null)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("hospitalId");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void bloodBankStaffWithoutABloodBankIsRefused() {
        assertThatThrownBy(() -> create(payload(Role.BLOOD_BANK_STAFF, null, null)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("bloodBankId");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void anAdministratorCannotBeGivenAFacility() {
        // Refused rather than ignored: silently dropping the id would accept a
        // request whose author believed something untrue about what they created.
        assertThatThrownBy(() -> create(payload(Role.ADMIN, BANK_ID, null)))
            .isInstanceOf(InvalidFieldException.class);
        assertThatThrownBy(() -> create(payload(Role.ADMIN, null, HOSPITAL_ID)))
            .isInstanceOf(InvalidFieldException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aStaffAccountCannotCarryTheOtherRolesFacility() {
        assertThatThrownBy(() -> create(payload(Role.HOSPITAL_STAFF, BANK_ID, HOSPITAL_ID)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("bloodBankId");

        assertThatThrownBy(() -> create(payload(Role.BLOOD_BANK_STAFF, BANK_ID, HOSPITAL_ID)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("hospitalId");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aFacilityIdThatMatchesNoRowIsA404() {
        when(hospitalRepository.findById(HOSPITAL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> create(payload(Role.HOSPITAL_STAFF, null, HOSPITAL_ID)))
            .isInstanceOf(NotFoundException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aDuplicatePhoneIsRefusedAndNothingIsWritten() {
        // Checked against the normalised number (Business Rule 13), so one number typed two ways is still a duplicate.
        when(userRepository.existsByPhone("+23276900005")).thenReturn(true);

        assertThatThrownBy(() -> create(payload(Role.ADMIN, null, null)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("phone number");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aDuplicateEmailIsRefusedAndNothingIsWritten() {
        when(userRepository.existsByEmail("isatu@example.com")).thenReturn(true);

        assertThatThrownBy(() -> create(payload(Role.ADMIN, null, null)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("email address");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void thePhoneAndEmailAreStoredNormalised() {
        stubSaveAssigningId();

        create(payload(Role.ADMIN, null, null));

        User saved = savedUser();
        // The same rule registration applies, via the same shared function, so an
        // account created here can be signed into however the number is typed.
        assertThat(saved.getPhone()).isEqualTo("+23276900005");
        assertThat(saved.getEmail()).isEqualTo("isatu@example.com");
    }

    @Test
    void theGeneratedPasswordActuallyVerifiesAgainstTheStoredHash() {
        stubSaveAssigningId();

        CreatedAccountResponse created = create(payload(Role.ADMIN, null, null));
        User saved = savedUser();

        // The key assertion: the real BCrypt comparison login will run, not merely that a hash was stored.
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

        CreatedAccountResponse created = create(payload(Role.ADMIN, null, null));
        User saved = savedUser();

        // The control for the test above: proves that assertion is actually
        // checking something, rather than matches() returning true indiscriminately.
        assertThat(passwordEncoder.matches(created.temporaryPassword() + "x", saved.getPasswordHash())).isFalse();
    }

    @Test
    void everyAccountGetsItsOwnPassword() {
        stubSaveAssigningId();

        String first = create(payload(Role.ADMIN, null, null)).temporaryPassword();
        String second = create(payload(Role.ADMIN, null, null)).temporaryPassword();

        // Generated per account, never a shared default — one leaked password must
        // not be every staff account's password.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theResponseCarriesTheNewAccountSoTheConsoleNeedNotRefetchEveryUser() {
        stubSaveAssigningId();

        CreatedAccountResponse created = create(payload(Role.ADMIN, null, null));

        assertThat(created.account().id()).isNotNull();
        assertThat(created.account().fullName()).isEqualTo("Isatu Bangura");
        assertThat(created.temporaryPassword()).isNotBlank();
    }

    // ---------- searching, filtering, sorting, paging ----------

    /** Captures exactly what searchUsers hands the repository. */
    private record SearchCall(String q, String textPattern, String phonePattern, Role role,
                              AccountStatus status, Pageable pageable) {}

    private SearchCall search(String q, String role, String status, String sort, String direction) {
        when(userRepository.searchAccounts(any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(Page.empty());
        service.searchUsers(0, 13, q, role, status, sort, direction);

        ArgumentCaptor<String> qArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> phone = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Role> roleArg = ArgumentCaptor.forClass(Role.class);
        ArgumentCaptor<AccountStatus> statusArg = ArgumentCaptor.forClass(AccountStatus.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).searchAccounts(qArg.capture(), text.capture(), phone.capture(),
            roleArg.capture(), statusArg.capture(), pageable.capture());
        return new SearchCall(qArg.getValue(), text.getValue(), phone.getValue(),
            roleArg.getValue(), statusArg.getValue(), pageable.getValue());
    }

    @Test
    void theDefaultOrderIsNameAscendingWithIdAsTheTieBreaker() {
        Sort sort = search(null, null, null, "name", "asc").pageable().getSort();

        assertThat(sort.toList()).containsExactly(Sort.Order.asc("fullName"), Sort.Order.asc("id"));
    }

    @Test
    void eachWhitelistedSortMapsToItsPropertyInEitherDirectionAndIdAlwaysComesLast() {
        assertThat(search(null, null, null, "role", "desc").pageable().getSort().toList())
            .containsExactly(Sort.Order.desc("role"), Sort.Order.asc("id"));
    }

    @Test
    void theWhitelistIsExactlyTheFourSortChoicesTheTableOffers() {
        assertThat(AdminUserService.SORT_PROPERTIES).containsOnly(
            java.util.Map.entry("name", "fullName"), java.util.Map.entry("role", "role"),
            java.util.Map.entry("createdAt", "createdAt"), java.util.Map.entry("status", "status"));
        assertThat(search(null, null, null, "createdAt", "desc").pageable().getSort().toList())
            .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    }

    @Test
    void aSortOutsideTheWhitelistIsRefusedBeforeTheDatabaseIsAsked() {
        assertThatThrownBy(() -> service.searchUsers(0, 13, null, null, null, "password_hash", "asc"))
            .isInstanceOfSatisfying(InvalidFieldException.class, ex -> assertThat(ex.getField()).isEqualTo("sort"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void aDirectionOtherThanAscOrDescIsRefused() {
        assertThatThrownBy(() -> service.searchUsers(0, 13, null, null, null, "name", "sideways"))
            .isInstanceOfSatisfying(InvalidFieldException.class, ex -> assertThat(ex.getField()).isEqualTo("direction"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void nameAndEmailSearchIsLowerCasedAndContainsAnywhere() {
        SearchCall call = search("  KamaRA ", null, null, "name", "asc");

        assertThat(call.q()).isEqualTo("KamaRA");
        assertThat(call.textPattern()).isEqualTo("%kamara%");
    }

    @Test
    void anEmailFragmentIsMatchedThroughTheSameLowerCasedPattern() {
        assertThat(search("TestDonor.015@Seed", null, null, "name", "asc").textPattern())
            .isEqualTo("%testdonor.015@seed%");
    }

    @Test
    void aPhoneTypedInLocalFormBecomesTheStoredInternationalForm() {
        assertThat(search("076900015", null, null, "name", "asc").phonePattern()).isEqualTo("%+23276900015%");
    }

    @Test
    void aPhoneTypedInInternationalFormWithSpacesMatchesTheSameWay() {
        assertThat(search("+232 769 000", null, null, "name", "asc").phonePattern()).isEqualTo("%+232769000%");
    }

    @Test
    void aPartialPhoneIsMatchedAsTypedAnywhereInTheNumber() {
        assertThat(search("900", null, null, "name", "asc").phonePattern()).isEqualTo("%900%");
    }

    @Test
    void percentAndUnderscoreAreEscapedSoTheyMatchOnlyThemselves() {
        SearchCall call = search("50%_x", null, null, "name", "asc");

        assertThat(call.textPattern()).isEqualTo("%50\\%\\_x%");
        assertThat(call.phonePattern()).isEqualTo("%50\\%\\_x%");
    }

    @Test
    void blankSearchMeansNoSearchAtAll() {
        SearchCall call = search("   ", null, null, "name", "asc");

        assertThat(call.q()).isNull();
        assertThat(call.textPattern()).isNull();
        assertThat(call.phonePattern()).isNull();
    }

    @Test
    void roleAndStatusFiltersArePassedAsTheirEnums() {
        SearchCall call = search(null, "DONOR", "SUSPENDED", "name", "asc");

        assertThat(call.role()).isEqualTo(Role.DONOR);
        assertThat(call.status()).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void noFiltersArePassedAsNull() {
        SearchCall call = search(null, null, null, "name", "asc");

        assertThat(call.role()).isNull();
        assertThat(call.status()).isNull();
    }

    @Test
    void thePageMathsComeFromTheDatabasesCountNotFromTheRowsOnThisPage() {
        when(userRepository.searchAccounts(any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenAnswer(call -> new PageImpl<>(List.of(donor(), bankStaff()), call.getArgument(5), 28));

        AdminUserPageResponse page = service.searchUsers(2, 13, null, null, null, "name", "asc");

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(13);
        assertThat(page.totalElements()).isEqualTo(28);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.content()).hasSize(2);
    }

    @Test
    void theResponseRowHasNoFieldThatCouldCarryAPasswordHash() {
        List<String> fields = java.util.Arrays.stream(AdminUserResponse.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName).toList();

        assertThat(fields).noneMatch(name -> name.toLowerCase().contains("password") || name.toLowerCase().contains("hash"));
    }

    @Test
    void searchMapsEachRolesFacilityLinkOrLeavesItHonestlyAbsent() {
        when(userRepository.searchAccounts(any(), any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(donor(), bankStaff(), hospitalStaff())));

        List<AdminUserResponse> users = service.searchUsers(0, 13, null, null, null, "name", "asc").content();

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
        // The guard is only against locking yourself out; reactivating your own account must still work.
        User self = donor();
        self.setId(ADMIN_ID);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(self));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUserResponse updated = service.updateStatus(ADMIN_ID, AccountStatus.ACTIVE, admin());

        assertThat(updated.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    // ---------- the last active administrator ----------

    private static User activeAdmin() {
        User user = donor();
        user.setRole(Role.ADMIN);
        return user;
    }

    @Test
    void theLastActiveAdministratorCannotBeSuspendedAndNothingChanges() {
        // Reached by a caller who is no longer active themselves, e.g. two admins suspending each other at once.
        User lastAdmin = activeAdmin();
        when(userRepository.findById(lastAdmin.getId())).thenReturn(Optional.of(lastAdmin));
        when(userRepository.lockByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)).thenReturn(List.of(lastAdmin));

        assertThatThrownBy(() -> service.updateStatus(lastAdmin.getId(), AccountStatus.SUSPENDED, admin()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("last active administrator");

        assertThat(lastAdmin.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void anAdministratorCanBeSuspendedWhileAnotherStaysActive() {
        User target = activeAdmin();
        User otherAdmin = activeAdmin();
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.lockByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)).thenReturn(List.of(target, otherAdmin));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUserResponse updated = service.updateStatus(target.getId(), AccountStatus.SUSPENDED, admin());

        assertThat(updated.status()).isEqualTo(AccountStatus.SUSPENDED);
    }

    @Test
    void suspendingAnAccountThatIsNotAnActiveAdministratorTakesNoAdminLock() {
        User target = bankStaff();
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStatus(target.getId(), AccountStatus.SUSPENDED, admin());

        verify(userRepository, never()).lockByRoleAndStatus(any(), any());
    }

    @Test
    void suspendingAnUnknownUserIsA404() {
        when(userRepository.findById(OTHER_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(OTHER_USER_ID, AccountStatus.SUSPENDED, admin()))
            .isInstanceOf(NotFoundException.class);
    }

    // ---------- the audit trail for the three existing actions (Business Rule 17) ----------

    @Test
    void creatingAnAccountRecordsWhoCreatedItAndNeverThePassword() {
        stubSaveAssigningId();
        stubHospitalExists();

        CreatedAccountResponse created = create(payload(Role.HOSPITAL_STAFF, null, HOSPITAL_ID));
        User saved = savedUser();

        // Actor is the calling administrator; target is the account just created.
        verify(auditLogService).record(eq(ADMIN_ID), eq(AuditAction.ACCOUNT_CREATED), eq(saved), argThat(details ->
            details.contains("Hospital Staff")
                && details.contains("Isatu Bangura")
                && details.contains("+23276900005")
                && details.contains("Makeni Regional Hospital")
                // The one-time password must never reach a table anyone can read.
                && !details.contains(created.temporaryPassword())));
    }

    @Test
    void aRefusedCreationRecordsNothing() {
        when(userRepository.existsByPhone("+23276900005")).thenReturn(true);

        assertThatThrownBy(() -> create(payload(Role.ADMIN, null, null))).isInstanceOf(ConflictException.class);

        verifyNoInteractions(auditLogService);
    }

    @Test
    void suspendingRecordsAnAccountSuspendedEntry() {
        User target = donor();
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStatus(target.getId(), AccountStatus.SUSPENDED, admin());

        verify(auditLogService).record(ADMIN_ID, AuditAction.ACCOUNT_SUSPENDED, target, "Account suspended.");
    }

    @Test
    void reactivatingRecordsAnAccountReactivatedEntry() {
        User target = hospitalStaff(); // starts SUSPENDED
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStatus(target.getId(), AccountStatus.ACTIVE, admin());

        verify(auditLogService).record(ADMIN_ID, AuditAction.ACCOUNT_REACTIVATED, target, "Account reactivated.");
    }

    @Test
    void settingAStatusAnAccountAlreadyHasRecordsNothing() {
        // Still the harmless no-op it always was — and the log is of things that
        // happened, so nothing is recorded for it.
        User target = donor(); // already ACTIVE
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStatus(target.getId(), AccountStatus.ACTIVE, admin());

        verifyNoInteractions(auditLogService);
    }

    // ---------- editing another account (Business Rule 17) ----------

    private static EditAccountPayload edit(String phone, UUID bloodBankId, UUID hospitalId) {
        return new EditAccountPayload(phone, bloodBankId, hospitalId, null);
    }

    private User stubTarget(User target) {
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        return target;
    }

    @Test
    void anAdministratorEditsAnotherAccountsPhone() {
        User target = donor();
        target.setPhone("+23276111111");
        stubTarget(target);

        AdminUserResponse updated = service.editAccount(target.getId(), edit("076 222 222", null, null), admin());

        // Normalised by Business Rule 13's shared function, whichever way it was typed.
        assertThat(updated.phone()).isEqualTo("+23276222222");
        assertThat(target.getPhone()).isEqualTo("+23276222222");
        verify(auditLogService).record(ADMIN_ID, AuditAction.ACCOUNT_EDITED, target,
            "Phone changed from +23276111111 to +23276222222.");
    }

    @Test
    void aPhoneBelongingToAnotherAccountIsRefusedAndNothingChanges() {
        User target = donor();
        target.setPhone("+23276111111");
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        // Stored internationally; typed locally — still the same number.
        when(userRepository.existsByPhone("+23276222222")).thenReturn(true);

        assertThatThrownBy(() -> service.editAccount(target.getId(), edit("076222222", null, null), admin()))
            .isInstanceOf(ConflictException.class)
            .hasMessage("An account already exists for this phone number.");

        assertThat(target.getPhone()).isEqualTo("+23276111111");
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void keepingAnAccountsOwnPhoneIsNotAClashWithItself() {
        User target = donor();
        target.setPhone("+23276111111");
        stubTarget(target);

        service.editAccount(target.getId(), edit("+232 76 111 111", null, null), admin());

        verify(userRepository, never()).existsByPhone(any());
    }

    @Test
    void movingBloodBankStaffToAnotherBankIsRecordedWithBothBanks() {
        User target = stubTarget(bankStaff()); // at Makeni Regional Blood Bank
        target.setPhone("+23276111111");
        BloodBank other = new BloodBank();
        other.setId(BANK_ID);
        other.setName("Holy Spirit Blood Bank");
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(other));

        AdminUserResponse updated = service.editAccount(target.getId(), edit("+23276111111", BANK_ID, null), admin());

        assertThat(updated.bloodBankName()).isEqualTo("Holy Spirit Blood Bank");
        verify(auditLogService).record(ADMIN_ID, AuditAction.ACCOUNT_EDITED, target,
            "Blood bank changed from Makeni Regional Blood Bank to Holy Spirit Blood Bank.");
    }

    @Test
    void theFacilityRuleIsJudgedAgainstTheRoleTheAccountAlreadyHas() {
        // Staff must keep a facility; a donor or administrator must not be given one —
        // the same rule as account creation (Business Rule 14).
        User hospitalTarget = hospitalStaff();
        hospitalTarget.setPhone("+23276111111");
        when(userRepository.findById(hospitalTarget.getId())).thenReturn(Optional.of(hospitalTarget));
        assertThatThrownBy(() -> service.editAccount(hospitalTarget.getId(), edit("+23276111111", null, null), admin()))
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("hospitalId");

        User donorTarget = donor();
        donorTarget.setPhone("+23276222222");
        when(userRepository.findById(donorTarget.getId())).thenReturn(Optional.of(donorTarget));
        assertThatThrownBy(() -> service.editAccount(donorTarget.getId(), edit("+23276222222", null, HOSPITAL_ID), admin()))
            .extracting(ex -> ((InvalidFieldException) ex).getField())
            .isEqualTo("hospitalId");

        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void roleCannotBeChangedByAnEdit() {
        User target = donor();

        assertThatThrownBy(() -> service.editAccount(target.getId(),
                new EditAccountPayload("+23276111111", null, null, java.util.Map.of("role", "ADMIN")), admin()))
            .isInstanceOf(InvalidFieldException.class)
            .hasMessageContaining("role cannot be changed");

        // Refused on the shape of the request alone, before the account is even
        // looked up — so nothing about it can have been touched.
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void anAdministratorCannotEditTheirOwnPhoneOrFacilityHere() {
        // Business Rule 16 keeps every account holder, administrators included, from
        // changing their own phone; this endpoint must not be a way round that.
        assertThatThrownBy(() -> service.editAccount(ADMIN_ID, edit("+23276999999", null, null), admin()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("another administrator");

        verify(userRepository, never()).findById(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void anEditThatChangesNothingRecordsNothing() {
        User target = stubTarget(bankStaff());
        target.setPhone("+23276111111");
        UUID sameBank = target.getBloodBank().getId();
        when(bloodBankRepository.findById(sameBank)).thenReturn(Optional.of(target.getBloodBank()));

        service.editAccount(target.getId(), edit("+23276111111", sameBank, null), admin());

        verifyNoInteractions(auditLogService);
    }

    // ---------- suspending a donor cancels their open visit (Business Rule 29) ----------

    @Test
    void suspendingADonorCancelsTheirOpenVisitInTheSameCall() {
        User target = donor();
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStatus(target.getId(), AccountStatus.SUSPENDED, admin());

        verify(donationBookingService).cancelOnSuspension(target.getId());
    }

    @Test
    void reactivatingADonorSuspendingStaffOrRepeatingASuspensionTouchesNoVisit() {
        User suspendedDonor = donor();
        suspendedDonor.setStatus(AccountStatus.SUSPENDED);
        User staff = bankStaff();
        when(userRepository.findById(suspendedDonor.getId())).thenReturn(Optional.of(suspendedDonor));
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStatus(suspendedDonor.getId(), AccountStatus.SUSPENDED, admin());
        service.updateStatus(suspendedDonor.getId(), AccountStatus.ACTIVE, admin());
        service.updateStatus(staff.getId(), AccountStatus.SUSPENDED, admin());

        verifyNoInteractions(donationBookingService);
    }

    // ---------- resetting a password (Business Rule 31) ----------

    @Test
    void anAdministratorCannotResetTheirOwnPasswordAndNothingIsRead() {
        assertThatThrownBy(() -> service.resetPassword(ADMIN_ID, admin()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Profile Settings");

        verifyNoInteractions(userRepository, auditLogService, notificationService, eventPublisher, passwordResetRequestService);
    }

    @Test
    void aResetSuspendedAccountStaysSuspendedMustChangeItsPasswordAndTheEntryNeverHoldsIt() {
        User target = bankStaff();
        target.setStatus(AccountStatus.SUSPENDED);
        when(userRepository.lockById(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.resetPassword(target.getId(), admin());

        assertThat(target.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(target.isMustChangePassword()).isTrue();
        assertThat(passwordEncoder.matches(response.temporaryPassword(), target.getPasswordHash())).isTrue();
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq(ADMIN_ID), eq(AuditAction.ADMIN_PASSWORD_RESET), eq(target), details.capture());
        assertThat(details.getValue()).contains("stays suspended").doesNotContain(response.temporaryPassword());
        verify(notificationService).notifyPasswordResetByAdmin(target);
        // Business Rule 32: the reset is stamped and announced, so the holder's older tokens and sockets stop working.
        assertThat(target.getPasswordChangedAt()).isNotNull();
        verify(eventPublisher).publishEvent(new PasswordChangedEvent(target.getId(), target.getPasswordChangedAt()));
        // Business Rule 33: any waiting "Forgot password?" request is closed by the same reset, in its transaction.
        verify(passwordResetRequestService).closeOnReset(target, ADMIN_ID, target.getPasswordChangedAt());
    }

    @Test
    void resettingAnUnknownAccountIsA404() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.lockById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(unknown, admin())).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(auditLogService, notificationService);
    }
}
