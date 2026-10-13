package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.bloodlink.bloodbank.dto.BloodBankResponse;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.AuditLogRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;

import jakarta.persistence.EntityManager;
// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.ObjectMapper;

/**
 * Business Rule 19 end to end: an administrator adding a blood bank or a hospital,
 * through the real endpoints, security filter chain and PostgreSQL database — which
 * is where V14's case-insensitive unique index and audit-log constraints live, and
 * so the only place they can be proven.
 *
 * Shares bloodlink_test with the other integration tests and the same guarantee:
 * {@code @Transactional}, so every facility, account and audit entry written here is
 * rolled back when each method ends, and re-running is always safe. Run with:
 * <pre>{@code .\mvnw.cmd test -Dtest=AdminFacilityCreationIT}</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminFacilityCreationIT {

    private static final String PASSWORD = "correct-horse-battery-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private EntityManager entityManager;

    // ---------- successful creation ----------

    @Test
    void anAdministratorCreatesABloodBankAndItIsListedAndAudited() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        String token = signIn(admin);
        String name = uniqueName("Kabala Road Blood Bank");

        MvcResult result = create(token, "/api/admin/blood-banks",
                facility("  " + name + "  ", "Kabala Road, Makeni", "076 111 222"))
            .andExpect(status().isCreated())
            // Stored trimmed; the contact phone exactly as typed (not Business Rule 13).
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.location").value("Kabala Road, Makeni"))
            .andExpect(jsonPath("$.contactPhone").value("076 111 222"))
            .andReturn();
        UUID bankId = idOf(result);

        // Listed by the existing endpoint every picker uses — no separate admin list.
        mockMvc.perform(get("/api/blood-banks").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name", hasItem(name)));

        // One BLOOD_BANK_CREATED entry, naming the administrator and the new bank.
        List<AuditLog> entries = auditLogRepository.findByTargetBloodBankId(bankId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAction()).isEqualTo(AuditAction.BLOOD_BANK_CREATED);
        assertThat(entries.get(0).getActor().getId()).isEqualTo(admin.getId());
        assertThat(entries.get(0).getTarget()).isNull();
        assertThat(entries.get(0).getDetails()).contains(name, "Kabala Road, Makeni", "076 111 222");

        // And the Audit Log page shows it, labelled as a blood bank rather than an
        // account, inside the paged envelope (Business Rule 17's pagination).
        mockMvc.perform(get("/api/admin/audit-log").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].action").value("BLOOD_BANK_CREATED"))
            .andExpect(jsonPath("$.content[0].targetType").value("BLOOD_BANK"))
            .andExpect(jsonPath("$.content[0].targetId").value(bankId.toString()))
            .andExpect(jsonPath("$.content[0].targetName").value(name))
            .andExpect(jsonPath("$.content[0].actorName").value("Isatu Bangura"));
    }

    @Test
    void anAdministratorCreatesAHospitalAndItIsListedAndAudited() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        String token = signIn(admin);
        String name = uniqueName("Teko Barracks Hospital");

        MvcResult result = create(token, "/api/admin/hospitals",
                facility(name, "Teko Barracks, Makeni", "+232 76 333 444"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value(name))
            .andReturn();
        UUID hospitalId = idOf(result);

        mockMvc.perform(get("/api/hospitals").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name", hasItem(name)));

        List<AuditLog> entries = auditLogRepository.findByTargetHospitalId(hospitalId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAction()).isEqualTo(AuditAction.HOSPITAL_CREATED);
        assertThat(entries.get(0).getActor().getId()).isEqualTo(admin.getId());

        mockMvc.perform(get("/api/admin/audit-log").header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$.content[0].action").value("HOSPITAL_CREATED"))
            .andExpect(jsonPath("$.content[0].targetType").value("HOSPITAL"))
            .andExpect(jsonPath("$.content[0].targetName").value(name));
    }

    @Test
    void aNewBloodBankShowsAllEightGroupsAtZeroWithNoSeedingStep() throws Exception {
        String token = signIn(seed("System Administrator", Role.ADMIN));
        UUID bankId = idOf(create(token, "/api/admin/blood-banks",
                facility(uniqueName("Magburaka Road Blood Bank"), "Magburaka Road, Makeni", "076 555 666"))
            .andExpect(status().isCreated())
            .andReturn());

        // Nothing was written to blood_inventory for it...
        entityManager.flush();
        Number rows = (Number) entityManager
            .createNativeQuery("select count(*) from blood_inventory where blood_bank_id = :id")
            .setParameter("id", bankId)
            .getSingleResult();
        assertThat(rows.longValue()).isZero();

        // ...and the Inventory screen's endpoint still reports every group, at zero,
        // Critical by Business Rule 3 — the read path fills in what does not exist.
        mockMvc.perform(get("/api/inventory/{id}", bankId).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(8))
            .andExpect(jsonPath("$[*].bloodType").value(containsInAnyOrder(
                "A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-")))
            .andExpect(jsonPath("$[*].unitsAvailable", everyItem(is(0))))
            .andExpect(jsonPath("$[*].status", everyItem(is("CRITICAL"))));

        // The Inventory page's stat cards read the summary endpoint; it works too.
        mockMvc.perform(get("/api/inventory/{id}/summary", bankId).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalUnits").value(0))
            .andExpect(jsonPath("$.criticalGroupsCount").value(8));
    }

    // ---------- duplicate names ----------

    @Test
    void aDuplicateBloodBankNameIsRefusedWith409EvenInDifferentCaseAndSpacing() throws Exception {
        String token = signIn(seed("System Administrator", Role.ADMIN));
        String name = uniqueName("Clock Tower Blood Bank");
        create(token, "/api/admin/blood-banks", facility(name, "Makeni", "076 000 001"))
            .andExpect(status().isCreated());
        long banksBefore = bloodBankRepository.count();

        create(token, "/api/admin/blood-banks", facility(name, "Elsewhere", "076 000 002"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("A blood bank named \"" + name + "\" already exists."));
        // The same name typed differently is still the same facility.
        create(token, "/api/admin/blood-banks",
                facility("  " + name.toUpperCase().replace(" ", "  ") + " ", "Elsewhere", "076 000 003"))
            .andExpect(status().isConflict());

        assertThat(bloodBankRepository.count()).isEqualTo(banksBefore);
    }

    @Test
    void aDuplicateHospitalNameIsRefusedWith409IncludingASeededOne() throws Exception {
        String token = signIn(seed("System Administrator", Role.ADMIN));
        Hospital seeded = hospitalRepository.findAllByOrderByNameAsc().get(0);

        create(token, "/api/admin/hospitals", facility(seeded.getName().toLowerCase(), "Makeni", "076 000 004"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    @Test
    void aHospitalMayShareANameWithABloodBank() throws Exception {
        // Uniqueness is per facility type: "Holy Spirit" really is both.
        String token = signIn(seed("System Administrator", Role.ADMIN));
        BloodBank seededBank = bloodBankRepository.findAllByOrderByNameAsc().get(0);

        create(token, "/api/admin/hospitals", facility(seededBank.getName(), "Makeni", "076 000 005"))
            .andExpect(status().isCreated());
    }

    @Test
    void theDatabaseItselfRefusesADuplicateNameThatSkipsTheService() {
        // V14's index is the real guarantee — proven by going around the service.
        BloodBank seeded = bloodBankRepository.findAllByOrderByNameAsc().get(0);
        BloodBank copy = new BloodBank();
        copy.setName(seeded.getName().toUpperCase());
        copy.setLocation("Makeni");
        copy.setContactPhone("076 000 006");

        assertThatThrownBy(() -> bloodBankRepository.saveAndFlush(copy))
            .hasStackTraceContaining("uq_blood_banks_name");
    }

    // ---------- validation ----------

    @Test
    void everyFieldIsRequired() throws Exception {
        String token = signIn(seed("System Administrator", Role.ADMIN));

        create(token, "/api/admin/hospitals", facility(" ", "", null))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.name").exists())
            .andExpect(jsonPath("$.fieldErrors.location").exists())
            .andExpect(jsonPath("$.fieldErrors.contactPhone").exists());
    }

    // ---------- who may call it ----------

    @Test
    void nonAdministratorTokensAreRefusedWith403AndNothingIsCreated() throws Exception {
        User donor = seed("Aminata Sesay", Role.DONOR);
        User hospitalStaff = seed("Hospital Desk Clerk", Role.HOSPITAL_STAFF);
        hospitalStaff.setHospital(hospitalRepository.findAllByOrderByNameAsc().get(0));
        User bankStaff = seed("Lab Technician", Role.BLOOD_BANK_STAFF);
        bankStaff.setBloodBank(bloodBankRepository.findAllByOrderByNameAsc().get(0));
        long banksBefore = bloodBankRepository.count();
        long hospitalsBefore = hospitalRepository.count();

        for (User caller : List.of(donor, hospitalStaff, bankStaff)) {
            String token = signIn(caller);
            create(token, "/api/admin/blood-banks", facility(uniqueName("Refused Bank"), "Makeni", "076 000 007"))
                .andExpect(status().isForbidden());
            create(token, "/api/admin/hospitals", facility(uniqueName("Refused Hospital"), "Makeni", "076 000 008"))
                .andExpect(status().isForbidden());
        }

        assertThat(bloodBankRepository.count()).isEqualTo(banksBefore);
        assertThat(hospitalRepository.count()).isEqualTo(hospitalsBefore);
    }

    @Test
    void anUnauthenticatedCallerIsRefusedWith401() throws Exception {
        mockMvc.perform(post("/api/admin/blood-banks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(facility("Nobody's Bank", "Makeni", "076 000 009"))))
            .andExpect(status().isUnauthorized());
    }

    // ---------- the audit log's loosened target column is still guarded ----------

    @Test
    void theDatabaseRefusesAnAccountActionWithNoAccount() {
        // V14 made target_user_id nullable for facility entries; its CHECK must stop
        // that being used to write an account action that points at nobody.
        User admin = seed("System Administrator", Role.ADMIN);
        entityManager.flush();

        assertThatThrownBy(() -> entityManager.createNativeQuery(
                "insert into audit_log (actor_user_id, action, details) values (:actor, 'ACCOUNT_CREATED', 'x')")
            .setParameter("actor", admin.getId())
            .executeUpdate())
            .hasStackTraceContaining("audit_log_target_matches_action");
    }

    // ---------- helpers ----------

    private ResultActions create(String token, String path, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(path)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    /** A request body; a HashMap-backed map so a null value can be sent as null. */
    private static Map<String, Object> facility(String name, String location, String contactPhone) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("location", location);
        body.put("contactPhone", contactPhone);
        return body;
    }

    private UUID idOf(MvcResult result) throws Exception {
        // Both facility responses share this shape (id, name, location, contactPhone).
        return objectMapper.readValue(result.getResponse().getContentAsString(), BloodBankResponse.class).id();
    }

    private String signIn(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(user.getPhone(), PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).token();
    }

    private User seed(String fullName, Role role) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail("facility.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        return userRepository.save(user);
    }

    /** A facility name no earlier run can have left behind (they all roll back, but
     *  this keeps a failure in one test from being mistaken for a duplicate in another). */
    private static String uniqueName(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "076" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
