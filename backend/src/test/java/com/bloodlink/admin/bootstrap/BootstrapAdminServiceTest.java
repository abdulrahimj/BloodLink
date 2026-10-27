package com.bloodlink.admin.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.bloodlink.admin.bootstrap.BootstrapAdminService.Outcome;
import com.bloodlink.audit.AuditLogService;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.UserRepository;

/**
 * Business Rule 27's first-administrator bootstrap, decision by decision: what it creates, when it does nothing,
 * and that the password never reaches the log. The concurrent-start guarantee is BootstrapAdminRaceIT's.
 */
@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
class BootstrapAdminServiceTest {

    private static final String PASSWORD = "Temp-Pass-For-Bootstrap-77";

    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;

    /** The real encoder, so the stored hash is proven to verify against the configured password. */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private BootstrapAdminService service;

    @BeforeEach
    void setUp() {
        service = new BootstrapAdminService(userRepository, passwordEncoder, auditLogService);
    }

    private static BootstrapAdminSettings settings(String password) {
        return new BootstrapAdminSettings("076 900 001", "  Aminata Sesay ", "Aminata@Example.org", password);
    }

    private void stubNoActiveAdmin() {
        when(userRepository.existsByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)).thenReturn(false);
    }

    @Test
    void createsOneActiveAdministratorWhoMustChangeThePassword(CapturedOutput output) {
        stubNoActiveAdmin();
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });

        assertThat(service.createFirstAdminIfNeeded(settings(PASSWORD))).isEqualTo(Outcome.CREATED);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User admin = captor.getValue();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(admin.isMustChangePassword()).isTrue();
        // Normalised exactly as every other account is (Business Rules 13 and 18).
        assertThat(admin.getPhone()).isEqualTo("+23276900001");
        assertThat(admin.getEmail()).isEqualTo("aminata@example.org");
        assertThat(admin.getFullName()).isEqualTo("Aminata Sesay");
        assertThat(admin.getBloodBank()).isNull();
        assertThat(admin.getHospital()).isNull();
        assertThat(admin.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();

        // Audited as ACCOUNT_CREATED with the new administrator as both actor and subject.
        verify(auditLogService).record(eq(admin.getId()), eq(AuditAction.ACCOUNT_CREATED), eq(admin),
            org.mockito.ArgumentMatchers.contains("from the startup settings"));
        assertThat(output).contains("Created the first administrator account for Aminata Sesay");
        assertThat(output).doesNotContain(PASSWORD);
    }

    @Test
    void takesTheLockBeforeLookingForAnAdministrator() {
        when(userRepository.existsByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)).thenReturn(true);

        service.createFirstAdminIfNeeded(settings(PASSWORD));

        // The lock must come first, or two servers could both see "no administrator" before either has created one.
        InOrder order = inOrder(userRepository);
        order.verify(userRepository).waitForAdvisoryLock(BootstrapAdminService.LOCK_KEY);
        order.verify(userRepository).existsByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE);
    }

    @Test
    void doesNothingAtAllWhenAnActiveAdministratorExists(CapturedOutput output) {
        when(userRepository.existsByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)).thenReturn(true);

        assertThat(service.createFirstAdminIfNeeded(settings(PASSWORD))).isEqualTo(Outcome.ADMIN_EXISTS);

        // Not even the duplicate checks: no account is looked up, written, reset or reactivated.
        verify(userRepository, never()).existsByPhone(anyString());
        verify(userRepository, never()).save(any(User.class));
        verify(auditLogService, never()).record(any(), any(), any(), any());
        assertThat(output).contains("settings were ignored").doesNotContain(PASSWORD);
    }

    @Test
    void anExistingAdministratorAndNoSettingsIsAnOrdinaryQuietStart(CapturedOutput output) {
        when(userRepository.existsByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)).thenReturn(true);

        assertThat(service.createFirstAdminIfNeeded(new BootstrapAdminSettings(null, null, null, null)))
            .isEqualTo(Outcome.ADMIN_EXISTS);

        assertThat(output).doesNotContain("BLOODLINK_BOOTSTRAP_ADMIN");
    }

    @Test
    void withNoAdministratorAndNoSettingsItWarnsHowToCreateOne(CapturedOutput output) {
        stubNoActiveAdmin();

        assertThat(service.createFirstAdminIfNeeded(new BootstrapAdminSettings("", " ", null, null)))
            .isEqualTo(Outcome.SETTINGS_MISSING);

        verify(userRepository, never()).save(any(User.class));
        assertThat(output)
            .contains("WARN")
            .contains("No active administrator account exists")
            .contains("Creating the first administrator")
            .contains("Not set: BLOODLINK_BOOTSTRAP_ADMIN_PHONE, BLOODLINK_BOOTSTRAP_ADMIN_NAME, "
                + "BLOODLINK_BOOTSTRAP_ADMIN_EMAIL, BLOODLINK_BOOTSTRAP_ADMIN_PASSWORD");
    }

    @Test
    void aPartlySetGroupNamesOnlyWhatIsMissingAndCreatesNothing(CapturedOutput output) {
        stubNoActiveAdmin();

        assertThat(service.createFirstAdminIfNeeded(
            new BootstrapAdminSettings("076 900 001", "Aminata Sesay", null, PASSWORD)))
            .isEqualTo(Outcome.SETTINGS_MISSING);

        verify(userRepository, never()).save(any(User.class));
        assertThat(output).contains("Not set: BLOODLINK_BOOTSTRAP_ADMIN_EMAIL.").doesNotContain(PASSWORD);
    }

    @Test
    void refusesAPhoneThatAlreadyBelongsToAnotherAccount(CapturedOutput output) {
        stubNoActiveAdmin();
        when(userRepository.existsByPhone("+23276900001")).thenReturn(true);

        assertThat(service.createFirstAdminIfNeeded(settings(PASSWORD))).isEqualTo(Outcome.PHONE_IN_USE);

        verify(userRepository, never()).save(any(User.class));
        verify(auditLogService, never()).record(any(), any(), any(), any());
        assertThat(output).contains("ERROR").contains("already belongs to another account").doesNotContain(PASSWORD);
    }

    @Test
    void refusesAnEmailThatAlreadyBelongsToAnotherAccount() {
        stubNoActiveAdmin();
        when(userRepository.existsByPhone("+23276900001")).thenReturn(false);
        when(userRepository.existsByEmail("aminata@example.org")).thenReturn(true);

        assertThat(service.createFirstAdminIfNeeded(settings(PASSWORD))).isEqualTo(Outcome.EMAIL_IN_USE);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void refusesAPasswordTooWeakForTheAppsOwnRule(CapturedOutput output) {
        stubNoActiveAdmin();
        String weak = "Ab3$xyz"; // seven characters: registration would refuse it too

        assertThat(service.createFirstAdminIfNeeded(settings(weak))).isEqualTo(Outcome.INVALID_SETTING);

        verify(userRepository, never()).existsByPhone(anyString());
        verify(userRepository, never()).save(any(User.class));
        assertThat(output)
            .contains("BLOODLINK_BOOTSTRAP_ADMIN_PASSWORD was refused")
            .contains("at least 8 characters")
            .doesNotContain(weak);
    }

    @Test
    void refusesAPasswordLongerThanBcryptAccepts() {
        stubNoActiveAdmin();

        assertThat(service.createFirstAdminIfNeeded(settings("x".repeat(73)))).isEqualTo(Outcome.INVALID_SETTING);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void refusesAPhoneOrEmailThatIsNotUsable(CapturedOutput output) {
        stubNoActiveAdmin();

        assertThat(service.createFirstAdminIfNeeded(
            new BootstrapAdminSettings("12345", "Aminata Sesay", "aminata@example.org", PASSWORD)))
            .isEqualTo(Outcome.INVALID_SETTING);
        assertThat(service.createFirstAdminIfNeeded(
            new BootstrapAdminSettings("076 900 001", "Aminata Sesay", "aminata@example", PASSWORD)))
            .isEqualTo(Outcome.INVALID_SETTING);

        verify(userRepository, never()).save(any(User.class));
        assertThat(output)
            .contains("BLOODLINK_BOOTSTRAP_ADMIN_PHONE was refused")
            .contains("BLOODLINK_BOOTSTRAP_ADMIN_EMAIL was refused");
    }

    @Test
    void theSettingsNeverPrintTheirPassword() {
        assertThat(settings(PASSWORD).toString()).doesNotContain(PASSWORD).contains("(set, hidden)");
    }
}
