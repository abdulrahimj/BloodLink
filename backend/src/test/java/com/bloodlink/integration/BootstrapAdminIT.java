package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.admin.bootstrap.BootstrapAdminRunner;
import com.bloodlink.admin.bootstrap.BootstrapAdminService;
import com.bloodlink.admin.bootstrap.BootstrapAdminService.Outcome;
import com.bloodlink.admin.bootstrap.BootstrapAdminSettings;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.AuditLogRepository;
import com.bloodlink.repository.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Business Rule 27's bootstrap against the real database: the account, its audit entry (which V14's one-target CHECK
 * accepts), the advisory lock query, and the first sign-in. Rolled back; the startup runner is off under "test".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BootstrapAdminIT {

    private static final String PASSWORD = "first-admin-temporary-2026";

    @Autowired private BootstrapAdminService bootstrapAdminService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private org.springframework.context.ApplicationContext context;

    private String phone;
    private String email;

    @BeforeEach
    void uniqueSettings() {
        long n = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000L);
        phone = "078" + String.format("%06d", n);
        email = "bootstrap.it." + UUID.randomUUID() + "@example.com";
    }

    private BootstrapAdminSettings settings() {
        return new BootstrapAdminSettings(phone, "Aminata Sesay", email, PASSWORD);
    }

    /** Suspends any active administrator inside this test's transaction, so "none exists" holds; rolled back after. */
    private void noActiveAdministrator() {
        userRepository.findByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)
            .forEach(admin -> admin.setStatus(AccountStatus.SUSPENDED));
        userRepository.flush();
    }

    @Test
    void createsExactlyOneAdministratorAndAuditsItWithTheNewAccountAsActorAndSubject() throws Exception {
        noActiveAdministrator();

        assertThat(bootstrapAdminService.createFirstAdminIfNeeded(settings())).isEqualTo(Outcome.CREATED);

        List<User> active = userRepository.findByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE);
        assertThat(active).singleElement().satisfies(admin -> {
            assertThat(admin.getPhone()).isEqualTo(PhoneNumber.normalize(phone));
            assertThat(admin.isMustChangePassword()).isTrue();
            assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
        });
        User admin = active.get(0);

        // Flushed and read back, so V14's audit_log_target_matches_action CHECK has really judged the row.
        userRepository.flush();
        AuditLog entry = auditLogRepository.findAllNewestFirst(PageRequest.of(0, 1)).getContent().get(0);
        assertThat(entry.getAction()).isEqualTo(AuditAction.ACCOUNT_CREATED);
        assertThat(entry.getActor().getId()).isEqualTo(admin.getId());
        assertThat(entry.getTarget().getId()).isEqualTo(admin.getId());
        assertThat(entry.getDetails()).contains("from the startup settings").doesNotContain(PASSWORD);

        // The first sign-in works and tells the frontend the password must be changed.
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(phone, PASSWORD))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.role").value("ADMIN"))
            .andExpect(jsonPath("$.user.mustChangePassword").value(true));

        // A second run (a restart with the settings still set) finds that administrator and does nothing.
        assertThat(bootstrapAdminService.createFirstAdminIfNeeded(
            new BootstrapAdminSettings("076555111", "Someone Else", "else@example.com", PASSWORD)))
            .isEqualTo(Outcome.ADMIN_EXISTS);
        assertThat(userRepository.findByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)).hasSize(1);
    }

    @Test
    void doesNothingWhenAnActiveAdministratorExists() {
        User existing = seededAdmin();
        long usersBefore = userRepository.count();
        String hashBefore = existing.getPasswordHash();

        // The settings even name the existing administrator's own phone: it is still not touched.
        BootstrapAdminSettings sameNumber =
            new BootstrapAdminSettings(existing.getPhone(), "Renamed", email, "a-different-password-1");
        assertThat(bootstrapAdminService.createFirstAdminIfNeeded(sameNumber)).isEqualTo(Outcome.ADMIN_EXISTS);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        User after = userRepository.findById(existing.getId()).orElseThrow();
        assertThat(after.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(after.getFullName()).isEqualTo("Existing Administrator");
        assertThat(after.isMustChangePassword()).isFalse();
    }

    @Test
    void doesNothingWhenTheSettingsAreMissing() {
        noActiveAdministrator();
        long usersBefore = userRepository.count();

        assertThat(bootstrapAdminService.createFirstAdminIfNeeded(new BootstrapAdminSettings(null, null, null, null)))
            .isEqualTo(Outcome.SETTINGS_MISSING);
        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void refusesAPhoneAlreadyInUseAndNeverReactivatesThatAccount() {
        // A suspended administrator holds the number: no active admin exists, but the account is never revived.
        User suspended = seededAdmin();
        noActiveAdministrator();
        long usersBefore = userRepository.count();

        BootstrapAdminSettings taken = new BootstrapAdminSettings(suspended.getPhone(), "Aminata Sesay", email, PASSWORD);
        assertThat(bootstrapAdminService.createFirstAdminIfNeeded(taken)).isEqualTo(Outcome.PHONE_IN_USE);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        User after = userRepository.findById(suspended.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(passwordEncoder.matches(PASSWORD, after.getPasswordHash())).isFalse();
    }

    @Test
    void refusesAWeakPassword() {
        noActiveAdministrator();
        long usersBefore = userRepository.count();

        assertThat(bootstrapAdminService.createFirstAdminIfNeeded(
            new BootstrapAdminSettings(phone, "Aminata Sesay", email, "short")))
            .isEqualTo(Outcome.INVALID_SETTING);
        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    @Test
    void theStartupRunnerIsSwitchedOffUnderTheTestProfile() {
        // Otherwise it would write outside the test transaction whenever the settings were set in the terminal.
        assertThat(context.getBeansOfType(BootstrapAdminRunner.class)).isEmpty();
    }

    private User seededAdmin() {
        User admin = new User();
        admin.setFullName("Existing Administrator");
        admin.setEmail("existing.admin." + UUID.randomUUID() + "@example.com");
        admin.setPhone(PhoneNumber.normalize(phone.replaceFirst("^078", "079")));
        admin.setPasswordHash(passwordEncoder.encode("the-existing-admins-password"));
        admin.setRole(Role.ADMIN);
        return userRepository.saveAndFlush(admin);
    }
}
