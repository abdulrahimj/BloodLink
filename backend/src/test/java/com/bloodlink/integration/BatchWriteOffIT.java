package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.BatchWriteOff;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.WriteOffReason;
import com.bloodlink.repository.AuditLogRepository;
import com.bloodlink.repository.BatchWriteOffRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;

import jakarta.persistence.EntityManager;
// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The write-off action (Business Rule 24, Stage 3), end to end through the real HTTP
 * endpoint and a real database: what a write-off writes (the batch's units, the
 * write-off row, the STOCK_WRITTEN_OFF audit entry pointing at the batch, a low-stock
 * alert), that expired batches can be written off, who may do it, every refusal and
 * that a refusal writes nothing, the audit log page's view of it, and the lock it
 * takes.
 *
 * The application clock is BatchStockCutoverIT's movable one, so the test can check
 * that every timestamp a write-off writes came from that clock and not the system's.
 *
 * Each test works on blood banks it creates for itself, so every figure is exact.
 * Shares bloodlink_test and its guarantee: {@code @Transactional}, all rolled back.
 * Hibernate's SQL is logged for this class so one test can read back the lock.
 */
@SpringBootTest(properties = "logging.level.org.hibernate.SQL=DEBUG")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@ExtendWith(OutputCaptureExtension.class)
@Import(BatchStockCutoverIT.MutableClockConfig.class)
class BatchWriteOffIT {

    private static final String PASSWORD = "correct-horse-battery-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodInventoryBatchRepository batchRepository;
    @Autowired private BatchWriteOffRepository writeOffRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private BatchStockCutoverIT.MutableClock clock;

    private BloodBank bank;
    private BloodBank otherBank;
    private User staff;
    private User admin;
    private String staffToken;
    private String otherStaffToken;
    private String hospitalToken;
    private String donorToken;
    private String adminToken;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        today = LocalDate.now(clock);
        bank = newBank("Write-off Bank");
        otherBank = newBank("Write-off Other Bank");
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        staff = user("Write-off Technician", Role.BLOOD_BANK_STAFF, bank, null);
        admin = user("Write-off Administrator", Role.ADMIN, null, null);
        staffToken = signIn(staff);
        otherStaffToken = signIn(user("Other Bank Technician", Role.BLOOD_BANK_STAFF, otherBank, null));
        hospitalToken = signIn(user("Write-off Ward Clerk", Role.HOSPITAL_STAFF, null, hospital));
        donorToken = signIn(user("Write-off Donor", Role.DONOR, null, null));
        adminToken = signIn(admin);
    }

    // ---------- what a write-off writes ----------

    @Test
    void aWriteOffReducesTheBatchRecordsWhoWhenAndWhyAuditsItAgainstTheBatchAndAlertsTheBank(CapturedOutput output)
        throws Exception {
        // 42 usable units: Sufficient. Writing off 3 leaves 39, which is Low.
        UUID batchId = addBatch("O+", 42, today.plusDays(40));
        Instant now = clock.instant();

        writeOff(staffToken, bank.getId(), batchId, 3, "DAMAGED", "  Bag leaking  ")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.writeOff.units").value(3))
            .andExpect(jsonPath("$.writeOff.reason").value("DAMAGED"))
            .andExpect(jsonPath("$.writeOff.note").value("Bag leaking"))
            .andExpect(jsonPath("$.writeOff.writtenOffByName").value("Write-off Technician"))
            .andExpect(jsonPath("$.batch.id").value(batchId.toString()))
            .andExpect(jsonPath("$.batch.unitsReceived").value(42))
            .andExpect(jsonPath("$.batch.unitsRemaining").value(39))
            .andExpect(jsonPath("$.batch.state").value("USABLE"));
        flush();

        // The batch: 3 fewer units, last_updated from the application clock.
        BloodInventoryBatch batch = batchRepository.findById(batchId).orElseThrow();
        assertThat(batch.getUnitsRemaining()).isEqualTo(39);
        assertThat(batch.getUnitsReceived()).isEqualTo(42);
        assertThat(batch.getLastUpdated()).isCloseTo(now, within(1, ChronoUnit.MICROS));

        // The write-off row: who, when, how many, why.
        List<BatchWriteOff> writeOffs = writeOffRepository.findForBatch(batchId);
        assertThat(writeOffs).singleElement().satisfies(w -> {
            assertThat(w.getUnits()).isEqualTo(3);
            assertThat(w.getReason()).isEqualTo(WriteOffReason.DAMAGED);
            assertThat(w.getNote()).isEqualTo("Bag leaking");
            assertThat(w.getWrittenOffBy().getId()).isEqualTo(staff.getId());
            assertThat(w.getWrittenOffAt()).isCloseTo(now, within(1, ChronoUnit.MICROS));
        });

        // The audit entry: STOCK_WRITTEN_OFF, pointing at the batch and at nothing else.
        List<AuditLog> entries = auditLogRepository.findByTargetBatchIdOldestFirst(batchId);
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getAction()).isEqualTo(AuditAction.STOCK_WRITTEN_OFF);
            assertThat(entry.getActor().getId()).isEqualTo(staff.getId());
            assertThat(entry.getTargetBatch().getId()).isEqualTo(batchId);
            assertThat(entry.getTarget()).isNull();
            assertThat(entry.getTargetBloodBank()).isNull();
            assertThat(entry.getTargetHospital()).isNull();
            assertThat(entry.getDetails()).isEqualTo("Wrote off 3 units of O+ from a batch at " + bank.getName()
                + " expiring " + today.plusDays(40) + ". Reason: Damaged. 39 of 42 units remain in the batch."
                + " Note: Bag leaking");
            assertThat(entry.getCreatedAt()).isCloseTo(now, within(1, ChronoUnit.MICROS));
        });
        // And in the column itself, as psql would show it.
        Object[] targets = (Object[]) entityManager.createNativeQuery(
                "select target_batch_id, target_user_id, target_blood_bank_id, target_hospital_id"
                    + " from audit_log where id = :id")
            .setParameter("id", entries.get(0).getId())
            .getSingleResult();
        assertThat(targets[0]).isEqualTo(batchId);
        assertThat(targets[1]).isNull();
        assertThat(targets[2]).isNull();
        assertThat(targets[3]).isNull();

        // Usable stock fell from Sufficient into Low, so the bank's staff are alerted,
        // exactly as after a contribution.
        JsonNode row = inventoryRow(staffToken, "O+");
        assertThat(row.get("unitsAvailable").asInt()).isEqualTo(39);
        assertThat(row.get("status").asString()).isEqualTo("LOW");
        assertThat(notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(staff.getId()))
            .anySatisfy(n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.LOW_STOCK_ALERT);
                assertThat(n.getMessage()).contains("O+ stock at " + bank.getName() + " has fallen to 39 units");
            });

        // The stock identity holds: received = remaining + issued + written off.
        assertThat(batchRepository.findIdsBreakingStockIdentity()).doesNotContain(batchId);
        // And the batch history shows the write-off.
        history(staffToken, batchId).andExpect(status().isOk())
            .andExpect(jsonPath("$.unitsWrittenOff").value(3))
            .andExpect(jsonPath("$.writeOffs.length()").value(1))
            .andExpect(jsonPath("$.writeOffs[0].reason").value("DAMAGED"))
            .andExpect(jsonPath("$.writeOffs[0].writtenOffByName").value("Write-off Technician"));

        // The lock: the same FOR NO KEY UPDATE a contribution takes, on this one row.
        String lockSql = output.getOut().lines()
            .filter(line -> line.contains("from blood_inventory_batches") && line.contains(" for "))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no locking SELECT on blood_inventory_batches was logged"));
        String sql = lockSql.substring(lockSql.indexOf("select"));
        assertThat(sql).containsPattern("where (\\w+)\\.id=\\? for no key update of \\1$");
    }

    @Test
    void anExpiredBatchCanBeWrittenOffWhichClearsTheExpiredFigureAndLeavesUsableStockAlone() throws Exception {
        addBatch("A-", 20, today.plusDays(30));
        // Straight into the table: staff can never enter a past date.
        UUID expiredId = batchRepository.save(
            BloodInventoryBatch.manual(bank, BloodType.A_NEGATIVE, 5, today.minusDays(2))).getId();
        flush();
        JsonNode before = inventoryRow(staffToken, "A-");
        assertThat(before.get("unitsAvailable").asInt()).isEqualTo(20);
        assertThat(before.get("expiredUnits").asInt()).isEqualTo(5);

        writeOff(staffToken, bank.getId(), expiredId, 5, "EXPIRED", null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.batch.unitsRemaining").value(0))
            .andExpect(jsonPath("$.batch.state").value("EXPIRED"))
            .andExpect(jsonPath("$.writeOff.note").doesNotExist());
        flush();

        JsonNode after = inventoryRow(staffToken, "A-");
        assertThat(after.get("unitsAvailable").asInt()).as("usable stock unchanged").isEqualTo(20);
        assertThat(after.get("expiredUnits").asInt()).as("nothing left awaiting discard").isZero();
        // No usable unit left the shelf, so no alert.
        assertThat(notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(staff.getId()))
            .noneMatch(n -> n.getType() == NotificationType.LOW_STOCK_ALERT);
        assertThat(auditLogRepository.findByTargetBatchIdOldestFirst(expiredId)).singleElement()
            .satisfies(entry -> assertThat(entry.getDetails()).contains("(already expired)", "Reason: Expired"));

        // The emptied batch leaves the live list but stays as history.
        listBatches(staffToken, "A-").andExpect(jsonPath("$.length()").value(1));
        history(staffToken, expiredId).andExpect(status().isOk())
            .andExpect(jsonPath("$.batch.unitsRemaining").value(0))
            .andExpect(jsonPath("$.unitsWrittenOff").value(5));
        assertThat(batchRepository.findIdsBreakingStockIdentity()).doesNotContain(expiredId);
    }

    // ---------- ADMIN reads stock but cannot change it (Stage 3b) ----------

    /**
     * Until Stage 3b this test asserted the opposite — that an ADMIN could write off
     * any bank's batch and was recorded as the actor. The project owner has decided an
     * administrator must not change stock at all, expired batches included.
     */
    @Test
    void anAdministratorCannotWriteOffAnyBatchExpiredOrNotAndNothingIsWritten() throws Exception {
        UUID usableId = addBatch("B+", 10, today.plusDays(20));
        UUID expiredId = batchRepository.save(
            BloodInventoryBatch.manual(bank, BloodType.B_POSITIVE, 5, today.minusDays(1))).getId();

        writeOff(adminToken, bank.getId(), usableId, 2, "DAMAGED", null).andExpect(status().isForbidden());
        writeOff(adminToken, bank.getId(), expiredId, 5, "EXPIRED", null).andExpect(status().isForbidden());

        assertUntouched(usableId, 10);
        assertUntouched(expiredId, 5);
    }

    @Test
    void anAdministratorCannotAddABatchAndNoBatchIsCreated() throws Exception {
        addBatchAs(adminToken, bank.getId(), "B-", 6, today.plusDays(20)).andExpect(status().isForbidden());
        flush();

        assertThat(batchRepository.findLiveForBank(bank.getId())).isEmpty();
        assertThat(inventoryRow(staffToken, "B-").get("unitsAvailable").asInt()).isZero();
    }

    @Test
    void ownBankStaffCanAddABatchAndAnotherBanksStaffCannot() throws Exception {
        addBatchAs(staffToken, bank.getId(), "B-", 6, today.plusDays(20)).andExpect(status().isOk())
            .andExpect(jsonPath("$.unitsAvailable").value(6));
        addBatchAs(otherStaffToken, bank.getId(), "B-", 4, today.plusDays(20)).andExpect(status().isForbidden());
        flush();

        assertThat(batchRepository.findLiveForBank(bank.getId())).singleElement()
            .satisfies(b -> assertThat(b.getUnitsReceived()).isEqualTo(6));
    }

    @Test
    void anAdministratorCanStillReadTheBatchListTheBatchHistoryAndTheAuditLog() throws Exception {
        UUID batchId = addBatch("AB-", 7, today.plusDays(25));
        writeOff(staffToken, bank.getId(), batchId, 1, "DAMAGED", null).andExpect(status().isCreated());
        flush();

        listBatches(adminToken, "AB-").andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].unitsRemaining").value(6));
        history(adminToken, batchId).andExpect(status().isOk())
            .andExpect(jsonPath("$.unitsWrittenOff").value(1));
        mockMvc.perform(get("/api/admin/audit-log").header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void theAdminAuditLogPageShowsAWriteOffAgainstItsBatch() throws Exception {
        UUID batchId = addBatch("O-", 12, today.plusDays(15));
        writeOff(staffToken, bank.getId(), batchId, 1, "FAILED_TESTING", null).andExpect(status().isCreated());
        flush();

        JsonNode page = json(mockMvc.perform(get("/api/admin/audit-log").param("size", "100")
                .header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk()).andReturn());
        JsonNode entry = null;
        for (JsonNode candidate : page.get("content")) {
            if (batchId.toString().equals(candidate.get("targetId").asString())) {
                entry = candidate;
            }
        }
        assertThat(entry).as("the write-off appears on the Audit Log page").isNotNull();
        assertThat(entry.get("action").asString()).isEqualTo("STOCK_WRITTEN_OFF");
        assertThat(entry.get("targetType").asString()).isEqualTo("BATCH");
        assertThat(entry.get("targetName").asString())
            .isEqualTo("O- batch, expiry " + today.plusDays(15) + " — " + bank.getName());
        assertThat(entry.get("targetRole").isNull()).isTrue();
        assertThat(entry.get("actorName").asString()).isEqualTo("Write-off Technician");
        assertThat(entry.get("details").asString()).contains("Reason: Failed testing");
    }

    // ---------- refused, with nothing written ----------

    @Test
    void badUnitsAreRefused() throws Exception {
        UUID batchId = addBatch("O+", 4, today.plusDays(10));

        writeOff(staffToken, bank.getId(), batchId, 0, "DAMAGED", null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.units").value("Must write off at least 1 unit"));
        writeOffBody(staffToken, bank.getId(), batchId, Map.of("reason", "DAMAGED"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.units").value("Units to write off is required"));
        writeOff(staffToken, bank.getId(), batchId, 5, "DAMAGED", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This batch has only 4 units left — 5 units cannot be written off."));

        assertUntouched(batchId, 4);
    }

    @Test
    void anUnknownOrMissingReasonAndAMissingNoteForOtherAreRefusedOnTheirFields() throws Exception {
        UUID batchId = addBatch("O+", 4, today.plusDays(10));

        writeOff(staffToken, bank.getId(), batchId, 1, "LOST", null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.reason")
                .value("Reason must be one of EXPIRED, DAMAGED, FAILED_TESTING, DATA_ENTRY_ERROR, OTHER."));
        writeOffBody(staffToken, bank.getId(), batchId, Map.of("units", 1))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.reason").value("A reason is required"));
        writeOff(staffToken, bank.getId(), batchId, 1, "OTHER", "   ")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.note").value("A note is required when the reason is OTHER."));
        writeOff(staffToken, bank.getId(), batchId, 1, "DAMAGED", "x".repeat(501))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.note").value("A note must be at most 500 characters."));

        assertUntouched(batchId, 4);
    }

    @Test
    void aBatchWithNoUnitsLeftCannotBeWrittenOff() throws Exception {
        UUID batchId = addBatch("O+", 2, today.plusDays(10));
        writeOff(staffToken, bank.getId(), batchId, 2, "DAMAGED", null).andExpect(status().isCreated());

        writeOff(staffToken, bank.getId(), batchId, 1, "DAMAGED", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This batch has no units left to write off."));
        flush();

        assertThat(writeOffRepository.findForBatch(batchId)).hasSize(1);
        assertThat(auditLogRepository.findByTargetBatchIdOldestFirst(batchId)).hasSize(1);
    }

    @Test
    void anUnknownBatchAndAnotherBanksBatchAreNotFoundAndAnotherBanksStaffAreForbidden() throws Exception {
        UUID batchId = addBatch("O+", 4, today.plusDays(10));
        UUID othersBatchId = batchRepository.save(
            BloodInventoryBatch.manual(otherBank, BloodType.O_POSITIVE, 6, today.plusDays(10))).getId();

        // No such batch.
        writeOff(staffToken, bank.getId(), UUID.randomUUID(), 1, "DAMAGED", null)
            .andExpect(status().isNotFound());
        // Another bank's batch, asked for under this bank: the same 404, so a bank
        // learns nothing about another bank's stock by guessing ids.
        writeOff(staffToken, bank.getId(), othersBatchId, 1, "DAMAGED", null)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("No batch found for this id at this blood bank."));
        // Another bank's staff, naming this bank: refused outright.
        writeOff(otherStaffToken, bank.getId(), batchId, 1, "DAMAGED", null)
            .andExpect(status().isForbidden());
        // Staff of this bank, naming the other bank: likewise.
        writeOff(staffToken, otherBank.getId(), othersBatchId, 1, "DAMAGED", null)
            .andExpect(status().isForbidden());
        // No such bank. (Asked by own-bank staff: since Stage 3b an ADMIN is refused
        // with a 403 before any bank is looked up, so it can no longer show this.)
        writeOff(staffToken, UUID.randomUUID(), batchId, 1, "DAMAGED", null)
            .andExpect(status().isNotFound());

        assertUntouched(batchId, 4);
        assertUntouched(othersBatchId, 6);
    }

    @Test
    void hospitalsDonorsAndAnonymousCallersCannotWriteOff() throws Exception {
        UUID batchId = addBatch("O+", 4, today.plusDays(10));

        writeOff(hospitalToken, bank.getId(), batchId, 1, "DAMAGED", null).andExpect(status().isForbidden());
        writeOff(donorToken, bank.getId(), batchId, 1, "DAMAGED", null).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/inventory/{b}/batches/{id}/write-offs", bank.getId(), batchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("units", 1, "reason", "DAMAGED"))))
            .andExpect(status().isUnauthorized());

        assertUntouched(batchId, 4);
    }

    // ---------- helpers ----------

    /** A refused write-off changed nothing: same units, no write-off row, no audit entry. */
    private void assertUntouched(UUID batchId, int unitsRemaining) {
        flush();
        assertThat(batchRepository.findById(batchId).orElseThrow().getUnitsRemaining()).isEqualTo(unitsRemaining);
        assertThat(writeOffRepository.findForBatch(batchId)).isEmpty();
        assertThat(auditLogRepository.findByTargetBatchIdOldestFirst(batchId)).isEmpty();
    }

    private ResultActions writeOff(String token, UUID bankId, UUID batchId, int units, String reason, String note)
        throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("units", units, "reason", reason));
        if (note != null) {
            body.put("note", note);
        }
        return writeOffBody(token, bankId, batchId, body);
    }

    private ResultActions writeOffBody(String token, UUID bankId, UUID batchId, Map<String, Object> body)
        throws Exception {
        return mockMvc.perform(post("/api/inventory/{b}/batches/{id}/write-offs", bankId, batchId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    /** Add New Batch through the real endpoint, as whoever {@code token} belongs to. */
    private ResultActions addBatchAs(String token, UUID bankId, String group, int units, LocalDate expiry)
        throws Exception {
        return mockMvc.perform(patch("/api/inventory/{b}/{t}", bankId, group)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("unitsToAdd", units, "expiryDate", expiry.toString()))));
    }

    /** Adds a batch through the real Add New Batch endpoint, as this bank's staff,
     *  and returns its id. */
    private UUID addBatch(String group, int units, LocalDate expiry) throws Exception {
        addBatchAs(staffToken, bank.getId(), group, units, expiry).andExpect(status().isOk());
        return batchRepository.findLiveForBankAndBloodType(bank.getId(), BloodType.fromLabel(group)).stream()
            .filter(b -> b.getExpiryDate().equals(expiry) && b.getUnitsReceived() == units)
            .findFirst().orElseThrow().getId();
    }

    private ResultActions listBatches(String token, String group) throws Exception {
        return mockMvc.perform(get("/api/inventory/{b}/batches", bank.getId())
            .param("bloodType", group).header("Authorization", bearer(token)));
    }

    private ResultActions history(String token, UUID batchId) throws Exception {
        return mockMvc.perform(get("/api/inventory/{b}/batches/{id}", bank.getId(), batchId)
            .header("Authorization", bearer(token)));
    }

    private JsonNode inventoryRow(String token, String group) throws Exception {
        JsonNode rows = json(mockMvc.perform(get("/api/inventory/{b}", bank.getId()).header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andReturn());
        for (JsonNode row : rows) {
            if (row.get("bloodType").asString().equals(group)) {
                return row;
            }
        }
        throw new AssertionError("no row for " + group);
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private BloodBank newBank(String name) {
        BloodBank created = new BloodBank();
        created.setName(name + " " + UUID.randomUUID().toString().substring(0, 8));
        created.setLocation("Makeni");
        created.setContactPhone("076 000 000");
        return bloodBankRepository.save(created);
    }

    private User user(String fullName, Role role, BloodBank bloodBank, Hospital hospital) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail(role == Role.DONOR ? null : "writeoff.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setBloodBank(bloodBank);
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

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. A
     *  prefix of its own (078) so they cannot meet BatchStockCutoverIT's (077). */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "078" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
