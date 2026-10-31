package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.MvcResult;
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

// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.ObjectMapper;

/**
 * Business Rule 14 end to end: the generated password really signs the new account in, and only an ADMIN
 * may create accounts (SecurityConfig). @Transactional on bloodlink_test; run -Dtest=AdminAccountCreationIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminAccountCreationIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodBankRepository bloodBankRepository;

    // ---------- the three roles this endpoint may create ----------

    @Test
    void anAdministratorCreatesAHospitalStaffAccountThatCanImmediatelySignIn() throws Exception {
        String adminToken = seededAdminToken();
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);

        String phone = uniqueLocalPhone();
        CreatedAccountResponse created = createAccount(adminToken, new CreateStaffAccountPayload(
            "Fatmata Conteh", phone, uniqueEmail(), Role.HOSPITAL_STAFF, null, hospital.getId()
        ));

        assertThat(created.account().role()).isEqualTo(Role.HOSPITAL_STAFF);
        assertThat(created.account().hospitalId()).isEqualTo(hospital.getId());
        assertThat(created.account().hospitalName()).isEqualTo(hospital.getName());
        assertThat(created.account().bloodBankId()).isNull();
        // Stored in the one format (Business Rule 13), not as the admin typed it.
        assertThat(created.account().phone()).isEqualTo(PhoneNumber.normalize(phone));

        // The test this class exists for: the password handed to the administrator
        // signs the new account in, over the real login endpoint.
        signIn(phone, created.temporaryPassword());
    }

    @Test
    void anAdministratorCreatesABloodBankStaffAccountThatCanImmediatelySignIn() throws Exception {
        String adminToken = seededAdminToken();
        BloodBank bank = bloodBankRepository.findAllByOrderByNameAsc().get(0);

        String phone = uniqueLocalPhone();
        CreatedAccountResponse created = createAccount(adminToken, new CreateStaffAccountPayload(
            "Mohamed Kamara", phone, uniqueEmail(), Role.BLOOD_BANK_STAFF, bank.getId(), null
        ));

        assertThat(created.account().role()).isEqualTo(Role.BLOOD_BANK_STAFF);
        assertThat(created.account().bloodBankId()).isEqualTo(bank.getId());
        assertThat(created.account().bloodBankName()).isEqualTo(bank.getName());
        assertThat(created.account().hospitalId()).isNull();

        signIn(phone, created.temporaryPassword());
    }

    @Test
    void anAdministratorCreatesAnotherAdministratorWithNoFacility() throws Exception {
        String adminToken = seededAdminToken();

        String phone = uniqueLocalPhone();
        CreatedAccountResponse created = createAccount(adminToken, new CreateStaffAccountPayload(
            "Abu Bakarr Sesay", phone, uniqueEmail(), Role.ADMIN, null, null
        ));

        assertThat(created.account().role()).isEqualTo(Role.ADMIN);
        assertThat(created.account().bloodBankId()).isNull();
        assertThat(created.account().hospitalId()).isNull();

        // The new administrator can sign in, and their token really does carry ADMIN: proven by reaching an
        // administrator-only endpoint, once the temporary password is replaced (Business Rule 27).
        String newAdminToken = signIn(phone, created.temporaryPassword());
        String payload = objectMapper.writeValueAsString(new CreateStaffAccountPayload(
            "Created By The New Admin", uniqueLocalPhone(), uniqueEmail(), Role.ADMIN, null, null
        ));
        mockMvc.perform(post("/api/admin/users")
                .header("Authorization", "Bearer " + newAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        MvcResult changed = mockMvc.perform(patch("/api/users/me/password")
                .header("Authorization", "Bearer " + newAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\": \"" + created.temporaryPassword()
                    + "\", \"newPassword\": \"The-new-admins-own-passphrase-7\"}"))
            .andExpect(status().isOk())
            .andReturn();
        // The change answers with a fresh token; older ones stop working (Business Rule 32).
        String ownPasswordToken = objectMapper.readValue(changed.getResponse().getContentAsString(), AuthResponse.class)
            .token();
        mockMvc.perform(post("/api/admin/users")
                .header("Authorization", "Bearer " + ownPasswordToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated());
    }

    @Test
    void theStoredHashIsARealBcryptHashOfTheReturnedPassword() throws Exception {
        String adminToken = seededAdminToken();

        String phone = uniqueLocalPhone();
        CreatedAccountResponse created = createAccount(adminToken, new CreateStaffAccountPayload(
            "Isatu Bangura", phone, uniqueEmail(), Role.ADMIN, null, null
        ));

        // Read back what was actually written to the database — not what the service
        // said it wrote — and check it with the application's own encoder.
        User stored = userRepository.findByPhone(PhoneNumber.normalize(phone)).orElseThrow();

        assertThat(stored.getPasswordHash())
            .isNotEqualTo(created.temporaryPassword())
            .startsWith("$2");
        assertThat(passwordEncoder.matches(created.temporaryPassword(), stored.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("not-the-password", stored.getPasswordHash())).isFalse();
    }

    // ---------- what this endpoint refuses ----------

    @Test
    void aDonorAccountCannotBeCreatedThroughTheAdminEndpoint() throws Exception {
        // Donors self-register (Business Rule 8); the server refuses DONOR even though the console never offers it.
        mockMvc.perform(createRequest(seededAdminToken(), new CreateStaffAccountPayload(
                "Should Not Exist", uniqueLocalPhone(), uniqueEmail(), Role.DONOR, null, null
            )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.role").exists());
    }

    @Test
    void theFacilityIsRequiredOrForbiddenAccordingToTheRole() throws Exception {
        String adminToken = seededAdminToken();
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        BloodBank bank = bloodBankRepository.findAllByOrderByNameAsc().get(0);

        // Hospital staff with no hospital: an account that can see nothing.
        mockMvc.perform(createRequest(adminToken, new CreateStaffAccountPayload(
                "No Hospital", uniqueLocalPhone(), uniqueEmail(), Role.HOSPITAL_STAFF, null, null
            )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.hospitalId").exists());

        // Bank staff with no bank: the same failure on the other side.
        mockMvc.perform(createRequest(adminToken, new CreateStaffAccountPayload(
                "No Bank", uniqueLocalPhone(), uniqueEmail(), Role.BLOOD_BANK_STAFF, null, null
            )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.bloodBankId").exists());

        // An administrator with a facility: refused rather than ignored, because the
        // field would otherwise be dropped silently and mean nothing forever after.
        mockMvc.perform(createRequest(adminToken, new CreateStaffAccountPayload(
                "Admin With A Bank", uniqueLocalPhone(), uniqueEmail(), Role.ADMIN, bank.getId(), null
            )))
            .andExpect(status().isBadRequest());

        // Staff carrying the other role's facility as well as their own.
        mockMvc.perform(createRequest(adminToken, new CreateStaffAccountPayload(
                "Both Facilities", uniqueLocalPhone(), uniqueEmail(),
                Role.HOSPITAL_STAFF, bank.getId(), hospital.getId()
            )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.bloodBankId").exists());
    }

    @Test
    void aPhoneNumberAlreadyRegisteredIsRefused() throws Exception {
        String adminToken = seededAdminToken();
        String phone = uniqueLocalPhone();

        createAccount(adminToken, new CreateStaffAccountPayload(
            "First Holder", phone, uniqueEmail(), Role.ADMIN, null, null
        ));

        // The same number typed the other accepted way (Business Rule 13) must be refused as a duplicate.
        String sameNumberInternational = "+232 " + phone.substring(1, 3) + " " + phone.substring(3);

        mockMvc.perform(createRequest(adminToken, new CreateStaffAccountPayload(
                "Second Holder", sameNumberInternational, uniqueEmail(), Role.ADMIN, null, null
            )))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("phone number")));
    }

    @Test
    void aPhoneNumberThatIsNotAValidNumberIsRefused() throws Exception {
        mockMvc.perform(createRequest(seededAdminToken(), new CreateStaffAccountPayload(
                "Bad Number", "07690000", uniqueEmail(), Role.ADMIN, null, null
            )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.phone").exists());
    }

    // ---------- who may call it ----------

    @Test
    void aDonorTokenIsRefused() throws Exception {
        assertCreationForbiddenFor(donorToken());
    }

    @Test
    void aHospitalStaffTokenIsRefused() throws Exception {
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        User staff = staffAccount("Hospital Desk Clerk", Role.HOSPITAL_STAFF);
        staff.setHospital(hospital);
        userRepository.save(staff);

        assertCreationForbiddenFor(signIn(staff.getPhone(), PASSWORD));
    }

    @Test
    void aBloodBankStaffTokenIsRefused() throws Exception {
        BloodBank bank = bloodBankRepository.findAllByOrderByNameAsc().get(0);
        User staff = staffAccount("Lab Technician", Role.BLOOD_BANK_STAFF);
        staff.setBloodBank(bank);
        userRepository.save(staff);

        assertCreationForbiddenFor(signIn(staff.getPhone(), PASSWORD));
    }

    @Test
    void anUnauthenticatedCallerIsRefused() throws Exception {
        // 401 rather than 403: there is no identity to deny, so the honest answer is
        // "sign in first", which is what RestAuthenticationEntryPoint returns.
        mockMvc.perform(post("/api/admin/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateStaffAccountPayload(
                    "Nobody", uniqueLocalPhone(), uniqueEmail(), Role.ADMIN, null, null
                ))))
            .andExpect(status().isUnauthorized());
    }

    /**
     * Every non-admin role is refused by SecurityConfig's URL rule, before AdminUserService is reached.
     */
    private void assertCreationForbiddenFor(String token) throws Exception {
        long accountsBefore = userRepository.count();

        mockMvc.perform(createRequest(token, new CreateStaffAccountPayload(
                "Should Not Exist", uniqueLocalPhone(), uniqueEmail(), Role.ADMIN, null, null
            )))
            .andExpect(status().isForbidden());

        assertThat(userRepository.count()).isEqualTo(accountsBefore);
    }

    // ---------- helpers ----------

    private RequestBuilder createRequest(
        String token, CreateStaffAccountPayload payload
    ) throws Exception {
        return post("/api/admin/users")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(payload));
    }

    /** Creates an account over the real endpoint and returns the parsed 201 body. */
    private CreatedAccountResponse createAccount(String token, CreateStaffAccountPayload payload) throws Exception {
        MvcResult result = mockMvc.perform(createRequest(token, payload))
            .andExpect(status().isCreated())
            .andReturn();
        return readBody(result, CreatedAccountResponse.class);
    }

    /** Signs in over POST /api/auth/login and returns the issued token. */
    private String signIn(String phone, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(phone, password))))
            .andExpect(status().isOk())
            .andReturn();
        return readBody(result, AuthResponse.class).token();
    }

    /**
     * An administrator seeded directly, since the first admin is always created outside the application.
     */
    private String seededAdminToken() throws Exception {
        User admin = staffAccount("System Administrator", Role.ADMIN);
        userRepository.save(admin);
        return signIn(admin.getPhone(), PASSWORD);
    }

    /** A donor, created the only way donors are created: public registration. */
    private String donorToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(
                    "Aminata Sesay", uniqueLocalPhone(), uniqueEmail(),
                    LocalDate.of(1998, 3, 12), Gender.FEMALE, "O+", "Wusum, Makeni", PASSWORD
                ))))
            .andExpect(status().isCreated())
            .andReturn();
        return readBody(result, AuthResponse.class).token();
    }

    /** A signed-in-ready account of one role, with its phone stored normalised —
     *  the shape an administrator creating one by hand has to produce. */
    private User staffAccount(String fullName, Role role) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail(uniqueEmail());
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        return user;
    }

    private <T> T readBody(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type);
    }

    /**
     * A distinct local-format number (0 plus eight digits): a clock-based start per run, then a counter,
     * so no two numbers collide within or across runs.
     */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "076" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();

    private String uniqueEmail() {
        return "admin.it." + UUID.randomUUID() + "@example.com";
    }
}
