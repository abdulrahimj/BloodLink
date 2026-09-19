package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.UserRepository;

import jakarta.persistence.EntityManager;
// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.ObjectMapper;

/**
 * Business Rule 18 end to end: a donor may register without an email, an email that
 * is given must look real and be unique, and staff/administrator account creation
 * still requires one exactly as before (Business Rule 14).
 *
 * <h2>Two donors without an email do not collide — and why that needs no code</h2>
 * {@link #twoDonorsWithNoEmailBothRegister} shows it through the real endpoint. The
 * database-level tests below show why: V1's unique constraint on users.email uses
 * PostgreSQL's default, standard-SQL NULL semantics — NULL is never equal to NULL —
 * so any number of rows may have no email while two real addresses still cannot
 * match. Nothing in the application works around this, and these tests would fail
 * if the constraint were ever changed to treat NULLs as equal, or removed.
 *
 * Each database test that expects a refused statement is its own method, because a
 * refused statement aborts the PostgreSQL transaction it ran in.
 *
 * Shares bloodlink_test with the other integration tests and the same guarantee —
 * {@code @Transactional}, everything rolled back. Run with:
 * <pre>{@code .\mvnw.cmd test -Dtest=DonorOptionalEmailIT}</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DonorOptionalEmailIT {

    private static final String PASSWORD = "correct-horse-battery-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private EntityManager entityManager;

    // ---------- donor registration ----------

    @Test
    void aDonorRegistersWithNoEmail() throws Exception {
        String phone = uniqueLocalPhone();

        register(phone, null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user.email").doesNotExist());

        User stored = userRepository.findByPhone(PhoneNumber.normalize(phone)).orElseThrow();
        assertThat(stored.getEmail()).isNull();
    }

    @Test
    void aBlankEmailIsStoredAsNoEmail() throws Exception {
        String phone = uniqueLocalPhone();

        register(phone, "   ").andExpect(status().isCreated());

        assertThat(userRepository.findByPhone(PhoneNumber.normalize(phone)).orElseThrow().getEmail()).isNull();
    }

    @Test
    void twoDonorsWithNoEmailBothRegister() throws Exception {
        String first = uniqueLocalPhone();
        String second = uniqueLocalPhone();

        // One with the field missing entirely, one with it blank: both mean "none".
        register(first, null).andExpect(status().isCreated());
        register(second, "").andExpect(status().isCreated());

        // Both exist, both without an email — no conflict between them.
        assertThat(userRepository.findByPhone(PhoneNumber.normalize(first)).orElseThrow().getEmail()).isNull();
        assertThat(userRepository.findByPhone(PhoneNumber.normalize(second)).orElseThrow().getEmail()).isNull();
    }

    @Test
    void aMalformedEmailIsRefusedWithAFieldError() throws Exception {
        String phone = uniqueLocalPhone();

        register(phone, "aminata@gmail")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.email").value(
                "Enter a valid email address, like name@example.com — or leave it blank."));

        assertThat(userRepository.findByPhone(PhoneNumber.normalize(phone))).isEmpty();
    }

    @Test
    void aGenuinelyDuplicateEmailIsRefusedWith409() throws Exception {
        String email = "aminata." + UUID.randomUUID() + "@example.com";
        register(uniqueLocalPhone(), email).andExpect(status().isCreated());

        // Same address, different case: still the same address.
        String secondPhone = uniqueLocalPhone();
        register(secondPhone, email.toUpperCase())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This email is already registered"));

        assertThat(userRepository.findByPhone(PhoneNumber.normalize(secondPhone))).isEmpty();
    }

    @Test
    void aDonorWithNoEmailCanUseProfileSettings() throws Exception {
        String phone = uniqueLocalPhone();
        register(phone, null).andExpect(status().isCreated());
        String token = signIn(phone);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").doesNotExist());

        // Renaming without being forced to invent an email.
        mockMvc.perform(patch("/api/users/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\": \"Aminata K. Sesay\", \"email\": \"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fullName").value("Aminata K. Sesay"))
            .andExpect(jsonPath("$.email").doesNotExist());
    }

    // ---------- staff and administrator creation: unchanged ----------

    @Test
    void staffAndAdministratorCreationStillRequiresAnEmail() throws Exception {
        String adminToken = signIn(seedAdmin());
        UUID bankId = bloodBankRepository.findAllByOrderByNameAsc().get(0).getId();

        // Missing: refused, as before.
        createStaff(adminToken, "{\"fullName\": \"No Email Staff\", \"phone\": \"" + uniqueLocalPhone()
                + "\", \"role\": \"BLOOD_BANK_STAFF\", \"bloodBankId\": \"" + bankId + "\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.email").exists());
        // Blank: refused, as before.
        createStaff(adminToken, "{\"fullName\": \"Blank Email Admin\", \"phone\": \"" + uniqueLocalPhone()
                + "\", \"email\": \"\", \"role\": \"ADMIN\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.email").exists());
        // Present: created, as before.
        createStaff(adminToken, "{\"fullName\": \"With Email Admin\", \"phone\": \"" + uniqueLocalPhone()
                + "\", \"email\": \"admin." + UUID.randomUUID() + "@example.com\", \"role\": \"ADMIN\"}")
            .andExpect(status().isCreated());
    }

    // ---------- the database: standard SQL NULL semantics, no custom logic ----------

    @Test
    void theEmailUniqueConstraintTreatsNullsAsDistinct() {
        // Read back from the database itself: a plain UNIQUE, which in PostgreSQL means
        // NULLS DISTINCT. If anyone changed it to NULLS NOT DISTINCT, this definition
        // would say so and the rest of this class would start failing too.
        Object definition = entityManager.createNativeQuery(
            "select pg_get_constraintdef(oid) from pg_constraint where conname = 'users_email_key'")
            .getSingleResult();
        assertThat(definition).isEqualTo("UNIQUE (email)");

        // And in action: two rows with no email, inserted directly, both accepted.
        insertDonorRow(null);
        insertDonorRow(null);
    }

    @Test
    void theSameConstraintStillRefusesTwoRealAddressesThatMatch() {
        String email = "same." + UUID.randomUUID() + "@example.com";
        insertDonorRow(email);

        assertThatThrownBy(() -> insertDonorRow(email))
            .hasStackTraceContaining("users_email_key");
    }

    @Test
    void theDatabaseStoresNoEmailAsNullNeverAsAnEmptyString() {
        // Two empty strings WOULD collide; V13 makes "" impossible to store at all.
        assertThatThrownBy(() -> insertDonorRow(""))
            .hasStackTraceContaining("users_email_not_blank");
    }

    @Test
    void theDatabaseStillRequiresAnEmailForStaff() {
        assertThatThrownBy(() -> entityManager.createNativeQuery(
                "insert into users (full_name, email, password_hash, role, phone) "
                    + "values ('Staff Without Email', null, 'x', 'BLOOD_BANK_STAFF', :phone)")
                .setParameter("phone", PhoneNumber.normalize(uniqueLocalPhone()))
                .executeUpdate())
            .hasStackTraceContaining("users_email_required_for_staff");
    }

    // ---------- helpers ----------

    private ResultActions register(String phone, String email) throws Exception {
        String emailJson = email == null ? "" : ", \"email\": \"" + email + "\"";
        return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\": \"Aminata Sesay\", \"phone\": \"" + phone + "\"" + emailJson
                + ", \"dateOfBirth\": \"1998-03-12\", \"gender\": \"FEMALE\", \"bloodType\": \"O+\""
                + ", \"address\": \"Wusum, Makeni\", \"password\": \"" + PASSWORD + "\"}"));
    }

    private ResultActions createStaff(String adminToken, String json) throws Exception {
        return mockMvc.perform(post("/api/admin/users")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
    }

    private void insertDonorRow(String email) {
        entityManager.createNativeQuery(
                "insert into users (full_name, email, password_hash, role, phone) "
                    + "values ('Direct Insert', :email, 'x', 'DONOR', :phone)")
            .setParameter("email", email)
            .setParameter("phone", PhoneNumber.normalize(uniqueLocalPhone()))
            .executeUpdate();
    }

    private String signIn(String phone) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(phone, PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).token();
    }

    private String seedAdmin() {
        User admin = new User();
        admin.setFullName("System Administrator");
        admin.setEmail("admin.it." + UUID.randomUUID() + "@example.com");
        String phone = uniqueLocalPhone();
        admin.setPhone(PhoneNumber.normalize(phone));
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
        return phone;
    }

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "076" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
