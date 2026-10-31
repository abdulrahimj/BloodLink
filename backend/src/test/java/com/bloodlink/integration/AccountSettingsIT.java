package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import com.bloodlink.security.JwtService;

// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.ObjectMapper;

/**
 * Business Rule 16 end to end: password changes really take effect at login, refused ones change nothing,
 * "phone" is refused not dropped, and the 401 names its field. Rolled back; run -Dtest=AccountSettingsIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountSettingsIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";
    private static final String NEW_PASSWORD = "A-brand-new-passphrase-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private JwtService jwtService;

    // ---------- every role can read its own account ----------

    @Test
    void everyRoleCanReadItsOwnAccountIncludingTheFactsItCannotChange() throws Exception {
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        BloodBank bank = bloodBankRepository.findAllByOrderByNameAsc().get(0);

        User hospitalStaff = seed("Fatmata Conteh", Role.HOSPITAL_STAFF);
        hospitalStaff.setHospital(hospital);
        User bankStaff = seed("Mohamed Kamara", Role.BLOOD_BANK_STAFF);
        bankStaff.setBloodBank(bank);
        User admin = seed("Isatu Bangura", Role.ADMIN);

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(signIn(hospitalStaff))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("HOSPITAL_STAFF"))
            .andExpect(jsonPath("$.hospitalName").value(hospital.getName()))
            .andExpect(jsonPath("$.phone").value(hospitalStaff.getPhone()))
            .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(signIn(bankStaff))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("BLOOD_BANK_STAFF"))
            .andExpect(jsonPath("$.bloodBankName").value(bank.getName()));

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(signIn(admin))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.bloodBankId").doesNotExist())
            .andExpect(jsonPath("$.hospitalId").doesNotExist());

        // A donor too: the same settings apply to every role.
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(registerDonor())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("DONOR"));
    }

    // ---------- the password change ----------

    @Test
    void theCorrectCurrentPasswordChangesThePasswordForReal() throws Exception {
        User staff = seed("Lab Technician", Role.BLOOD_BANK_STAFF);
        String token = signIn(staff);

        changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());

        // The proof that matters: the old password no longer signs in, the new one does.
        login(staff.getPhone(), PASSWORD).andExpect(status().isUnauthorized());
        login(staff.getPhone(), NEW_PASSWORD).andExpect(status().isOk());
    }

    // ---------- Business Rule 32: older sign-ins end, this device stays signed in ----------

    @Test
    void aTokenFromBeforeASelfChangeIsRefusedAndTheFreshOneWorks() throws Exception {
        User staff = seed("Lab Technician", Role.BLOOD_BANK_STAFF);
        // A few seconds old, as another device's sign-in would be (a token from the change's own second still counts).
        String otherDevice = jwtService.issueToken(staff, Instant.now().minusSeconds(5));
        String thisDevice = jwtService.issueToken(staff, Instant.now().minusSeconds(5));

        MvcResult result = changePassword(thisDevice, PASSWORD, NEW_PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.id").value(staff.getId().toString()))
            .andExpect(jsonPath("$.user.mustChangePassword").value(false))
            .andReturn();
        String fresh = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).token();

        for (String old : new String[] { otherDevice, thisDevice }) {
            mockMvc.perform(get("/api/users/me").header("Authorization", bearer(old)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGED"))
                .andExpect(jsonPath("$.message").value("Your password was changed. Please sign in again."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
        }
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(fresh))).andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(fresh))).andExpect(status().isOk());
    }

    @Test
    void aTokenIssuedInTheSameSecondAsTheChangeWorksAndOneFromTheSecondBeforeDoesNot() throws Exception {
        User staff = seed("Hospital Desk Clerk", Role.HOSPITAL_STAFF);
        Instant second = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        staff.setPasswordChangedAt(second.plusMillis(700));
        userRepository.saveAndFlush(staff);

        // Issued-at is stored in whole seconds: 12:00:00 is the change's own second (12:00:00.700), so not older.
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(jwtService.issueToken(staff, second))))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me")
                .header("Authorization", bearer(jwtService.issueToken(staff, second.minusSeconds(1)))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("PASSWORD_CHANGED"));
    }

    @Test
    void anAccountWithNoPasswordChangeRecordedKeepsItsOlderTokens() throws Exception {
        User staff = seed("Lab Technician", Role.BLOOD_BANK_STAFF);
        assertThat(staff.getPasswordChangedAt()).isNull();

        mockMvc.perform(get("/api/users/me")
                .header("Authorization", bearer(jwtService.issueToken(staff, Instant.now().minus(23, ChronoUnit.HOURS)))))
            .andExpect(status().isOk());
    }

    @Test
    void aSignInAfterTheChangeWorks() throws Exception {
        User staff = seed("Lab Technician", Role.BLOOD_BANK_STAFF);
        changePassword(jwtService.issueToken(staff, Instant.now().minusSeconds(5)), PASSWORD, NEW_PASSWORD)
            .andExpect(status().isOk());

        MvcResult result = login(staff.getPhone(), NEW_PASSWORD).andExpect(status().isOk()).andReturn();
        String token = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).token();
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(token))).andExpect(status().isOk());
    }

    @Test
    void anIncorrectCurrentPasswordIsRefusedWith401AndThePasswordIsNotChanged() throws Exception {
        User staff = seed("Hospital Desk Clerk", Role.HOSPITAL_STAFF);
        String token = signIn(staff);
        String hashBefore = userRepository.findById(staff.getId()).orElseThrow().getPasswordHash();

        changePassword(token, "not-my-password", NEW_PASSWORD)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Your current password is incorrect."))
            // The field is what keeps the frontend from treating this as an expired
            // session and signing the user out for a typo.
            .andExpect(jsonPath("$.fieldErrors.currentPassword").exists());

        assertThat(userRepository.findById(staff.getId()).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
        login(staff.getPhone(), PASSWORD).andExpect(status().isOk());
        login(staff.getPhone(), NEW_PASSWORD).andExpect(status().isUnauthorized());

        // And the session survived: the same token still works.
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(token)))
            .andExpect(status().isOk());
    }

    @Test
    void theDonorPasswordChangeNeedsTheCurrentPasswordToo() throws Exception {
        // The same rule for every role, the Donor included — no role has a
        // password change that skips the proof.
        String token = registerDonor();

        changePassword(token, "not-my-password", NEW_PASSWORD).andExpect(status().isUnauthorized());
        changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void aWeakNewPasswordIsRefusedByTheSameRuleAsRegistrationNamingWhatIsMissing() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        String token = signIn(admin);

        changePassword(token, PASSWORD, "short")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.newPassword")
                .value("Use at least 8 characters, and add a capital letter, a number and a special character."));
        changePassword(token, PASSWORD, "Password 123")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.newPassword").value("Add a special character."));

        login(admin.getPhone(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void registrationRefusesAWeakPasswordNamingWhatIsMissingAndCreatesNoAccount() throws Exception {
        String phone = uniqueLocalPhone();

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(
                    "Aminata Sesay", phone, null, LocalDate.of(1998, 3, 12), Gender.FEMALE, "O+", "Wusum, Makeni",
                    "password123"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.password").value("Add a capital letter and a special character."));

        assertThat(userRepository.findByPhone(PhoneNumber.normalize(phone))).isEmpty();
    }

    // ---------- editing name and email ----------

    @Test
    void anAdministratorEditsTheirOwnNameAndEmail() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        String token = signIn(admin);
        String newEmail = "isatu.k." + UUID.randomUUID() + "@Example.com";

        mockMvc.perform(patch("/api/users/me")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\": \"Isatu K. Bangura\", \"email\": \"" + newEmail + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fullName").value("Isatu K. Bangura"))
            .andExpect(jsonPath("$.email").value(newEmail.toLowerCase()));

        User stored = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(stored.getFullName()).isEqualTo("Isatu K. Bangura");
        assertThat(stored.getEmail()).isEqualTo(newEmail.toLowerCase());
        // Nothing else moved — and the admin can still sign in exactly as before.
        assertThat(stored.getPhone()).isEqualTo(admin.getPhone());
        assertThat(stored.getRole()).isEqualTo(Role.ADMIN);
        login(admin.getPhone(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void anEmailBelongingToSomeoneElseIsRefused() throws Exception {
        User first = seed("First Person", Role.ADMIN);
        User second = seed("Second Person", Role.ADMIN);

        mockMvc.perform(patch("/api/users/me")
                .header("Authorization", bearer(signIn(second)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\": \"Second Person\", \"email\": \"" + first.getEmail() + "\"}"))
            .andExpect(status().isConflict());
    }

    // ---------- the phone cannot be changed through either request ----------

    @Test
    void aPhoneInADetailsRequestIsRefusedAndNothingIsChanged() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);

        mockMvc.perform(patch("/api/users/me")
                .header("Authorization", bearer(signIn(admin)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\": \"Changed Name\", \"email\": \"changed." + UUID.randomUUID()
                    + "@example.com\", \"phone\": \"+23276111222\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.phone").exists());

        // Refused whole — neither the phone nor the name that came with it.
        User stored = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(stored.getPhone()).isEqualTo(admin.getPhone());
        assertThat(stored.getFullName()).isEqualTo("Isatu Bangura");
    }

    @Test
    void aPhoneInAPasswordRequestIsRefusedAndThePasswordIsNotChanged() throws Exception {
        User staff = seed("Lab Technician", Role.BLOOD_BANK_STAFF);

        mockMvc.perform(patch("/api/users/me/password")
                .header("Authorization", bearer(signIn(staff)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"" + NEW_PASSWORD
                    + "\", \"phone\": \"+23276111222\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.phone").exists());

        assertThat(userRepository.findById(staff.getId()).orElseThrow().getPhone()).isEqualTo(staff.getPhone());
        login(staff.getPhone(), PASSWORD).andExpect(status().isOk());
        login(staff.getPhone(), NEW_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void roleAndFacilityCannotBeChangedEither() throws Exception {
        User staff = seed("Hospital Desk Clerk", Role.HOSPITAL_STAFF);
        String token = signIn(staff);
        String body = "{\"fullName\": \"Hospital Desk Clerk\", \"email\": \"" + staff.getEmail() + "\", ";

        mockMvc.perform(patch("/api/users/me").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body + "\"role\": \"ADMIN\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.role").exists());

        mockMvc.perform(patch("/api/users/me").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body + "\"hospitalId\": \"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.hospitalId").exists());

        assertThat(userRepository.findById(staff.getId()).orElseThrow().getRole()).isEqualTo(Role.HOSPITAL_STAFF);
    }

    // ---------- who may call it ----------

    @Test
    void anUnauthenticatedCallerIsRefused() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized())
            // A session-level 401 names no field: the frontend's cue to sign out.
            .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    // ---------- helpers ----------

    private ResultActions changePassword(String token, String current, String next) throws Exception {
        return mockMvc.perform(patch("/api/users/me/password")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\": \"" + current + "\", \"newPassword\": \"" + next + "\"}"));
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

    /** A donor, created the only way donors are: public registration. */
    private String registerDonor() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(
                    "Aminata Sesay", uniqueLocalPhone(), uniqueEmail(),
                    LocalDate.of(1998, 3, 12), Gender.FEMALE, "O+", "Wusum, Makeni", PASSWORD
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).token();
    }

    /** A saved staff or admin account with PASSWORD, its phone stored normalised. */
    private User seed(String fullName, Role role) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail(uniqueEmail());
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        return userRepository.save(user);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String uniqueEmail() {
        return "account.it." + UUID.randomUUID() + "@example.com";
    }

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "076" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
