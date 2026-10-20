package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
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
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.AuditLogRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;

import jakarta.persistence.EntityManager;
// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stage 3b-2 (Business Rule 8): an ADMIN cannot record a donation or CONTRIBUTE (403, nothing written),
 * while bank staff still can and an ADMIN may still decline. @Transactional, rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminStockLockoutIT {

    private static final String PASSWORD = "correct-horse-battery-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private DonorRepository donorRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private BloodBank bank;
    private BloodBank otherBank;
    private String staffToken;
    private String otherStaffToken;
    private String hospitalToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        bank = newBank("Lockout Bank");
        otherBank = newBank("Lockout Other Bank");
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        staffToken = signIn(user("Lockout Technician", Role.BLOOD_BANK_STAFF, bank, null));
        otherStaffToken = signIn(user("Lockout Other Technician", Role.BLOOD_BANK_STAFF, otherBank, null));
        hospitalToken = signIn(user("Lockout Ward Clerk", Role.HOSPITAL_STAFF, null, hospital));
        adminToken = signIn(user("Lockout Administrator", Role.ADMIN, null, null));
    }

    // ---------- Record Donation ----------

    @Test
    void anAdministratorCannotRecordADonationForAnyBankAndNothingIsWritten() throws Exception {
        Donor donor = donor();
        long auditBefore = auditLogRepository.count();

        recordDonation(adminToken, donor, bank.getId()).andExpect(status().isForbidden());
        // Naming no bank changes nothing: refused all the same.
        recordDonation(adminToken, donor, null).andExpect(status().isForbidden());
        flush();

        assertThat(donationsOf(donor)).isZero();
        assertThat(batchesAt(bank)).isZero();
        assertThat(donorRepository.findById(donor.getId()).orElseThrow().getLastDonationDate())
            .as("the donor's recovery clock did not start").isNull();
        assertThat(auditLogRepository.count()).isEqualTo(auditBefore);
    }

    @Test
    void ownBankStaffCanStillRecordADonation() throws Exception {
        Donor donor = donor();

        recordDonation(staffToken, donor, null).andExpect(status().isCreated())
            .andExpect(jsonPath("$.bloodBankId").value(bank.getId().toString()))
            .andExpect(jsonPath("$.stockAfter").value(1));
        flush();

        assertThat(donationsOf(donor)).isEqualTo(1);
        assertThat(batchesAt(bank)).isEqualTo(1);
    }

    @Test
    void hospitalStaffCannotRecordADonationAndAnotherBanksStaffCannotPutUnitsOnThisBanksShelf() throws Exception {
        Donor first = donor();
        recordDonation(hospitalToken, first, bank.getId()).andExpect(status().isForbidden());

        // Another bank's staff naming this bank: the name is ignored, the donation is
        // theirs, and this bank's shelf is untouched.
        Donor second = donor();
        recordDonation(otherStaffToken, second, bank.getId()).andExpect(status().isCreated());
        flush();

        assertThat(donationsOf(first)).isZero();
        assertThat(batchesAt(bank)).isZero();
        assertThat(batchesAt(otherBank)).isEqualTo(1);
    }

    // ---------- CONTRIBUTE ----------

    @Test
    void anAdministratorCannotContributeFromAnyBankAndNothingIsWritten() throws Exception {
        addStock(staffToken, bank, 10);
        UUID requestId = raiseRequest(4);
        long auditBefore = auditLogRepository.count();

        contribute(adminToken, requestId, bank.getId(), 4).andExpect(status().isForbidden());
        contribute(adminToken, requestId, null, 4).andExpect(status().isForbidden());
        flush();

        assertThat(contributionsTo(requestId)).isZero();
        assertThat(requestStatus(requestId)).isEqualTo("PENDING");
        assertThat(usableUnits(bank, "O+")).isEqualTo(10);
        assertThat(auditLogRepository.count()).isEqualTo(auditBefore);
    }

    @Test
    void ownBankStaffCanStillContribute() throws Exception {
        addStock(staffToken, bank, 10);
        UUID requestId = raiseRequest(4);

        contribute(staffToken, requestId, null, 4).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULFILLED"));
        flush();

        assertThat(contributionsTo(requestId)).isEqualTo(1);
        assertThat(usableUnits(bank, "O+")).isEqualTo(6);
    }

    @Test
    void hospitalStaffCannotContributeAndAnotherBanksStaffCannotTakeThisBanksUnits() throws Exception {
        addStock(staffToken, bank, 10);
        UUID requestId = raiseRequest(4);

        contribute(hospitalToken, requestId, bank.getId(), 4).andExpect(status().isForbidden());
        // Another bank's staff naming this bank contribute from their own empty shelf, so are refused;
        // this bank's units never move.
        contribute(otherStaffToken, requestId, bank.getId(), 4).andExpect(status().isConflict());
        flush();

        assertThat(contributionsTo(requestId)).isZero();
        assertThat(usableUnits(bank, "O+")).isEqualTo(10);
    }

    // ---------- what an ADMIN keeps ----------

    @Test
    void anAdministratorCanStillDeclineARequestOnANamedBanksBehalf() throws Exception {
        UUID requestId = raiseRequest(4);

        mockMvc.perform(patch("/api/requests/{id}", requestId)
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("action", "REJECT", "bloodBankId", bank.getId().toString()))))
            .andExpect(status().isOk());
        flush();

        Long declines = entityManager.createQuery(
                "select count(d) from BloodRequestDecline d where d.bloodRequest.id = :id and d.bloodBank.id = :bank", Long.class)
            .setParameter("id", requestId).setParameter("bank", bank.getId())
            .getSingleResult();
        assertThat(declines).isEqualTo(1);
    }

    // ---------- helpers ----------

    private ResultActions recordDonation(String token, Donor donor, UUID bloodBankId) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
            "donorId", donor.getId().toString(), "bloodType", "O+", "unitsDonated", 1));
        if (bloodBankId != null) {
            body.put("bloodBankId", bloodBankId.toString());
        }
        return mockMvc.perform(post("/api/donations")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions contribute(String token, UUID requestId, UUID bloodBankId, int units) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("action", "CONTRIBUTE", "units", units));
        if (bloodBankId != null) {
            body.put("bloodBankId", bloodBankId.toString());
        }
        return mockMvc.perform(patch("/api/requests/{id}", requestId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    private void addStock(String token, BloodBank owner, int units) throws Exception {
        mockMvc.perform(patch("/api/inventory/{b}/{t}", owner.getId(), "O+")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "unitsToAdd", units, "expiryDate", LocalDate.now().plusDays(30).toString()))))
            .andExpect(status().isOk());
    }

    private UUID raiseRequest(int units) throws Exception {
        JsonNode created = json(mockMvc.perform(post("/api/requests")
                .header("Authorization", bearer(hospitalToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "bloodType", "O+", "unitsRequested", units, "urgencyLevel", "URGENT"))))
            .andExpect(status().isCreated())
            .andReturn());
        return UUID.fromString(created.get("id").asString());
    }

    private int usableUnits(BloodBank owner, String group) throws Exception {
        JsonNode rows = json(mockMvc.perform(get("/api/inventory/{b}", owner.getId()).header("Authorization", bearer(staffToken)))
            .andExpect(status().isOk()).andReturn());
        for (JsonNode row : rows) {
            if (row.get("bloodType").asString().equals(group)) {
                return row.get("unitsAvailable").asInt();
            }
        }
        throw new AssertionError("no row for " + group);
    }

    private long donationsOf(Donor donor) {
        return entityManager.createQuery("select count(d) from Donation d where d.donor.id = :id", Long.class)
            .setParameter("id", donor.getId()).getSingleResult();
    }

    private long batchesAt(BloodBank owner) {
        return entityManager.createQuery("select count(b) from BloodInventoryBatch b where b.bloodBank.id = :id", Long.class)
            .setParameter("id", owner.getId()).getSingleResult();
    }

    private long contributionsTo(UUID requestId) {
        return entityManager.createQuery(
                "select count(c) from BloodRequestContribution c where c.bloodRequest.id = :id", Long.class)
            .setParameter("id", requestId).getSingleResult();
    }

    private String requestStatus(UUID requestId) {
        return entityManager.createQuery("select r.status from BloodRequest r where r.id = :id", Object.class)
            .setParameter("id", requestId).getSingleResult().toString();
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private Donor donor() {
        Donor donor = new Donor();
        donor.setUser(user("Lockout Donor " + UUID.randomUUID().toString().substring(0, 6), Role.DONOR, null, null));
        donor.setBloodType(BloodType.O_POSITIVE);
        donor.setDateOfBirth(LocalDate.of(1993, 5, 1));
        donor.setGender(Gender.FEMALE);
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
        user.setEmail(role == Role.DONOR ? null : "lockout.it." + UUID.randomUUID() + "@example.com");
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

    /** Distinct local-format numbers (see AdminAccountCreationIT), with prefix 079 to avoid the other ITs' numbers. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "079" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
