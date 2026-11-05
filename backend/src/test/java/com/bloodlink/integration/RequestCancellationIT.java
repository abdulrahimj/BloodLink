package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.repository.AuditLogRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CancelRequestPayload;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestAction;
import com.bloodlink.request.dto.RequestActionPayload;

import tools.jackson.databind.ObjectMapper;

/**
 * A hospital cancelling its own open request, end to end (Business Rule 35). Rolled back on bloodlink_test;
 * the race against a contribution is in PartialFulfilmentRaceIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RequestCancellationIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";
    private static final String GROUP = "AB-";
    private static final String NOTE = "Ward 3 patient Fatmata moved to Freetown";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private BloodRequestRepository bloodRequestRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbc;

    private BloodBank bankA;
    private BloodBank bankB;
    private User bankAStaff;
    private User bankBStaff;
    private User hospitalStaff;
    private Hospital otherHospital;
    private String bankAToken;
    private String bankBToken;
    private String hospitalToken;

    /** Two seeded banks, each with a technician holding 9 units, and a clerk at the first hospital. */
    @BeforeEach
    void setUp() throws Exception {
        List<BloodBank> banks = bloodBankRepository.findAllByOrderByNameAsc();
        bankA = banks.get(0);
        bankB = banks.get(1);
        List<Hospital> hospitals = hospitalRepository.findAllByOrderByNameAsc();
        Hospital hospital = hospitals.get(0);
        otherHospital = hospitals.get(1);

        bankAStaff = account("Cancel Technician A", Role.BLOOD_BANK_STAFF, bankA, null);
        bankBStaff = account("Cancel Technician B", Role.BLOOD_BANK_STAFF, bankB, null);
        hospitalStaff = account("Cancel Ward Clerk", Role.HOSPITAL_STAFF, null, hospital);
        bankAToken = signIn(bankAStaff);
        bankBToken = signIn(bankBStaff);
        hospitalToken = signIn(hospitalStaff);

        addStock(bankAToken, bankA.getId(), 9);
        addStock(bankBToken, bankB.getId(), 9);
    }

    // ---------- cancelling ----------

    @Test
    void aHospitalCancelsAPendingRequest() throws Exception {
        UUID requestId = submitRequest(5);

        cancel(hospitalToken, requestId, "NO_LONGER_NEEDED", null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellationReason").value("NO_LONGER_NEEDED"))
            .andExpect(jsonPath("$.cancellationNote").doesNotExist())
            .andExpect(jsonPath("$.cancelledByName").value("Cancel Ward Clerk"))
            .andExpect(jsonPath("$.cancelledAt").exists())
            .andExpect(jsonPath("$.unitsContributed").value(0))
            .andExpect(jsonPath("$.unitsOutstanding").value(0));

        // It leaves the hospital's dashboard list and is on its Request history, with who and why (Business Rule 36).
        assertThat(requestIdsVisibleTo(hospitalToken)).doesNotContain(requestId);
        mockMvc.perform(get("/api/requests/history").header("Authorization", bearer(hospitalToken)))
            .andExpect(jsonPath("$.content[0].request.id").value(requestId.toString()))
            .andExpect(jsonPath("$.content[0].request.status").value("CANCELLED"))
            .andExpect(jsonPath("$.content[0].request.cancelledByName").value("Cancel Ward Clerk"));

        assertThat(cancellationEntries(requestId)).singleElement()
            .satisfies(entry -> assertThat(entry.getDetails()).endsWith("No units had been supplied."));
    }

    @Test
    void cancellingAPartlySuppliedRequestKeepsTheSuppliedUnitsAndReturnsNoStock() throws Exception {
        UUID requestId = submitRequest(5);
        contribute(bankAToken, requestId, 3).andExpect(status().isOk());
        int stockAfterSupplying = unitsInStock(bankA.getId(), bankAToken);
        assertThat(stockAfterSupplying).isEqualTo(6);

        cancel(hospitalToken, requestId, "PATIENT_TRANSFERRED", NOTE)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellationNote").value(NOTE))
            .andExpect(jsonPath("$.unitsRequested").value(5))
            .andExpect(jsonPath("$.unitsContributed").value(3))
            .andExpect(jsonPath("$.unitsOutstanding").value(0))
            .andExpect(jsonPath("$.contributions.length()").value(1));

        assertThat(unitsInStock(bankA.getId(), bankAToken)).isEqualTo(stockAfterSupplying);
        assertThat(jdbc.queryForObject(
            "select count(*) from blood_request_contributions where blood_request_id = ?", Long.class, requestId))
            .isEqualTo(1L);
    }

    // ---------- when it is refused ----------

    @Test
    void aFullySuppliedRequestCannotBeCancelled() throws Exception {
        UUID requestId = submitRequest(2);
        contribute(bankAToken, requestId, 2).andExpect(status().isOk());

        cancel(hospitalToken, requestId, "NO_LONGER_NEEDED", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This request was just fully supplied, so it can no longer be cancelled."));
    }

    @Test
    void aRejectedRequestCannotBeCancelled() throws Exception {
        UUID requestId = submitRequest(2);
        decline(bankAToken, requestId).andExpect(status().isOk());
        decline(bankBToken, requestId).andExpect(status().isOk());

        cancel(hospitalToken, requestId, "NO_LONGER_NEEDED", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Every blood bank declined this request, so it is already closed."));
    }

    @Test
    void aRequestCannotBeCancelledTwiceAndTheFirstReasonStands() throws Exception {
        UUID requestId = submitRequest(2);
        cancel(hospitalToken, requestId, "ENTERED_BY_MISTAKE", null).andExpect(status().isOk());

        cancel(hospitalToken, requestId, "NO_LONGER_NEEDED", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This request has already been cancelled."));

        assertThat(bloodRequestRepository.findById(requestId).orElseThrow().getCancellationReason().name())
            .isEqualTo("ENTERED_BY_MISTAKE");
        assertThat(cancellationEntries(requestId)).hasSize(1);
    }

    // ---------- who may cancel ----------

    @Test
    void anotherHospitalsStaffGetTheSameAnswerAsForARequestThatDoesNotExist() throws Exception {
        UUID requestId = submitRequest(2);
        String otherClerk = signIn(account("Other Clerk", Role.HOSPITAL_STAFF, null, otherHospital));

        cancel(otherClerk, requestId, "NO_LONGER_NEEDED", null)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("No blood request found for this id."));
        cancel(otherClerk, UUID.randomUUID(), "NO_LONGER_NEEDED", null)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("No blood request found for this id."));

        assertStillPending(requestId);
    }

    @Test
    void bankStaffDonorsAndAdministratorsAreRefused() throws Exception {
        UUID requestId = submitRequest(2);

        cancel(bankAToken, requestId, "NO_LONGER_NEEDED", null).andExpect(status().isForbidden());
        cancel(signIn(account("Cancel Donor", Role.DONOR, null, null)), requestId, "NO_LONGER_NEEDED", null)
            .andExpect(status().isForbidden());
        cancel(signIn(account("Cancel Admin", Role.ADMIN, null, null)), requestId, "NO_LONGER_NEEDED", null)
            .andExpect(status().isForbidden());

        assertStillPending(requestId);
    }

    @Test
    void noTokenIsRefused() throws Exception {
        UUID requestId = submitRequest(2);

        mockMvc.perform(post("/api/requests/{id}/cancel", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("NO_LONGER_NEEDED", null)))
            .andExpect(status().isUnauthorized());

        assertStillPending(requestId);
    }

    @Test
    void suspendedHospitalStaffAreRefused() throws Exception {
        UUID requestId = submitRequest(2);
        hospitalStaff.setStatus(AccountStatus.SUSPENDED);
        userRepository.saveAndFlush(hospitalStaff);

        cancel(hospitalToken, requestId, "NO_LONGER_NEEDED", null).andExpect(status().isUnauthorized());

        assertStillPending(requestId);
    }

    // ---------- the reason and the note ----------

    @Test
    void aReasonIsRequiredAndMustBeOneOfTheFive() throws Exception {
        UUID requestId = submitRequest(2);

        cancel(hospitalToken, requestId, null, null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.reason").value("A reason is required"));
        cancel(hospitalToken, requestId, "CHANGED_MY_MIND", null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.reason").value(containsString("NO_LONGER_NEEDED")));

        assertStillPending(requestId);
    }

    @Test
    void aNoteIsRequiredForOtherAndABlankOneDoesNotCount() throws Exception {
        UUID requestId = submitRequest(2);

        cancel(hospitalToken, requestId, "OTHER", null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.note").exists());
        cancel(hospitalToken, requestId, "OTHER", "   ")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.note").exists());
        assertStillPending(requestId);

        cancel(hospitalToken, requestId, "OTHER", "  Theatre closed  ")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cancellationNote").value("Theatre closed"));
    }

    @Test
    void aNoteMayBe200CharactersButNot201() throws Exception {
        UUID requestId = submitRequest(2);

        cancel(hospitalToken, requestId, "NO_LONGER_NEEDED", "x".repeat(201))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.note").value(containsString("200")));
        assertStillPending(requestId);

        cancel(hospitalToken, requestId, "NO_LONGER_NEEDED", "x".repeat(200)).andExpect(status().isOk());
    }

    // ---------- who is told ----------

    @Test
    void everyBankStillWatchingIsToldThroughItsActiveStaffOnly() throws Exception {
        // A third bank that never touched the request: it could still see it, so it is told too.
        BloodBank bystander = bank("Cancel Bystander Bank");
        User bystanderStaff = account("Bystander Technician", Role.BLOOD_BANK_STAFF, bystander, null);
        User suspended = account("Suspended Technician", Role.BLOOD_BANK_STAFF, bankA, null);
        suspended.setStatus(AccountStatus.SUSPENDED);
        userRepository.saveAndFlush(suspended);
        User admin = account("Bystander Admin", Role.ADMIN, null, null);

        UUID requestId = submitRequest(5);
        contribute(bankAToken, requestId, 2).andExpect(status().isOk()); // bank A contributed
        decline(bankBToken, requestId).andExpect(status().isOk());       // bank B declined: no longer watching

        cancel(hospitalToken, requestId, "OBTAINED_ELSEWHERE", NOTE).andExpect(status().isOk());

        String reference = bloodRequestRepository.findById(requestId).orElseThrow().getReference();
        String expected = "Request #%s (AB-, 5 units) was cancelled by %s: Obtained elsewhere."
            .formatted(reference, hospitalStaff.getHospital().getName());

        assertThat(cancelNotices(bankAStaff)).extracting(Notification::getMessage).containsExactly(expected);
        assertThat(cancelNotices(bystanderStaff)).extracting(Notification::getMessage).containsExactly(expected);

        assertThat(cancelNotices(bankBStaff)).isEmpty();
        assertThat(cancelNotices(suspended)).isEmpty();
        assertThat(cancelNotices(admin)).isEmpty();
        assertThat(cancelNotices(hospitalStaff)).isEmpty();
    }

    // ---------- what blood banks can do and see afterwards ----------

    @Test
    void aCancelledRequestCannotBeContributedToOrDeclined() throws Exception {
        UUID requestId = submitRequest(4);
        cancel(hospitalToken, requestId, "NO_LONGER_NEEDED", null).andExpect(status().isOk());

        contribute(bankAToken, requestId, 2)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This request was just cancelled, so it cannot take more units."));
        decline(bankBToken, requestId)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This request was just cancelled, so there is nothing to decline."));

        assertThat(unitsInStock(bankA.getId(), bankAToken)).isEqualTo(9);
    }

    @Test
    void itLeavesTheQueueAndStaysOnlyAsAContributorsHistoryWithoutTheNoteOrTheName() throws Exception {
        UUID requestId = submitRequest(5);
        contribute(bankAToken, requestId, 3).andExpect(status().isOk());
        assertThat(requestIdsVisibleTo(bankBToken)).contains(requestId);

        cancel(hospitalToken, requestId, "PATIENT_TRANSFERRED", NOTE).andExpect(status().isOk());

        // It leaves both queues (Business Rule 36). Bank A, which gave to it, has it on its history with the reason
        // alone; bank B neither gave nor declined, so it is not on bank B's.
        assertThat(requestIdsVisibleTo(bankAToken)).doesNotContain(requestId);
        assertThat(requestIdsVisibleTo(bankBToken)).doesNotContain(requestId);
        String row = "$.content[?(@.request.id == '%s')]".formatted(requestId);
        mockMvc.perform(get("/api/requests/history").header("Authorization", bearer(bankAToken)))
            .andExpect(jsonPath(row + ".request.status").value(org.hamcrest.Matchers.contains("CANCELLED")))
            .andExpect(jsonPath(row + ".request.cancellationReason").value(org.hamcrest.Matchers.contains("PATIENT_TRANSFERRED")))
            .andExpect(jsonPath(row + ".request.unitsContributed").value(org.hamcrest.Matchers.contains(3)))
            .andExpect(jsonPath(row + ".unitsFromYourBank").value(org.hamcrest.Matchers.contains(3)))
            .andExpect(jsonPath(row + ".request.cancellationNote").value(org.hamcrest.Matchers.contains((Object) null)))
            .andExpect(jsonPath(row + ".request.cancelledByName").value(org.hamcrest.Matchers.contains((Object) null)));
        // The private note never appears anywhere in the bank's history response.
        String bankHistory = mockMvc.perform(get("/api/requests/history").header("Authorization", bearer(bankAToken)))
            .andReturn().getResponse().getContentAsString();
        assertThat(bankHistory).doesNotContain(NOTE);
        mockMvc.perform(get("/api/requests/history").header("Authorization", bearer(bankBToken)))
            .andExpect(jsonPath(row).isEmpty());
    }

    @Test
    void itNoLongerCountsAsAnOpenRequestOnTheBankDashboard() throws Exception {
        UUID requestId = submitRequest(2);
        long before = bloodRequestRepository.countOpenForBank(bankA.getId());

        cancel(hospitalToken, requestId, "NO_LONGER_NEEDED", null).andExpect(status().isOk());

        assertThat(bloodRequestRepository.countOpenForBank(bankA.getId())).isEqualTo(before - 1);
    }

    // ---------- the audit log and the reports ----------

    @Test
    void anAuditEntryRecordsWhoAndWhyWithoutTheNoteOrTheWard() throws Exception {
        UUID requestId = submitRequest(5);
        contribute(bankAToken, requestId, 3).andExpect(status().isOk());
        cancel(hospitalToken, requestId, "PATIENT_TRANSFERRED", NOTE).andExpect(status().isOk());

        List<AuditLog> entries = cancellationEntries(requestId);
        assertThat(entries).hasSize(1);
        AuditLog entry = entries.get(0);
        assertThat(entry.getActor().getId()).isEqualTo(hospitalStaff.getId());
        assertThat(entry.getDetails())
            .contains("AB-, 5 units", "Reason: Patient transferred", "3 of 5 units had been supplied")
            .doesNotContain("Fatmata", "Theatre");

        String adminToken = signIn(account("Audit Reader", Role.ADMIN, null, null));
        mockMvc.perform(get("/api/admin/audit-log").header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].action").value("REQUEST_CANCELLED"))
            .andExpect(jsonPath("$.content[0].actorName").value("Cancel Ward Clerk"))
            .andExpect(jsonPath("$.content[0].targetType").value("REQUEST"))
            .andExpect(jsonPath("$.content[0].targetId").value(requestId.toString()))
            .andExpect(jsonPath("$.content[0].targetName").value(containsString("AB-")));
    }

    @Test
    void reportsCountCancelledRequestsWithoutCallingThemDecided() throws Exception {
        String adminToken = signIn(account("Report Reader", Role.ADMIN, null, null));
        long cancelledBefore = cancelledInOutcomes(adminToken);
        long decidedBefore = decidedInReport(adminToken);

        cancel(hospitalToken, submitRequest(2), "NO_LONGER_NEEDED", null).andExpect(status().isOk());

        assertThat(cancelledInOutcomes(adminToken)).isEqualTo(cancelledBefore + 1);
        assertThat(decidedInReport(adminToken)).isEqualTo(decidedBefore);
        mockMvc.perform(get("/api/admin/reports/summary/requests").header("Authorization", bearer(adminToken)))
            .andExpect(jsonPath("$.byStatus[?(@.label == 'CANCELLED')].count").exists());
    }

    // ---------- the database's own guards (V32) ----------

    @Test
    void theDatabaseRefusesAHalfRecordedCancellation() throws Exception {
        UUID requestId = submitRequest(2);
        // Hibernate holds the insert back; without this the plain SQL below would match no row.
        bloodRequestRepository.flush();

        assertRefused("blood_requests_cancellation_matches_status",
            "update blood_requests set status = 'CANCELLED' where id = ?", requestId);
        assertRefused("blood_requests_cancellation_matches_status",
            "update blood_requests set cancellation_reason = 'NO_LONGER_NEEDED' where id = ?", requestId);
        assertRefused("blood_requests_cancellation_reason_check",
            """
            update blood_requests set status = 'CANCELLED', cancellation_reason = 'BORED',
                cancelled_by = ?, cancelled_at = now() where id = ?
            """, hospitalStaff.getId(), requestId);
        assertRefused("blood_requests_cancellation_note_required_for_other",
            """
            update blood_requests set status = 'CANCELLED', cancellation_reason = 'OTHER',
                cancelled_by = ?, cancelled_at = now() where id = ?
            """, hospitalStaff.getId(), requestId);
        assertRefused("blood_requests_cancellation_note_not_blank",
            """
            update blood_requests set status = 'CANCELLED', cancellation_reason = 'NO_LONGER_NEEDED',
                cancellation_note = '  ', cancelled_by = ?, cancelled_at = now() where id = ?
            """, hospitalStaff.getId(), requestId);
    }

    @Test
    void theAuditLogRefusesACancellationEntryWithNoRequest() {
        userRepository.flush();
        assertRefused("audit_log_target_matches_action",
            "insert into audit_log (actor_user_id, action, details) values (?, 'REQUEST_CANCELLED', 'x')",
            hospitalStaff.getId());
    }

    // ---------- helpers ----------

    private void assertRefused(String expected, String sql, Object... args) {
        jdbc.execute("savepoint expected_refusal");
        try {
            assertThatThrownBy(() -> jdbc.update(sql, args)).hasStackTraceContaining(expected);
        } finally {
            jdbc.execute("rollback to savepoint expected_refusal");
        }
    }

    private void assertStillPending(UUID requestId) {
        assertThat(bloodRequestRepository.findById(requestId).orElseThrow().getStatus().name()).isEqualTo("PENDING");
        assertThat(cancellationEntries(requestId)).isEmpty();
    }

    private List<AuditLog> cancellationEntries(UUID requestId) {
        return auditLogRepository.findByActionOldestFirst(AuditAction.REQUEST_CANCELLED).stream()
            .filter(entry -> entry.getTargetBloodRequest().getId().equals(requestId))
            .toList();
    }

    private List<Notification> cancelNotices(User recipient) {
        return notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(recipient.getId()).stream()
            .filter(notification -> notification.getType() == NotificationType.REQUEST_CANCELLED)
            .toList();
    }

    private long cancelledInOutcomes(String adminToken) throws Exception {
        return readJson(get("/api/admin/reports/requests"), adminToken).get("cancelled").asLong();
    }

    private long decidedInReport(String adminToken) throws Exception {
        return readJson(get("/api/admin/reports/summary/requests"), adminToken).get("decided").asLong();
    }

    private tools.jackson.databind.JsonNode readJson(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String token
    ) throws Exception {
        MvcResult result = mockMvc.perform(request.header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private ResultActions cancel(String token, UUID requestId, String reason, String note) throws Exception {
        return mockMvc.perform(post("/api/requests/{id}/cancel", requestId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload(reason, note)));
    }

    private String payload(String reason, String note) {
        return objectMapper.writeValueAsString(new CancelRequestPayload(reason, note));
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

    private UUID submitRequest(int units) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/requests")
                .header("Authorization", bearer(hospitalToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequestPayload(
                    GROUP, units, UrgencyLevel.URGENT, "Theatre 2"))))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), BloodRequestResponse.class).id();
    }

    private void addStock(String token, UUID bankId, int units) throws Exception {
        mockMvc.perform(patch("/api/inventory/{bankId}/{group}", bankId, GROUP)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new AddBatchRequest(units, LocalDate.now().plusDays(30)))))
            .andExpect(status().isOk());
    }

    private int unitsInStock(UUID bankId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/inventory/{bankId}", bankId)
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        InventoryItemResponse[] rows = objectMapper.readValue(
            result.getResponse().getContentAsString(), InventoryItemResponse[].class);
        return List.of(rows).stream()
            .filter(row -> row.bloodType().equals(GROUP))
            .findFirst()
            .orElseThrow()
            .unitsAvailable();
    }

    private List<UUID> requestIdsVisibleTo(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/requests").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        BloodRequestResponse[] rows = objectMapper.readValue(
            result.getResponse().getContentAsString(), BloodRequestResponse[].class);
        return List.of(rows).stream().map(BloodRequestResponse::id).toList();
    }

    private BloodBank bank(String name) {
        BloodBank bank = new BloodBank();
        bank.setName(name);
        bank.setLocation("Makeni");
        bank.setContactPhone("076 000 000");
        return bloodBankRepository.save(bank);
    }

    private User account(String fullName, Role role, BloodBank bank, Hospital hospital) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail("cancel.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setBloodBank(bank);
        user.setHospital(hospital);
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

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "077" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
