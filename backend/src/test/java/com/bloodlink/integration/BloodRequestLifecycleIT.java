package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.auth.dto.RegisterRequest;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestAction;
import com.bloodlink.request.dto.RequestActionPayload;

// Spring Boot 4 ships Jackson 3, where ObjectMapper lives under tools.jackson
// (see RestAuthenticationEntryPoint for the same note) — the bean autowired here
// is that one, not com.fasterxml.jackson.databind.ObjectMapper.
import tools.jackson.databind.ObjectMapper;

/**
 * The one end-to-end test in this project: a realistic path through every layer —
 * SecurityConfig and JwtAuthenticationFilter, Bean Validation, the controllers,
 * the services, and a real PostgreSQL database — rather than a single class in
 * isolation with everything around it mocked away.
 *
 * The flow, in order:
 *   1. A donor registers over the real HTTP endpoint, and their own token is
 *      proven to work against their own profile.
 *   2. A hospital-staff and a blood-bank-staff account are seeded directly via
 *      the repositories, because — deliberately, per CLAUDE.md — there is no
 *      self-registration path for either role; an administrator would create
 *      these by hand in a real deployment, which is exactly what this test does.
 *   3. The bank adds stock (Business Rule 7's accumulation, exercised for real).
 *   4. The hospital submits a request (Figure 3.5's hospital side).
 *   5. The bank approves it, then fulfils it (Business Rule 5's lifecycle, and
 *      the stock deduction Business Rule 5 also requires).
 *   6. The chain closes on the fact that started this project's Phase 7: a
 *      REQUEST_STATUS_CHANGE Notification row exists for the hospital staff who
 *      raised the request — for both the approval and the fulfilment — proving
 *      the request module and the notification module actually agree with each
 *      other rather than merely each having their own passing tests.
 *
 * <h2>Running this test</h2>
 * Excluded from the ordinary {@code mvn test} / {@code .\mvnw.cmd test} run by
 * Maven Surefire's own default naming convention — {@code *Test.java} classes are
 * included, {@code *IT.java} classes are not — so this never blocks a normal build
 * for a developer without the database below, and every other test in this
 * project stays exactly as fast and dependency-free as before this test existed.
 * Run it explicitly with:
 * <pre>{@code .\mvnw.cmd test -Dtest=BloodRequestLifecycleIT}</pre>
 *
 * It needs a real PostgreSQL database named {@code bloodlink_test} to exist —
 * create it once with {@code createdb bloodlink_test} (or the {@code psql}
 * equivalent). This is a dedicated, persistent fixture, never {@code bloodlink_dev}:
 * see {@code application-test.properties} for why re-running this test is always
 * safe — the whole method is wrapped in one transaction that is rolled back at the
 * end, so nothing it writes is ever left behind for the next run to trip over.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BloodRequestLifecycleIT {

    private static final String PASSWORD = "correct-horse-battery-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private NotificationRepository notificationRepository;

    @Test
    void donorRegistrationHospitalRequestBankFulfilmentAndNotificationAllWorkTogether() throws Exception {
        // ---------- 1. A donor registers, for real, over HTTP ----------
        RegisterRequest registration = new RegisterRequest(
            "Aminata Sesay",
            "+232 76 900 " + uniqueSuffix(),
            "aminata.it." + uniqueSuffix() + "@example.com",
            LocalDate.of(1998, 3, 12),
            Gender.FEMALE,
            "O+",
            "Wusum, Makeni",
            PASSWORD
        );

        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registration)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user.role").value("DONOR"))
            .andReturn();
        AuthResponse donorAuth = readBody(registerResult, AuthResponse.class);

        // The registration response is not just trusted — the token it issued is
        // proven to actually authenticate, against the donor's own profile.
        mockMvc.perform(get("/api/donors/me").header("Authorization", bearer(donorAuth.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fullName").value("Aminata Sesay"))
            .andExpect(jsonPath("$.eligibilityStatus").value("ELIGIBLE"));

        // ---------- 2. Seed the two staff accounts no self-registration path can create ----------
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        BloodBank bank = bloodBankRepository.findAllByOrderByNameAsc().get(0);

        User hospitalStaff = staffAccount(
            "Hospital Desk Clerk", "+232 76 900 " + uniqueSuffix(), Role.HOSPITAL_STAFF
        );
        hospitalStaff.setHospital(hospital);
        hospitalStaff = userRepository.save(hospitalStaff);

        User bankStaff = staffAccount(
            "Lab Technician", "+232 76 900 " + uniqueSuffix(), Role.BLOOD_BANK_STAFF
        );
        bankStaff.setBloodBank(bank);
        userRepository.save(bankStaff);

        String hospitalToken = loginAndGetToken(hospitalStaff.getPhone());
        String bankToken = loginAndGetToken(bankStaff.getPhone());

        // ---------- 3. The bank stocks up (Business Rule 7's accumulation, for real) ----------
        int startingOPlusUnits = readOPlusUnits(bank.getId(), bankToken);

        mockMvc.perform(patch("/api/inventory/{bankId}/{bloodType}", bank.getId(), "O+")
                .header("Authorization", bearer(bankToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new AddBatchRequest(20, LocalDate.now().plusMonths(2)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unitsAvailable").value(startingOPlusUnits + 20));

        // ---------- 4. The hospital submits a request ----------
        CreateRequestPayload requestPayload =
            new CreateRequestPayload("O+", 3, UrgencyLevel.URGENT, "Maternity Ward — Bed 4");

        MvcResult createResult = mockMvc.perform(post("/api/requests")
                .header("Authorization", bearer(hospitalToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestPayload)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.hospitalId").value(hospital.getId().toString()))
            .andReturn();
        BloodRequestResponse created = readBody(createResult, BloodRequestResponse.class);

        // ---------- 5. The bank approves, then fulfils ----------
        mockMvc.perform(patch("/api/requests/{id}", created.id())
                .header("Authorization", bearer(bankToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RequestActionPayload(RequestAction.APPROVE, null))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.fulfilledByBloodBankId").value(bank.getId().toString()));

        mockMvc.perform(patch("/api/requests/{id}", created.id())
                .header("Authorization", bearer(bankToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RequestActionPayload(RequestAction.FULFIL, null))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULFILLED"));

        // The stock deduction Business Rule 5 requires actually happened:
        // 20 units added, 3 issued, so 17 more than whatever the bank started with.
        assertThat(readOPlusUnits(bank.getId(), bankToken)).isEqualTo(startingOPlusUnits + 17);

        // The hospital sees its own request as FULFILLED through its own read path too.
        mockMvc.perform(get("/api/requests").header("Authorization", bearer(hospitalToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(created.id().toString()))
            .andExpect(jsonPath("$[0].status").value("FULFILLED"));

        // ---------- 6. The chain's final link: the hospital was actually notified ----------
        // Read directly from the repository rather than the notifications endpoint:
        // the WebSocket push is deferred to after-commit (NotificationPublisher),
        // and this whole test runs inside one transaction that is rolled back
        // rather than committed, so a live push would never fire here regardless
        // of whether the durable row — the actual subject of this assertion —
        // was correctly written.
        List<Notification> hospitalNotifications =
            notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(hospitalStaff.getId());

        assertThat(hospitalNotifications)
            .extracting(Notification::getType)
            .containsOnly(NotificationType.REQUEST_STATUS_CHANGE);
        assertThat(hospitalNotifications).hasSize(2); // one for approval, one for fulfilment
        assertThat(hospitalNotifications)
            .extracting(Notification::getMessage)
            .anySatisfy(message -> assertThat(message).contains("approved"))
            .anySatisfy(message -> assertThat(message).contains("fulfilled"));
    }

    // ---------- helpers ----------

    /** A staff account of the given role, active and ready to sign in — the shape
     *  an administrator would create by hand, since no self-registration path
     *  exists for anything but DONOR. */
    private User staffAccount(String fullName, String phone, Role role) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail(phone.replaceAll("[^0-9]", "") + "@example.com");
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        return user;
    }

    private String loginAndGetToken(String phone) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(phone, PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
        return readBody(result, AuthResponse.class).token();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private <T> T readBody(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type);
    }

    /** This bank's current O+ units, via the real inventory endpoint — used to make
     *  the test's arithmetic correct regardless of whatever this bank already held
     *  when the test started, rather than assuming it begins at zero. */
    private int readOPlusUnits(UUID bankId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/inventory/{bankId}", bankId)
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        InventoryItemResponse[] rows = objectMapper.readValue(
            result.getResponse().getContentAsString(), InventoryItemResponse[].class
        );
        return List.of(rows).stream()
            .filter(row -> row.bloodType().equals("O+"))
            .findFirst()
            .orElseThrow()
            .unitsAvailable();
    }

    /**
     * A short digits-only suffix so repeated runs of this test (or two runs
     * started in the same millisecond) never collide on phone/email uniqueness —
     * a fresh value per run rather than a fixed one, since the whole test is
     * transactional and every run's rows disappear on rollback anyway, but the
     * uniqueness constraint is still checked before that rollback happens.
     */
    private String uniqueSuffix() {
        return String.valueOf(System.nanoTime()).substring(8);
    }
}
