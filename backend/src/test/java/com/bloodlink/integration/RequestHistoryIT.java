package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CancelRequestPayload;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestAction;
import com.bloodlink.request.dto.RequestActionPayload;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Request history end to end (Business Rule 36): who sees what, open requests never in it and finished ones never
 * on the dashboards, the filters, paging, the closing time, and the bank never given the cancellation note. Rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RequestHistoryIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";
    private static final String NOTE = "Bed 4 patient Isatu moved to Connaught";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private BloodBank bankA;
    private BloodBank bankB;
    private Hospital hospital;
    private Hospital otherHospital;
    private String bankAToken;
    private String bankBToken;
    private String hospitalToken;
    private String otherHospitalToken;

    // r1 FULFILLED by A; r2 CANCELLED after 1 unit from A; r3 REJECTED (A and B declined); r4 FULFILLED by B alone;
    // r5 open; r6 the other hospital's, FULFILLED by A; r7 O-, REJECTED.
    private UUID r1;
    private UUID r2;
    private UUID r3;
    private UUID r4;
    private UUID r5;
    private UUID r6;
    private UUID r7;

    /** The two seeded banks (so every bank declining closes a request) with 20 AB- each, and two new hospitals. */
    @BeforeEach
    void setUp() throws Exception {
        List<BloodBank> banks = bloodBankRepository.findAllByOrderByNameAsc();
        bankA = banks.get(0);
        bankB = banks.get(1);
        hospital = newHospital("History Hospital");
        otherHospital = newHospital("History Other Hospital");

        bankAToken = signIn(account("History Technician A", Role.BLOOD_BANK_STAFF, bankA, null));
        bankBToken = signIn(account("History Technician B", Role.BLOOD_BANK_STAFF, bankB, null));
        hospitalToken = signIn(account("History Ward Clerk", Role.HOSPITAL_STAFF, null, hospital));
        otherHospitalToken = signIn(account("History Other Clerk", Role.HOSPITAL_STAFF, null, otherHospital));
        addStock(bankAToken, bankA.getId(), 20);
        addStock(bankBToken, bankB.getId(), 20);

        r1 = submit(hospitalToken, "AB-", 2, UrgencyLevel.URGENT);
        contribute(bankAToken, r1, 2).andExpect(jsonPath("$.status").value("FULFILLED"));

        r2 = submit(hospitalToken, "AB-", 3, UrgencyLevel.ROUTINE);
        contribute(bankAToken, r2, 1).andExpect(status().isOk());
        mockMvc.perform(post("/api/requests/{id}/cancel", r2).header("Authorization", bearer(hospitalToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CancelRequestPayload("PATIENT_TRANSFERRED", NOTE))))
            .andExpect(status().isOk());

        r3 = submit(hospitalToken, "AB-", 1, UrgencyLevel.URGENT);
        decline(bankAToken, r3).andExpect(status().isOk());
        decline(bankBToken, r3).andExpect(jsonPath("$.status").value("REJECTED"));

        r4 = submit(hospitalToken, "AB-", 2, UrgencyLevel.ROUTINE);
        contribute(bankBToken, r4, 2).andExpect(jsonPath("$.status").value("FULFILLED"));

        r5 = submit(hospitalToken, "AB-", 4, UrgencyLevel.EMERGENCY);

        r6 = submit(otherHospitalToken, "AB-", 1, UrgencyLevel.URGENT);
        contribute(bankAToken, r6, 1).andExpect(jsonPath("$.status").value("FULFILLED"));

        r7 = submit(hospitalToken, "O-", 2, UrgencyLevel.EMERGENCY);
        decline(bankAToken, r7).andExpect(status().isOk());
        decline(bankBToken, r7).andExpect(jsonPath("$.status").value("REJECTED"));
    }

    // ---------- who sees what ----------

    @Test
    void aHospitalSeesOnlyItsOwnFinishedRequests() throws Exception {
        assertThat(historyIds(hospitalToken, "")).containsExactlyInAnyOrder(r1, r2, r3, r4, r7);
        assertThat(historyIds(otherHospitalToken, "")).containsExactly(r6);
    }

    @Test
    void aBankSeesTheRequestsItContributedToOrDeclinedAndNoOthers() throws Exception {
        JsonNode page = history(bankAToken, "");
        assertThat(ids(page)).containsExactlyInAnyOrder(r1, r2, r3, r6, r7);
        // r4 was filled by bank B alone: bank A never touched it.
        assertThat(ids(page)).doesNotContain(r4);
        assertThat(entry(page, r1).get("unitsFromYourBank").asInt()).isEqualTo(2);
        assertThat(entry(page, r1).get("declinedByYourBank").asBoolean()).isFalse();
        assertThat(entry(page, r3).get("unitsFromYourBank").asInt()).isZero();
        assertThat(entry(page, r3).get("declinedByYourBank").asBoolean()).isTrue();
        assertThat(entry(page, r6).get("request").get("hospitalName").asString()).isEqualTo(otherHospital.getName());

        assertThat(historyIds(bankBToken, "")).containsExactlyInAnyOrder(r3, r4, r7);
    }

    @Test
    void aHospitalsEntriesCarryNoBankFields() throws Exception {
        JsonNode r1Entry = entry(history(hospitalToken, ""), r1);
        assertThat(r1Entry.get("unitsFromYourBank").isNull()).isTrue();
        assertThat(r1Entry.get("declinedByYourBank").isNull()).isTrue();
        assertThat(r1Entry.get("request").get("contributions").get(0).get("bloodBankName").asString())
            .isEqualTo(bankA.getName());
    }

    // ---------- open never in history, finished never on a dashboard ----------

    @Test
    void anOpenRequestIsNeverInHistoryAndAFinishedOneNeverOnADashboard() throws Exception {
        assertThat(historyIds(hospitalToken, "")).doesNotContain(r5);
        assertThat(historyIds(bankAToken, "")).doesNotContain(r5);

        assertThat(dashboardIds(hospitalToken)).contains(r5).doesNotContain(r1, r2, r3, r4, r7);
        assertThat(dashboardIds(bankAToken)).contains(r5).doesNotContain(r1, r2, r3, r4, r6, r7);
        assertThat(dashboardIds(bankBToken)).contains(r5).doesNotContain(r1, r2, r3, r4, r6, r7);
    }

    // ---------- the cancellation note ----------

    @Test
    void aBankGetsTheCancellationReasonButNeverTheNoteOrTheName() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/requests/history").header("Authorization", bearer(bankAToken)))
            .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(NOTE).doesNotContain("History Ward Clerk");
        JsonNode cancelled = entry(objectMapper.readTree(result.getResponse().getContentAsString()), r2).get("request");
        assertThat(cancelled.get("cancellationReason").asString()).isEqualTo("PATIENT_TRANSFERRED");
        assertThat(cancelled.get("cancellationNote").isNull()).isTrue();
        assertThat(cancelled.get("cancelledByName").isNull()).isTrue();

        JsonNode own = entry(history(hospitalToken, ""), r2).get("request");
        assertThat(own.get("cancellationNote").asString()).isEqualTo(NOTE);
        assertThat(own.get("cancelledByName").asString()).isEqualTo("History Ward Clerk");
    }

    // ---------- the closing time ----------

    @Test
    void eachFinishedRequestClosesAtTheMomentItFinished() throws Exception {
        assertThat(closedAt(r1)).isEqualTo(instant(
            "select max(contributed_at) from blood_request_contributions where blood_request_id = ?", r1));
        assertThat(closedAt(r2)).isEqualTo(instant("select cancelled_at from blood_requests where id = ?", r2));
        assertThat(closedAt(r3)).isEqualTo(instant(
            "select max(declined_at) from blood_request_declines where blood_request_id = ?", r3));
        entityManager.flush();
        assertThat(jdbc.queryForObject("select closed_at from blood_requests where id = ?", Timestamp.class, r5))
            .isNull();
        assertThat(entry(history(hospitalToken, ""), r1).get("request").get("closedAt").isNull()).isFalse();
    }

    // ---------- filters ----------

    @Test
    void filtersByStatusBloodGroupAndHospital() throws Exception {
        assertThat(historyIds(hospitalToken, "?status=CANCELLED")).containsExactly(r2);
        assertThat(historyIds(hospitalToken, "?status=REJECTED")).containsExactlyInAnyOrder(r3, r7);
        assertThat(historyIds(hospitalToken, "?bloodType=O-")).containsExactly(r7);
        assertThat(historyIds(bankAToken, "?hospitalId=" + otherHospital.getId())).containsExactly(r6);
        assertThat(historyIds(bankAToken, "?hospitalId=" + hospital.getId() + "&status=FULFILLED"))
            .containsExactly(r1);
    }

    @Test
    void filtersByClosedDateBothEndsInclusive() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        setClosedAt(r1, today.minusDays(40).atTime(12, 0).toInstant(ZoneOffset.UTC));

        assertThat(historyIds(hospitalToken, "?from=" + today.minusDays(29))).containsExactlyInAnyOrder(r2, r3, r4, r7);
        assertThat(historyIds(hospitalToken, "?to=" + today.minusDays(30))).containsExactly(r1);
        // The whole of both named days counts.
        assertThat(historyIds(hospitalToken, "?from=" + today.minusDays(40) + "&to=" + today.minusDays(40)))
            .containsExactly(r1);
        assertThat(historyIds(hospitalToken, "?from=" + today + "&to=" + today)).hasSize(4);
    }

    @Test
    void badFiltersAreRefusedNamingTheField() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        refused(hospitalToken, "?from=" + today + "&to=" + today.minusDays(1), "from");
        refused(hospitalToken, "?from=yesterday", "from");
        refused(hospitalToken, "?status=PENDING", "status");
        refused(hospitalToken, "?bloodType=Z", "bloodType");
        // A hospital sees only its own; naming one is refused, not ignored.
        refused(hospitalToken, "?hospitalId=" + otherHospital.getId(), "hospitalId");
        refused(hospitalToken, "?size=101", "size");
        refused(hospitalToken, "?page=-1", "page");
    }

    // ---------- paging ----------

    @Test
    void pagesNewestClosedFirstWithTotals() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        setClosedAt(r7, base.minusSeconds(10));
        setClosedAt(r4, base.minusSeconds(20));
        setClosedAt(r3, base.minusSeconds(30));
        setClosedAt(r2, base.minusSeconds(40));
        setClosedAt(r1, base.minusSeconds(50));

        JsonNode first = history(hospitalToken, "?size=2");
        assertThat(ids(first)).containsExactly(r7, r4);
        assertThat(first.get("totalElements").asLong()).isEqualTo(5);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("size").asInt()).isEqualTo(2);
        assertThat(ids(history(hospitalToken, "?size=2&page=1"))).containsExactly(r3, r2);
        assertThat(ids(history(hospitalToken, "?size=2&page=2"))).containsExactly(r1);
        // Past the end is an empty page, not an error.
        assertThat(ids(history(hospitalToken, "?size=2&page=3"))).isEmpty();
        // 20 a page by default.
        assertThat(history(hospitalToken, "").get("size").asInt()).isEqualTo(20);
    }

    // ---------- access ----------

    @Test
    void donorsAdministratorsAndStrangersAreRefused() throws Exception {
        String donorToken = signIn(account("History Donor", Role.DONOR, null, null));
        String adminToken = signIn(account("History Admin", Role.ADMIN, null, null));

        mockMvc.perform(get("/api/requests/history").header("Authorization", bearer(donorToken)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/requests/history").header("Authorization", bearer(adminToken)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/requests/history")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/requests/summary").header("Authorization", bearer(bankAToken)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/requests/summary").header("Authorization", bearer(donorToken)))
            .andExpect(status().isForbidden());
    }

    // ---------- the Hospital dashboard's cards ----------

    @Test
    void theHospitalSummaryCountsFinishedRequestsToo() throws Exception {
        mockMvc.perform(get("/api/requests/summary").header("Authorization", bearer(hospitalToken)))
            .andExpect(status().isOk())
            // r1, r2, r3, r4, r5 and r7, all raised just now.
            .andExpect(jsonPath("$.requestsThisMonth").value(6))
            .andExpect(jsonPath("$.awaitingUnits").value(1))
            // 2 into r1, 1 into r2 (cancelled, still received), 2 into r4.
            .andExpect(jsonPath("$.unitsReceived").value(5))
            // r5 only: r7 was an emergency too, but it is closed.
            .andExpect(jsonPath("$.openEmergencies").value(1));
    }

    // ---------- helpers ----------

    private JsonNode history(String token, String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/requests/history" + query).header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<UUID> historyIds(String token, String query) throws Exception {
        return ids(history(token, query));
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("content").forEach(entry -> ids.add(UUID.fromString(entry.get("request").get("id").asString())));
        return ids;
    }

    private static JsonNode entry(JsonNode page, UUID requestId) {
        for (JsonNode entry : page.get("content")) {
            if (entry.get("request").get("id").asString().equals(requestId.toString())) {
                return entry;
            }
        }
        throw new AssertionError("No history entry for " + requestId);
    }

    private void refused(String token, String query, String field) throws Exception {
        mockMvc.perform(get("/api/requests/history" + query).header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors." + field).exists());
    }

    private List<UUID> dashboardIds(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/requests").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        BloodRequestResponse[] rows = objectMapper.readValue(
            result.getResponse().getContentAsString(), BloodRequestResponse[].class);
        return List.of(rows).stream().map(BloodRequestResponse::id).toList();
    }

    /** Moves one of this test's own requests' closing time (a cancelled one's cancelled_at with it, per V33). */
    private void setClosedAt(UUID requestId, Instant at) {
        entityManager.flush();
        Timestamp value = Timestamp.from(at);
        int changed = jdbc.update("""
            update blood_requests
               set closed_at = ?,
                   cancelled_at = case when status = 'CANCELLED' then ? else cancelled_at end
             where id = ?
            """, value, value, requestId);
        assertThat(changed).isEqualTo(1);
        // Pending changes were flushed first; clearing makes the next read see this row as the database now has it.
        entityManager.clear();
    }

    private Instant closedAt(UUID requestId) {
        return instant("select closed_at from blood_requests where id = ?", requestId);
    }

    private Instant instant(String sql, UUID id) {
        entityManager.flush();
        return jdbc.queryForObject(sql, Timestamp.class, id).toInstant();
    }

    private UUID submit(String token, String group, int units, UrgencyLevel urgency) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/requests")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequestPayload(group, units, urgency, null))))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), BloodRequestResponse.class).id();
    }

    private ResultActions contribute(String token, UUID requestId, int units) throws Exception {
        return act(token, requestId, new RequestActionPayload(RequestAction.CONTRIBUTE, null, units));
    }

    private ResultActions decline(String token, UUID requestId) throws Exception {
        return act(token, requestId, new RequestActionPayload(RequestAction.REJECT, null, null));
    }

    private ResultActions act(String token, UUID requestId, RequestActionPayload body) throws Exception {
        return mockMvc.perform(patch("/api/requests/{id}", requestId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    private void addStock(String token, UUID bankId, int units) throws Exception {
        mockMvc.perform(patch("/api/inventory/{bankId}/{group}", bankId, "AB-")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AddBatchRequest(units, LocalDate.now().plusDays(30)))))
            .andExpect(status().isOk());
    }

    private Hospital newHospital(String name) {
        Hospital created = new Hospital();
        created.setName(name + " " + UUID.randomUUID().toString().substring(0, 8));
        created.setLocation("Makeni");
        created.setContactPhone("076 000 001");
        return hospitalRepository.save(created);
    }

    private User account(String fullName, Role role, BloodBank bank, Hospital facility) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail(role == Role.DONOR ? null : "history.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setBloodBank(bank);
        user.setHospital(facility);
        return userRepository.save(user);
    }

    private String signIn(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(user.getPhone(), PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).token();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Distinct local-format numbers within a run, with a prefix (031) no other IT uses. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "031" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
