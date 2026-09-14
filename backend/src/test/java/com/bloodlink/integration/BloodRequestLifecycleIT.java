package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
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
 * The end-to-end tests in this project: realistic paths through every layer —
 * SecurityConfig and JwtAuthenticationFilter, Bean Validation, the controllers,
 * the services, and a real PostgreSQL database — rather than single classes in
 * isolation with everything around them mocked away.
 *
 * Two flows live here, both about the life of one blood request:
 * {@link #donorRegistrationHospitalRequestBankFulfilmentAndNotificationAllWorkTogether}
 * walks the happy path from registration through fulfilment, and
 * {@link #oneBankDecliningKeepsARequestOpenForTheOthersAndTheLastDeclineClosesIt}
 * covers Business Rule 11's per-bank declines, which cannot be proven anywhere
 * else: the rule is enforced partly by a JPQL not-exists clause, and only a real
 * query against real rows can show a request leaving one bank's queue while
 * staying in another's.
 *
 * They share this class deliberately rather than splitting into a second IT.
 * CLAUDE.md reserves the {@code bloodlink_test} database for this one test class
 * precisely because it is {@code @Transactional} — every method rolls back
 * everything it writes, so re-running is always safe and never leaves residue to
 * clean up. A method added here inherits that guarantee; a separate consumer of
 * that database would have to re-earn it.
 *
 * The first flow, in order:
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
 * see {@code application-test.properties} for why re-running these tests is always
 * safe — each method is wrapped in one transaction that is rolled back at the end,
 * so nothing they write is ever left behind for the next run to trip over.
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

    /**
     * Business Rule 11's per-bank rejection, end to end against a real database.
     *
     * This is deliberately an integration test rather than another unit test. The
     * rule's visible half — "a bank that has declined stops seeing the request, and
     * every other bank keeps seeing it" — lives in a JPQL not-exists clause in
     * BloodRequestRepository.findActionableForBank. A mocked repository would return
     * whatever it was told to and prove nothing about that clause; only a real query
     * against real rows can show the request actually leaving one bank's queue while
     * staying in another's.
     *
     * The flow walks every blood bank that exists rather than hard-coding two, so
     * the assertion is literally the rule — PENDING after each decline except the
     * last, REJECTED only when none are left — rather than an arithmetic coincidence
     * that would quietly stop testing anything if a third bank were ever seeded.
     */
    @Test
    void oneBankDecliningKeepsARequestOpenForTheOthersAndTheLastDeclineClosesIt() throws Exception {
        List<BloodBank> banks = bloodBankRepository.findAllByOrderByNameAsc();
        // The interesting behaviour only exists with more than one bank: with a
        // single bank, "some banks have declined" and "all banks have declined" are
        // the same state and nothing here would be proven.
        assertThat(banks).hasSizeGreaterThan(1);

        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);

        User hospitalStaff = staffAccount(
            "Request Desk Clerk", "+232 76 900 " + uniqueSuffix(), Role.HOSPITAL_STAFF
        );
        hospitalStaff.setHospital(hospital);
        hospitalStaff = userRepository.save(hospitalStaff);
        String hospitalToken = loginAndGetToken(hospitalStaff.getPhone());

        // One staff account per bank, so each bank's queue can be read and acted on
        // as that bank — which is the only way the per-bank scoping can be observed.
        List<String> bankTokens = new ArrayList<>();
        for (BloodBank bank : banks) {
            User bankStaff = staffAccount(
                "Lab Technician " + bank.getName(), "+232 76 900 " + uniqueSuffix(), Role.BLOOD_BANK_STAFF
            );
            bankStaff.setBloodBank(bank);
            userRepository.save(bankStaff);
            bankTokens.add(loginAndGetToken(bankStaff.getPhone()));
        }

        // A group deliberately unlikely to be stocked is irrelevant here — declining
        // never touches inventory — so this uses the same shape as any other request.
        MvcResult createResult = mockMvc.perform(post("/api/requests")
                .header("Authorization", bearer(hospitalToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new CreateRequestPayload("AB-", 2, UrgencyLevel.URGENT, "ICU — Bed 8"))))
            .andExpect(status().isCreated())
            .andReturn();
        UUID requestId = readBody(createResult, BloodRequestResponse.class).id();

        // Before anyone responds, every bank can see it — Business Rule 6.
        for (String token : bankTokens) {
            assertThat(requestIdsVisibleTo(token)).contains(requestId);
        }

        // Each bank declines in turn. Every decline but the last must leave the
        // request PENDING and visible to the banks that have not yet responded.
        for (int i = 0; i < bankTokens.size(); i++) {
            boolean isLastBank = (i == bankTokens.size() - 1);
            String expectedStatus = isLastBank ? "REJECTED" : "PENDING";

            mockMvc.perform(patch("/api/requests/{id}", requestId)
                    .header("Authorization", bearer(bankTokens.get(i)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        new RequestActionPayload(RequestAction.REJECT, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                // A decline supplies nothing, so it never claims the request.
                .andExpect(jsonPath("$.fulfilledByBloodBankId").doesNotExist());

            // The declining bank has lost sight of it, for good.
            assertThat(requestIdsVisibleTo(bankTokens.get(i))).doesNotContain(requestId);

            // Declining twice as the same bank is refused rather than silently
            // counted again — which, left unguarded, could close a request before
            // the other banks had ever seen it.
            //
            // Both cases are a 409, but for different reasons, and the message is
            // asserted so this cannot pass for the wrong one: while the request is
            // still open it is the already-declined guard that refuses, and once the
            // last decline has closed it, it is the lifecycle guard (a REJECTED
            // request cannot be declined again by anybody).
            mockMvc.perform(patch("/api/requests/{id}", requestId)
                    .header("Authorization", bearer(bankTokens.get(i)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        new RequestActionPayload(RequestAction.REJECT, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString(
                    isLastBank ? "this one is rejected" : "already declined"
                )));

            // Every bank that has not responded yet still sees it and can still act
            // — the whole point of the change.
            for (int j = i + 1; j < bankTokens.size(); j++) {
                assertThat(requestIdsVisibleTo(bankTokens.get(j))).contains(requestId);
            }
        }

        // Only now, with no bank left to ask, is the hospital's request closed —
        // and the hospital is told once, on that final decline.
        mockMvc.perform(get("/api/requests").header("Authorization", bearer(hospitalToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(requestId.toString()))
            .andExpect(jsonPath("$[0].status").value("REJECTED"));

        List<Notification> hospitalNotifications =
            notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(hospitalStaff.getId());

        // Exactly one status-change notification, not one per decline: a decline
        // that leaves the request live is not news the hospital can act on.
        assertThat(hospitalNotifications)
            .extracting(Notification::getType)
            .containsOnly(NotificationType.REQUEST_STATUS_CHANGE);
        assertThat(hospitalNotifications).hasSize(1);
        assertThat(hospitalNotifications.get(0).getMessage()).contains("declined by every blood bank");
    }

    // ---------- helpers ----------

    /** The ids in one caller's own request feed — for blood bank staff, the queue
     *  their dashboard shows, already scoped by findActionableForBank. Read through
     *  the real endpoint rather than the repository so the scoping under test is the
     *  one the screen actually gets. */
    private List<UUID> requestIdsVisibleTo(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/requests").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        BloodRequestResponse[] rows = objectMapper.readValue(
            result.getResponse().getContentAsString(), BloodRequestResponse[].class
        );
        return List.of(rows).stream().map(BloodRequestResponse::id).toList();
    }

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
