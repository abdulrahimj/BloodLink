package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.admin.dto.PasswordResetResponse;
import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.common.PasswordPolicy;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.AuditLogRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;

import jakarta.persistence.EntityManager;
// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.ObjectMapper;

/**
 * Business Rule 31 end to end through the real filter chain: an administrator resets another account's password.
 * Rolled back like every bloodlink_test consumer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminPasswordResetIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void theOldPasswordStopsWorkingAndTheTemporaryOneSignsInFlaggedToChangeIt() throws Exception {
        User staff = seed("Fatmata Koroma", Role.HOSPITAL_STAFF);

        MvcResult result = reset(signIn(seed("Isatu Bangura", Role.ADMIN)), staff.getId())
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.account.id").value(staff.getId().toString()))
            .andExpect(jsonPath("$.account.status").value("ACTIVE"))
            .andReturn();
        String temporary = objectMapper.readValue(result.getResponse().getContentAsString(), PasswordResetResponse.class)
            .temporaryPassword();

        assertThat(PasswordPolicy.problemWith(temporary)).isNull();
        login(staff.getPhone(), PASSWORD).andExpect(status().isUnauthorized());
        login(staff.getPhone(), temporary)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.mustChangePassword").value(true));

        User stored = reload(staff);
        assertThat(stored.isMustChangePassword()).isTrue();
        assertThat(stored.getPasswordHash()).doesNotContain(temporary);
    }

    @Test
    void theResetIsAuditedAndTheHolderNotifiedWithoutThePasswordAnywhere() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        User staff = seed("Mohamed Kamara", Role.BLOOD_BANK_STAFF);
        String adminToken = signIn(admin);

        String temporary = temporaryPasswordFrom(reset(adminToken, staff.getId()));
        entityManager.flush();

        List<AuditLog> entries = auditLogRepository.findByTargetIdOldestFirst(staff.getId());
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getAction()).isEqualTo(AuditAction.ADMIN_PASSWORD_RESET);
            assertThat(entry.getActor().getId()).isEqualTo(admin.getId());
            assertThat(entry.getDetails()).contains("temporary").doesNotContain(temporary);
        });
        String auditPage = mockMvc.perform(get("/api/admin/audit-log").header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(auditPage).contains("ADMIN_PASSWORD_RESET").doesNotContain(temporary);

        assertThat(notifications(staff)).singleElement().satisfies(n -> {
            assertThat(n.getMessage()).isEqualTo("Your password was reset by an administrator. If you did not ask for "
                + "this, contact your blood bank or BloodLink administrator.");
            assertThat(n.getMessage()).doesNotContain(temporary);
        });
    }

    @Test
    void anAdministratorCannotResetTheirOwnPassword() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        String hashBefore = admin.getPasswordHash();

        reset(signIn(admin), admin.getId())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Profile Settings")));

        User stored = reload(admin);
        assertThat(stored.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(stored.isMustChangePassword()).isFalse();
        login(admin.getPhone(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void anAdministratorMayResetAnotherAdministrator() throws Exception {
        User other = seed("Abu Conteh", Role.ADMIN);

        String temporary = temporaryPasswordFrom(reset(signIn(seed("Isatu Bangura", Role.ADMIN)), other.getId()));

        login(other.getPhone(), temporary).andExpect(jsonPath("$.user.mustChangePassword").value(true));
        login(other.getPhone(), PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void aSuspendedAccountIsResetButStaysSuspendedAndIsNotNotified() throws Exception {
        User donor = seed("Aminata Sesay", Role.DONOR);
        donor.setStatus(AccountStatus.SUSPENDED);
        userRepository.save(donor);

        String temporary = temporaryPasswordFrom(reset(signIn(seed("Isatu Bangura", Role.ADMIN)), donor.getId())
            .andExpect(jsonPath("$.account.status").value("SUSPENDED")));

        User stored = reload(donor);
        assertThat(stored.getStatus()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(passwordEncoder.matches(temporary, stored.getPasswordHash())).isTrue();
        assertThat(notifications(donor)).isEmpty();
        assertThat(auditLogRepository.findByTargetIdOldestFirst(donor.getId()))
            .singleElement().satisfies(entry -> assertThat(entry.getDetails()).contains("stays suspended"));
        login(donor.getPhone(), temporary).andExpect(status().is4xxClientError());
    }

    @Test
    void onlyAnAdministratorMayResetAPassword() throws Exception {
        User target = seed("Fatmata Koroma", Role.HOSPITAL_STAFF);
        String hashBefore = target.getPasswordHash();

        for (Role role : List.of(Role.DONOR, Role.HOSPITAL_STAFF, Role.BLOOD_BANK_STAFF)) {
            reset(signIn(seed("Not An Admin", role)), target.getId()).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/admin/users/{id}/password-reset", target.getId()))
            .andExpect(status().isUnauthorized());

        assertThat(reload(target).getPasswordHash()).isEqualTo(hashBefore);
        assertThat(notifications(target)).isEmpty();
    }

    @Test
    void anUnknownAccountIsA404() throws Exception {
        reset(signIn(seed("Isatu Bangura", Role.ADMIN)), UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void aTokenIssuedBeforeTheResetCanOnlyReachTheSetYourOwnPasswordRoutes() throws Exception {
        User staff = seed("Fatmata Koroma", Role.HOSPITAL_STAFF);
        String oldToken = signIn(staff);

        reset(signIn(seed("Isatu Bangura", Role.ADMIN)), staff.getId()).andExpect(status().isOk());

        // The flag is read from the database on every request, so the old session is held at the gate at once.
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(oldToken)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mockMvc.perform(patch("/api/users/me/password").header("Authorization", bearer(oldToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"Taken-over-pass-1\"}"))
            .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private ResultActions reset(String token, UUID userId) throws Exception {
        return mockMvc.perform(post("/api/admin/users/{id}/password-reset", userId).header("Authorization", bearer(token)));
    }

    private String temporaryPasswordFrom(ResultActions reset) throws Exception {
        MvcResult result = reset.andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), PasswordResetResponse.class)
            .temporaryPassword();
    }

    private ResultActions login(String phone, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new LoginRequest(phone, password))));
    }

    private String signIn(User user) throws Exception {
        MvcResult result = login(user.getPhone(), PASSWORD).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).token();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private User reload(User user) {
        entityManager.flush();
        entityManager.clear();
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private List<Notification> notifications(User user) {
        entityManager.flush();
        return entityManager.createQuery(
                "select n from Notification n where n.recipient.id = :id and n.type = :type", Notification.class)
            .setParameter("id", user.getId())
            .setParameter("type", NotificationType.PASSWORD_RESET_BY_ADMIN)
            .getResultList();
    }

    /** An ACTIVE account with PASSWORD; staff get the first hospital or blood bank, as their role requires. */
    private User seed(String fullName, Role role) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail("reset.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(AccountStatus.ACTIVE);
        if (role == Role.HOSPITAL_STAFF) user.setHospital(hospitalRepository.findAllByOrderByNameAsc().get(0));
        if (role == Role.BLOOD_BANK_STAFF) user.setBloodBank(bloodBankRepository.findAllByOrderByNameAsc().get(0));
        return userRepository.save(user);
    }

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "076" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
