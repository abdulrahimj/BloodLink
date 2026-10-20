package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.ContributionAllocationRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.stock.BatchStockService;

import jakarta.persistence.EntityManager;
// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The batch-stock cutover end to end (Business Rule 24): usable stock everywhere, FEFO with allocations,
 * batch views and entered-expiry rules. A MutableClock moves the date; SQL logged to read back the lock.
 */
@SpringBootTest(properties = "logging.level.org.hibernate.SQL=DEBUG")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@ExtendWith(OutputCaptureExtension.class)
@Import(BatchStockCutoverIT.MutableClockConfig.class)
class BatchStockCutoverIT {

    private static final String PASSWORD = "correct-horse-battery-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private DonorRepository donorRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodInventoryBatchRepository batchRepository;
    @Autowired private ContributionAllocationRepository allocationRepository;
    @Autowired private BatchStockService batchStockService;
    @Autowired private MutableClock clock;

    private BloodBank bank;
    private BloodBank otherBank;
    private String staffToken;
    private String otherStaffToken;
    private String hospitalToken;
    private String adminToken;
    private String donorToken;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        today = LocalDate.now(clock);
        bank = newBank("Cutover Bank");
        otherBank = newBank("Cutover Other Bank");
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        staffToken = signIn(user("Cutover Technician", Role.BLOOD_BANK_STAFF, bank, null));
        otherStaffToken = signIn(user("Other Bank Technician", Role.BLOOD_BANK_STAFF, otherBank, null));
        hospitalToken = signIn(user("Cutover Ward Clerk", Role.HOSPITAL_STAFF, null, hospital));
        adminToken = signIn(user("Cutover Administrator", Role.ADMIN, null, null));
        donorToken = signIn(user("Cutover Donor", Role.DONOR, null, null));
    }

    // ---------- the scenario that motivated the redesign ----------

    /**
     * 10 units expiring in 2 days and 30 in 40 days stay separate batches, so on day 3 only the 30 count.
     */
    @Test
    void tenUnitsExpiringInTwoDaysAndThirtyInFortyAreTwoBatchesAndOnDayThreeOnlyTheThirtyCount() throws Exception {
        addBatch(staffToken, "O+", 10, today.plusDays(2)).andExpect(status().isOk())
            .andExpect(jsonPath("$.unitsAvailable").value(10));
        addBatch(staffToken, "O+", 30, today.plusDays(40)).andExpect(status().isOk())
            .andExpect(jsonPath("$.unitsAvailable").value(40))
            .andExpect(jsonPath("$.expiryDate").value(today.plusDays(2).toString()))
            // Only the 10 really expire soon — not all 40, as the old table said.
            .andExpect(jsonPath("$.expiringSoonUnits").value(10))
            .andExpect(jsonPath("$.expiredUnits").value(0))
            .andExpect(jsonPath("$.batchCount").value(2));

        summary(staffToken)
            .andExpect(jsonPath("$.totalUnits").value(40))
            .andExpect(jsonPath("$.expiringSoonUnits").value(10))
            .andExpect(jsonPath("$.expiredUnits").value(0));

        // Each batch keeps the date it arrived with.
        listBatches(staffToken, bank.getId(), "O+").andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].expiryDate").value(today.plusDays(2).toString()))
            .andExpect(jsonPath("$[0].state").value("EXPIRING_SOON"))
            .andExpect(jsonPath("$[0].daysUntilExpiry").value(2))
            .andExpect(jsonPath("$[1].expiryDate").value(today.plusDays(40).toString()))
            .andExpect(jsonPath("$[1].state").value("USABLE"));

        // Day 3: the first batch expired at 00:00 UTC on day 3.
        clock.advance(Duration.ofDays(3));

        JsonNode row = inventoryRow(staffToken, bank.getId(), "O+");
        assertThat(row.get("unitsAvailable").asInt()).isEqualTo(30);
        assertThat(row.get("expiredUnits").asInt()).isEqualTo(10);
        assertThat(row.get("expiringSoonUnits").asInt()).isZero(); // 37 days left: outside 28
        assertThat(row.get("expiryDate").asString()).isEqualTo(today.plusDays(40).toString());
        assertThat(row.get("status").asString()).isEqualTo("LOW");
        assertThat(row.get("batchCount").asInt()).isEqualTo(2);

        summary(staffToken)
            .andExpect(jsonPath("$.totalUnits").value(30))
            .andExpect(jsonPath("$.expiredUnits").value(10));
        listBatches(staffToken, bank.getId(), "O+")
            .andExpect(jsonPath("$[0].state").value("EXPIRED"))
            .andExpect(jsonPath("$[0].daysUntilExpiry").value(-1));

        // A hospital asks for 31: only 30 usable units exist, so the bank is refused,
        // and told about the 10 it cannot count.
        UUID requestId = raiseRequest("O+", 31);
        contribute(staffToken, requestId, 31)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("30 units available")))
            .andExpect(jsonPath("$.message").value(containsString("31 being contributed")))
            .andExpect(jsonPath("$.message").value(containsString("10 expired units cannot be issued")));

        // Issuing the 30 usable units takes them from the good batch only.
        contribute(staffToken, requestId, 30).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PARTIALLY_FULFILLED"));

        List<BloodInventoryBatch> batches = batchesOf(bank, BloodType.O_POSITIVE);
        BloodInventoryBatch expired = batches.stream().filter(b -> b.getExpiryDate().equals(today.plusDays(2))).findFirst().orElseThrow();
        BloodInventoryBatch good = batches.stream().filter(b -> b.getExpiryDate().equals(today.plusDays(40))).findFirst().orElseThrow();
        assertThat(expired.getUnitsRemaining()).as("expired blood is never issued").isEqualTo(10);
        assertThat(good.getUnitsRemaining()).isZero();

        history(staffToken, bank.getId(), good.getId()).andExpect(status().isOk())
            .andExpect(jsonPath("$.unitsIssued").value(30))
            .andExpect(jsonPath("$.issues.length()").value(1))
            .andExpect(jsonPath("$.issues[0].requestId").value(requestId.toString()))
            .andExpect(jsonPath("$.issues[0].units").value(30));
        history(staffToken, bank.getId(), expired.getId())
            .andExpect(jsonPath("$.unitsIssued").value(0))
            .andExpect(jsonPath("$.batch.state").value("EXPIRED"));

        assertLedgersBalance();
    }

    // ---------- first expiry, first out, with allocations ----------

    @Test
    void aContributionIsTakenEarliestExpiryFirstAndItsAllocationsAddUpToItsUnits(CapturedOutput output) throws Exception {
        // Added out of date order on purpose.
        addBatch(staffToken, "A-", 5, today.plusDays(20)).andExpect(status().isOk());
        addBatch(staffToken, "A-", 4, today.plusDays(3)).andExpect(status().isOk());
        addBatch(staffToken, "A-", 6, today.plusDays(9)).andExpect(status().isOk());

        UUID requestId = raiseRequest("A-", 7);
        contribute(staffToken, requestId, 7).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULFILLED"));

        // 4 from the batch expiring in 3 days, then 3 of the 6 expiring in 9 days; the
        // batch expiring in 20 days is untouched.
        Map<LocalDate, Integer> remaining = new java.util.TreeMap<>();
        batchesOf(bank, BloodType.A_NEGATIVE).forEach(b -> remaining.put(b.getExpiryDate(), b.getUnitsRemaining()));
        assertThat(remaining).containsExactly(
            Map.entry(today.plusDays(3), 0), Map.entry(today.plusDays(9), 3), Map.entry(today.plusDays(20), 5));

        // The allocations name both batches drawn on and add up to the 7 contributed.
        List<Object[]> allocations = entityManager.createQuery("""
                select b.expiryDate, a.unitsAllocated, c.unitsContributed
                  from ContributionAllocation a join a.batch b join a.contribution c
                 where c.bloodRequest.id = :requestId
                 order by b.expiryDate
                """, Object[].class)
            .setParameter("requestId", requestId)
            .getResultList();
        assertThat(allocations).hasSize(2);
        assertThat(allocations.get(0)).containsExactly(today.plusDays(3), 4, 7);
        assertThat(allocations.get(1)).containsExactly(today.plusDays(9), 3, 7);

        assertLedgersBalance();

        // The batch lock as Hibernate sends it: ordered by expiry then id, locked FOR NO KEY UPDATE in that order
        // (exclusive against other contributors, like the request lock).
        String lockSql = output.getOut().lines()
            .filter(line -> line.contains("from blood_inventory_batches") && line.contains(" for "))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no locking SELECT on blood_inventory_batches was logged"));
        String sql = lockSql.substring(lockSql.indexOf("select"));
        // ...order by bib1_0.expiry_date,bib1_0.id for no key update of bib1_0
        assertThat(sql).containsPattern("order by (\\w+)\\.expiry_date,\\1\\.id for (no key )?update of \\1$");
        assertThat(sql).contains("units_remaining>0").contains("expiry_date>=?");
        System.out.println("BATCH-LOCK-SQL: " + sql);
    }

    // ---------- one definition of usable stock ----------

    /**
     * The same shelf read six ways: every screen and rule must see the same usable figure.
     */
    @Test
    void everyScreenAndRuleSeesTheSameUsableStock() throws Exception {
        batch(BloodType.O_NEGATIVE, 12, today.plusDays(60));   // outside the 28-day window
        batch(BloodType.O_NEGATIVE, 6, today.plusDays(1));    // expiring soon
        batch(BloodType.O_NEGATIVE, 4, today);                // expires today: still usable
        batch(BloodType.O_NEGATIVE, 9, today.minusDays(1));   // expired: not stock
        flush();
        int usable = 12 + 6 + 4;

        // 1. the bank's own Inventory table
        JsonNode staffRow = inventoryRow(staffToken, bank.getId(), "O-");
        assertThat(staffRow.get("unitsAvailable").asInt()).isEqualTo(usable);
        assertThat(staffRow.get("expiredUnits").asInt()).isEqualTo(9);
        assertThat(staffRow.get("expiringSoonUnits").asInt()).isEqualTo(6 + 4);
        // 2. the hospital's availability view (the same endpoint, read as a hospital)
        assertThat(inventoryRow(hospitalToken, bank.getId(), "O-").get("unitsAvailable").asInt()).isEqualTo(usable);
        // 3. the summary cards and 4. the Dashboard
        summary(staffToken).andExpect(jsonPath("$.totalUnits").value(usable)).andExpect(jsonPath("$.expiredUnits").value(9));
        mockMvc.perform(get("/api/blood-banks/{id}/dashboard", bank.getId()).header("Authorization", bearer(staffToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalUnits").value(usable));
        // 5. the Admin Reports, per bank
        JsonNode report = json(mockMvc.perform(get("/api/admin/reports/summary/stock").header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk()).andReturn());
        JsonNode bankRow = null;
        for (JsonNode candidate : report.get("banks")) {
            if (candidate.get("bankName").asString().equals(bank.getName())) {
                bankRow = candidate;
            }
        }
        assertThat(bankRow).isNotNull();
        assertThat(bankRow.get("totalUnits").asInt()).isEqualTo(usable);
        // 6. the request-alert query: exactly the usable figure is enough, one more is not
        assertThat(batchStockService.bankIdsWithUsableUnitsAtLeast(BloodType.O_NEGATIVE, usable)).contains(bank.getId());
        assertThat(batchStockService.bankIdsWithUsableUnitsAtLeast(BloodType.O_NEGATIVE, usable + 1)).doesNotContain(bank.getId());
        // 7. the appeal rule: 22 usable is a shortage (under 30), so the bank may
        //    appeal; counting the 9 expired units (31) would have hidden that.
        assertThat(staffRow.get("appealAvailability").asString()).isEqualTo("AVAILABLE");
    }

    // ---------- the batch views: bank staff of that bank, and ADMIN ----------

    @Test
    void batchDetailIsForThatBanksStaffAndAdministratorsOnly() throws Exception {
        UUID batchId = batch(BloodType.B_POSITIVE, 3, today.plusDays(10)).getId();
        flush();

        listBatches(staffToken, bank.getId(), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        listBatches(adminToken, bank.getId(), null).andExpect(status().isOk());
        history(staffToken, bank.getId(), batchId).andExpect(status().isOk())
            .andExpect(jsonPath("$.batch.id").value(batchId.toString()))
            .andExpect(jsonPath("$.batch.source").value("MANUAL"))
            .andExpect(jsonPath("$.writeOffs.length()").value(0));
        history(adminToken, bank.getId(), batchId).andExpect(status().isOk());

        // Hospitals never see batch detail, nor donors, nor another bank's staff.
        listBatches(hospitalToken, bank.getId(), null).andExpect(status().isForbidden());
        history(hospitalToken, bank.getId(), batchId).andExpect(status().isForbidden());
        listBatches(donorToken, bank.getId(), null).andExpect(status().isForbidden());
        history(donorToken, bank.getId(), batchId).andExpect(status().isForbidden());
        listBatches(otherStaffToken, bank.getId(), null).andExpect(status().isForbidden());
        history(otherStaffToken, bank.getId(), batchId).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/{b}/batches", bank.getId())).andExpect(status().isUnauthorized());

        // A batch asked for under the wrong bank is not found, the same as no batch.
        history(adminToken, otherBank.getId(), batchId).andExpect(status().isNotFound());
        history(adminToken, bank.getId(), UUID.randomUUID()).andExpect(status().isNotFound());
        listBatches(staffToken, bank.getId(), "Q+").andExpect(status().isBadRequest());
    }

    // ---------- entered expiry dates: today .. today + 365 ----------

    @Test
    void addBatchRefusesAnExpiryOutsideTodayToAYearAheadWithA400OnTheField() throws Exception {
        addBatch(staffToken, "AB+", 2, today.minusDays(1)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.expiryDate").value(containsString("cannot be in the past")));
        addBatch(staffToken, "AB+", 2, today.plusDays(366)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.expiryDate").value(containsString("within 365 days")));
        assertThat(batchesOf(bank, BloodType.AB_POSITIVE)).isEmpty();

        addBatch(staffToken, "AB+", 2, today).andExpect(status().isOk());
        addBatch(staffToken, "AB+", 2, today.plusDays(365)).andExpect(status().isOk());
        assertThat(batchesOf(bank, BloodType.AB_POSITIVE)).hasSize(2);
    }

    @Test
    void aRecordedDonationBecomesItsOwnLinkedBatchWithTheDefaultOrTheEnteredExpiry() throws Exception {
        Donor first = donor();
        JsonNode receipt = json(recordDonation(first, "B-", 1, null).andExpect(status().isCreated()).andReturn());
        LocalDate defaultExpiry = LocalDate.now().plusDays(42);
        assertThat(receipt.get("batchExpiryDate").asString()).isEqualTo(defaultExpiry.toString());

        Donor second = donor();
        LocalDate onTheLabel = today.plusDays(30);
        recordDonation(second, "B-", 2, onTheLabel).andExpect(status().isCreated())
            .andExpect(jsonPath("$.batchExpiryDate").value(onTheLabel.toString()))
            .andExpect(jsonPath("$.stockAfter").value(3));

        // One batch per donation, linked to it, carrying its units; no merging.
        List<BloodInventoryBatch> batches = batchesOf(bank, BloodType.B_NEGATIVE);
        assertThat(batches).hasSize(2);
        assertThat(batches).allSatisfy(b -> assertThat(b.getDonation()).isNotNull());
        assertThat(batches).extracting(BloodInventoryBatch::getUnitsReceived).containsExactlyInAnyOrder(1, 2);
        assertThat(batches).extracting(BloodInventoryBatch::getExpiryDate).containsExactlyInAnyOrder(defaultExpiry, onTheLabel);
        assertThat(batches.stream().filter(b -> b.getDonation().getId().toString()
            .equals(receipt.get("donationId").asString())).findFirst()).isPresent();
    }

    @Test
    void aDonationWithAnExpiryMoreThanAYearAwayIsRefusedAndWritesNothing() throws Exception {
        Donor donor = donor();

        recordDonation(donor, "O+", 1, today.plusDays(400)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.expiryDate").value(containsString("within 365 days")));

        flush();
        assertThat(batchesOf(bank, BloodType.O_POSITIVE)).isEmpty();
        assertThat(donorRepository.findById(donor.getId()).orElseThrow().getLastDonationDate()).isNull();
        Number donations = (Number) entityManager
            .createNativeQuery("select count(*) from donations where donor_id = :id")
            .setParameter("id", donor.getId())
            .getSingleResult();
        assertThat(donations.longValue()).isZero();
    }

    // ---------- helpers ----------

    /** No batch anywhere breaks the stock identity, and no contribution's allocations
     *  disagree with its units. */
    private void assertLedgersBalance() {
        flush();
        assertThat(batchRepository.findIdsBreakingStockIdentity()).isEmpty();
        assertThat(allocationRepository.findContributionIdsWithMismatchedAllocations()).isEmpty();
    }

    private ResultActions addBatch(String token, String group, int units, LocalDate expiry) throws Exception {
        return mockMvc.perform(patch("/api/inventory/{b}/{t}", bank.getId(), group)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("unitsToAdd", units, "expiryDate", expiry.toString()))));
    }

    private ResultActions summary(String token) throws Exception {
        return mockMvc.perform(get("/api/inventory/{b}/summary", bank.getId()).header("Authorization", bearer(token)))
            .andExpect(status().isOk());
    }

    private ResultActions listBatches(String token, UUID bankId, String group) throws Exception {
        var request = get("/api/inventory/{b}/batches", bankId).header("Authorization", bearer(token));
        if (group != null) {
            request = request.param("bloodType", group);
        }
        return mockMvc.perform(request);
    }

    private ResultActions history(String token, UUID bankId, UUID batchId) throws Exception {
        return mockMvc.perform(get("/api/inventory/{b}/batches/{id}", bankId, batchId).header("Authorization", bearer(token)));
    }

    private JsonNode inventoryRow(String token, UUID bankId, String group) throws Exception {
        JsonNode rows = json(mockMvc.perform(get("/api/inventory/{b}", bankId).header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andReturn());
        for (JsonNode row : rows) {
            if (row.get("bloodType").asString().equals(group)) {
                return row;
            }
        }
        throw new AssertionError("no row for " + group);
    }

    private UUID raiseRequest(String group, int units) throws Exception {
        JsonNode created = json(mockMvc.perform(post("/api/requests")
                .header("Authorization", bearer(hospitalToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "bloodType", group, "unitsRequested", units, "urgencyLevel", "URGENT"))))
            .andExpect(status().isCreated())
            .andReturn());
        return UUID.fromString(created.get("id").asString());
    }

    private ResultActions contribute(String token, UUID requestId, int units) throws Exception {
        return mockMvc.perform(patch("/api/requests/{id}", requestId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("action", "CONTRIBUTE", "units", units))));
    }

    private ResultActions recordDonation(Donor donor, String group, int units, LocalDate expiry) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
            "donorId", donor.getId().toString(), "bloodType", group, "unitsDonated", units));
        if (expiry != null) {
            body.put("expiryDate", expiry.toString());
        }
        return mockMvc.perform(post("/api/donations")
            .header("Authorization", bearer(staffToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    /** A batch straight into the table: the only way to put an already-expired batch
     *  on a shelf, since staff can never enter a date in the past. */
    private BloodInventoryBatch batch(BloodType type, int units, LocalDate expiry) {
        return batchRepository.save(BloodInventoryBatch.manual(bank, type, units, expiry));
    }

    private List<BloodInventoryBatch> batchesOf(BloodBank owner, BloodType type) {
        flush();
        return entityManager.createQuery(
                "select b from BloodInventoryBatch b where b.bloodBank.id = :bank and b.bloodType = :type",
                BloodInventoryBatch.class)
            .setParameter("bank", owner.getId())
            .setParameter("type", type)
            .getResultList();
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private Donor donor() {
        Donor donor = new Donor();
        donor.setUser(user("Cutover Donor " + UUID.randomUUID().toString().substring(0, 6), Role.DONOR, null, null));
        donor.setBloodType(BloodType.O_POSITIVE);
        donor.setDateOfBirth(LocalDate.of(1993, 5, 1));
        donor.setGender(Gender.MALE);
        donor.setAddress("Makeni");
        return donorRepository.save(donor);
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
        user.setEmail(role == Role.DONOR ? null : "cutover.it." + UUID.randomUUID() + "@example.com");
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

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "077" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();

    // ---------- the movable clock ----------

    /** A UTC clock this test can move forward. Reset to the real time before each test. */
    static final class MutableClock extends Clock {

        private volatile Instant instant = Instant.now();

        void reset() {
            instant = Instant.now();
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class MutableClockConfig {
        /** Primary, so StockPolicy is given this rather than TimeConfig's system clock. */
        @Bean
        @Primary
        MutableClock mutableTestClock() {
            return new MutableClock();
        }
    }
}
