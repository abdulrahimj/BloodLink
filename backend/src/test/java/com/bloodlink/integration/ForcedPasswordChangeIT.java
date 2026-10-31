package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.admin.dto.CreateStaffAccountPayload;
import com.bloodlink.admin.dto.CreatedAccountResponse;
import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.auth.dto.RegisterRequest;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.StompAuthChannelInterceptor;
import com.bloodlink.security.StompAuthenticationException;
import com.bloodlink.security.StompPrincipal;

import tools.jackson.databind.ObjectMapper;

/**
 * Business Rule 27's forced password change end to end, through the real filter chain: who is flagged, what a flagged
 * account can still reach, and that changing the password lifts it for the same token. Rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ForcedPasswordChangeIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";
    private static final String OWN_PASSWORD = "My-own-chosen-passphrase-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private StompAuthChannelInterceptor stompAuthChannelInterceptor;

    // ---------- who is flagged ----------

    @Test
    void everyAccountAnAdministratorCreatesIsFlaggedAndTheLoginResponseSaysSo() throws Exception {
        String adminToken = seededAdminToken();
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        BloodBank bank = bloodBankRepository.findAllByOrderByNameAsc().get(0);

        for (CreateStaffAccountPayload payload : new CreateStaffAccountPayload[] {
            payload(Role.HOSPITAL_STAFF, null, hospital.getId()),
            payload(Role.BLOOD_BANK_STAFF, bank.getId(), null),
            payload(Role.ADMIN, null, null),
        }) {
            CreatedAccountResponse created = createAccount(adminToken, payload);

            assertThat(userRepository.findById(created.account().id()).orElseThrow().isMustChangePassword())
                .as(payload.role().name()).isTrue();
            login(payload.phone(), created.temporaryPassword())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true));
        }
    }

    @Test
    void aSelfRegisteredDonorChoseTheirOwnPasswordAndIsNotFlagged() throws Exception {
        String phone = uniqueLocalPhone();
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(
                    "Aminata Sesay", phone, null, LocalDate.of(1998, 3, 12), Gender.FEMALE, "O+", "Wusum, Makeni", PASSWORD
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user.mustChangePassword").value(false))
            .andReturn();
        String token = readBody(result, AuthResponse.class).token();

        assertThat(userRepository.findByPhone(PhoneNumber.normalize(phone)).orElseThrow().isMustChangePassword()).isFalse();
        login(phone, PASSWORD).andExpect(jsonPath("$.user.mustChangePassword").value(false));
        mockMvc.perform(get("/api/donors/me").header("Authorization", bearer(token))).andExpect(status().isOk());
    }

    // ---------- what a flagged account can and cannot reach ----------

    @Test
    void aFlaggedAccountIsRefusedEverythingButItsOwnAccountWithADistinctCode() throws Exception {
        Flagged staff = createdBankStaff();

        for (String path : new String[] {
            "/api/notifications", "/api/blood-banks", "/api/hospitals",
            "/api/inventory/" + staff.bankId(), "/api/requests", "/api/donors/roster",
            // A route the role could never reach is refused for the password first, so the screen knows what to do.
            "/api/admin/users",
        }) {
            mockMvc.perform(get(path).header("Authorization", bearer(staff.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("set your own password")));
        }
        // Its own details can be read but not edited until the password is its own.
        mockMvc.perform(patch("/api/users/me").header("Authorization", bearer(staff.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\": \"Renamed\", \"email\": \"renamed@example.com\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(staff.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("BLOOD_BANK_STAFF"))
            .andExpect(jsonPath("$.mustChangePassword").value(true));
        // Signing out is client-side; signing in again is a public endpoint that is never blocked.
        login(staff.phone(), staff.temporaryPassword()).andExpect(status().isOk());
    }

    @Test
    void aFlaggedAccountCannotOpenTheNotificationSocketUntilThePasswordIsChanged() throws Exception {
        Flagged staff = createdBankStaff();

        assertThatThrownBy(() -> stompAuthChannelInterceptor.preSend(connectFrame(staff.token()), null))
            .isInstanceOf(StompAuthenticationException.class)
            .hasMessageContaining("set your own password");

        changePassword(staff.token(), staff.temporaryPassword(), OWN_PASSWORD).andExpect(status().isNoContent());

        Message<byte[]> connect = connectFrame(staff.token());
        stompAuthChannelInterceptor.preSend(connect, null);
        assertThat(((StompPrincipal) StompHeaderAccessor.wrap(connect).getUser()).userId()).isEqualTo(staff.id());
    }

    // ---------- the change itself ----------

    @Test
    void aWrongTemporaryPasswordOrTheSamePasswordIsRefusedAndTheFlagStays() throws Exception {
        Flagged staff = createdBankStaff();

        changePassword(staff.token(), "not-the-temporary-one", OWN_PASSWORD)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.fieldErrors.currentPassword").exists());
        // The generated temporary password meets the rule, so keeping it is refused for being unchanged.
        changePassword(staff.token(), staff.temporaryPassword(), staff.temporaryPassword())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.newPassword").value(org.hamcrest.Matchers.containsString("different")));
        changePassword(staff.token(), staff.temporaryPassword(), "short")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        changePassword(staff.token(), staff.temporaryPassword(), "my-own-chosen-passphrase")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.newPassword").value("Add a capital letter and a number."));

        assertThat(userRepository.findById(staff.id()).orElseThrow().isMustChangePassword()).isTrue();
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(staff.token())))
            .andExpect(status().isForbidden());
    }

    @Test
    void afterTheChangeTheSameTokenWorksNormallyAndTheNextLoginIsUnflagged() throws Exception {
        Flagged staff = createdBankStaff();

        changePassword(staff.token(), staff.temporaryPassword(), OWN_PASSWORD).andExpect(status().isNoContent());

        assertThat(userRepository.findById(staff.id()).orElseThrow().isMustChangePassword()).isFalse();
        // Read from the database on every request, so the token issued before the change needs no replacing.
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(staff.token())))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/inventory/" + staff.bankId()).header("Authorization", bearer(staff.token())))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(staff.token())))
            .andExpect(jsonPath("$.mustChangePassword").value(false));

        login(staff.phone(), staff.temporaryPassword()).andExpect(status().isUnauthorized());
        login(staff.phone(), OWN_PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.mustChangePassword").value(false));
    }

    @Test
    void anAccountThatWasNeverFlaggedIsUnaffected() throws Exception {
        String adminToken = seededAdminToken();

        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    /** A blood bank staff account an administrator has just created, signed in with its temporary password. */
    private record Flagged(UUID id, String phone, String temporaryPassword, String token, UUID bankId) {}

    private Flagged createdBankStaff() throws Exception {
        BloodBank bank = bloodBankRepository.findAllByOrderByNameAsc().get(0);
        CreateStaffAccountPayload payload = payload(Role.BLOOD_BANK_STAFF, bank.getId(), null);
        CreatedAccountResponse created = createAccount(seededAdminToken(), payload);
        MvcResult result = login(payload.phone(), created.temporaryPassword()).andExpect(status().isOk()).andReturn();
        String token = readBody(result, AuthResponse.class).token();
        return new Flagged(created.account().id(), payload.phone(), created.temporaryPassword(), token, bank.getId());
    }

    private CreateStaffAccountPayload payload(Role role, UUID bloodBankId, UUID hospitalId) {
        return new CreateStaffAccountPayload("New " + role.name(), uniqueLocalPhone(), uniqueEmail(), role, bloodBankId, hospitalId);
    }

    private CreatedAccountResponse createAccount(String adminToken, CreateStaffAccountPayload payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/users")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
            .andExpect(status().isCreated())
            .andReturn();
        return readBody(result, CreatedAccountResponse.class);
    }

    /** An administrator seeded directly with a password of its own, so it is never flagged. */
    private String seededAdminToken() throws Exception {
        User admin = new User();
        admin.setFullName("System Administrator");
        admin.setEmail(uniqueEmail());
        admin.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
        MvcResult result = login(admin.getPhone(), PASSWORD).andExpect(status().isOk()).andReturn();
        return readBody(result, AuthResponse.class).token();
    }

    private ResultActions login(String phone, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new LoginRequest(phone, password))));
    }

    private ResultActions changePassword(String token, String current, String next) throws Exception {
        return mockMvc.perform(patch("/api/users/me/password")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\": \"" + current + "\", \"newPassword\": \"" + next + "\"}"));
    }

    /** A STOMP CONNECT frame carrying the token, as the browser's notification socket sends it. */
    private static Message<byte[]> connectFrame(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + token);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private <T> T readBody(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String uniqueEmail() {
        return "forced.change.it." + UUID.randomUUID() + "@example.com";
    }

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. */
    private static String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "077" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
