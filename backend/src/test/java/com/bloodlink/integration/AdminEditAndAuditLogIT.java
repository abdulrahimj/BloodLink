package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.bloodlink.admin.dto.CreateStaffAccountPayload;
import com.bloodlink.admin.dto.CreatedAccountResponse;
import com.bloodlink.audit.AuditLogService;
import com.bloodlink.audit.dto.AuditLogEntryResponse;
import com.bloodlink.audit.dto.AuditLogPageResponse;
import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
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
 * Business Rule 17 end to end: an administrator editing another account's phone and
 * facility, and the append-only audit log that records that and every other
 * administrator action — through the real endpoints, filter chain and database.
 *
 * <h2>"No way to modify or delete an entry" — proven three ways</h2>
 * <ol>
 *   <li><b>Over HTTP:</b> every modifying method against the audit log's URLs is
 *       refused, and an entry is unchanged afterwards.</li>
 *   <li><b>In the route table:</b> the application's full list of mapped endpoints
 *       is read back from Spring MVC and checked — no route anywhere that touches
 *       the audit log answers anything but GET. Unlike a list of URLs to try, this
 *       also catches an endpoint added later under a path nobody thought to test.</li>
 *   <li><b>At the database:</b> an UPDATE, a DELETE and a TRUNCATE issued directly
 *       in SQL all fail, courtesy of V12's triggers — so even code that bypassed
 *       every endpoint could not do it.</li>
 * </ol>
 * Each database test is its own method because a refused statement aborts the
 * PostgreSQL transaction it ran in; nothing can follow it in the same method.
 *
 * Shares bloodlink_test with the other integration tests and the same guarantee:
 * {@code @Transactional}, so every row written here — audit entries included — is
 * rolled back. (A rollback is not a DELETE: the triggers do not fire, and nothing
 * is left behind.) Run with:
 * <pre>{@code .\mvnw.cmd test -Dtest=AdminEditAndAuditLogIT}</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminEditAndAuditLogIT {

    private static final String PASSWORD = "correct-horse-battery-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AuditLogService auditLogService;
    @Autowired private EntityManager entityManager;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;

    // ---------- editing another account ----------

    @Test
    void anAdministratorEditsAnotherAccountsPhoneAndTheNewNumberSignsIn() throws Exception {
        User admin = seed("System Administrator", Role.ADMIN);
        User staff = seed("Lab Technician", Role.BLOOD_BANK_STAFF);
        staff.setBloodBank(bank(0));
        String oldPhone = staff.getPhone();
        String typed = uniqueLocalPhone();

        editDetails(signIn(admin), staff.getId(), Map.of("phone", typed, "bloodBankId", bank(0).getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phone").value(PhoneNumber.normalize(typed)));

        // The proof that matters: the new number is now the login, the old one is not.
        login(typed, PASSWORD).andExpect(status().isOk());
        login(oldPhone, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void aPhoneAlreadyRegisteredIsRefusedJustAsAtRegistration() throws Exception {
        User admin = seed("System Administrator", Role.ADMIN);
        User holder = seed("Existing Holder", Role.ADMIN);
        User target = seed("Target Account", Role.ADMIN);
        long entriesBefore = auditLogRepository.count();

        // The holder's number, typed the other accepted way (Business Rule 13).
        String sameNumberLocal = "0" + holder.getPhone().substring(4);

        editDetails(signIn(admin), target.getId(), Map.of("phone", sameNumberLocal))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("An account already exists for this phone number."));

        assertThat(userRepository.findById(target.getId()).orElseThrow().getPhone()).isNotEqualTo(holder.getPhone());
        assertThat(auditLogRepository.count()).isEqualTo(entriesBefore);
    }

    @Test
    void theFacilityRuleFollowsTheAccountsExistingRole() throws Exception {
        User admin = seed("System Administrator", Role.ADMIN);
        String token = signIn(admin);
        User hospitalStaff = seed("Hospital Desk Clerk", Role.HOSPITAL_STAFF);
        hospitalStaff.setHospital(hospital(0));
        User anotherAdmin = seed("Another Administrator", Role.ADMIN);

        // Staff must keep one...
        editDetails(token, hospitalStaff.getId(), Map.of("phone", hospitalStaff.getPhone()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.hospitalId").exists());
        // ...and an administrator may not be given one.
        editDetails(token, anotherAdmin.getId(), Map.of("phone", anotherAdmin.getPhone(), "bloodBankId", bank(0).getId()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.bloodBankId").exists());
        // And a move to the other hospital works.
        editDetails(token, hospitalStaff.getId(), Map.of("phone", hospitalStaff.getPhone(), "hospitalId", hospital(1).getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hospitalName").value(hospital(1).getName()));
    }

    @Test
    void roleCannotBeChangedByAnEdit() throws Exception {
        User admin = seed("System Administrator", Role.ADMIN);
        User target = seed("Target Account", Role.ADMIN);

        editDetails(signIn(admin), target.getId(), Map.of("phone", target.getPhone(), "role", "DONOR"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.role").exists());

        assertThat(userRepository.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void anAdministratorCannotEditTheirOwnPhoneThroughThisEndpoint() throws Exception {
        User admin = seed("System Administrator", Role.ADMIN);

        editDetails(signIn(admin), admin.getId(), Map.of("phone", uniqueLocalPhone()))
            .andExpect(status().isConflict());
    }

    // ---------- only administrators ----------

    @Test
    void nonAdministratorTokensAreRefusedForEditingAndForTheLog() throws Exception {
        User target = seed("Target Account", Role.ADMIN);
        User hospitalStaff = seed("Hospital Desk Clerk", Role.HOSPITAL_STAFF);
        hospitalStaff.setHospital(hospital(0));
        User bankStaff = seed("Lab Technician", Role.BLOOD_BANK_STAFF);
        bankStaff.setBloodBank(bank(0));
        User donor = seed("Aminata Sesay", Role.DONOR);
        String targetPhoneBefore = target.getPhone();

        for (User caller : List.of(donor, hospitalStaff, bankStaff)) {
            String token = signIn(caller);
            editDetails(token, target.getId(), Map.of("phone", uniqueLocalPhone()))
                .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/audit-log").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        }

        assertThat(userRepository.findById(target.getId()).orElseThrow().getPhone()).isEqualTo(targetPhoneBefore);
    }

    // ---------- one audit entry per action, with the right actor and target ----------

    @Test
    void everyAdministratorActionIsRecordedWithTheRightActorAndTarget() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        String token = signIn(admin);

        // 1. Create — through the real endpoint.
        MvcResult createdResult = mockMvc.perform(post("/api/admin/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateStaffAccountPayload(
                    "Mohamed Kamara", uniqueLocalPhone(), uniqueEmail(), Role.BLOOD_BANK_STAFF, bank(0).getId(), null))))
            .andExpect(status().isCreated())
            .andReturn();
        CreatedAccountResponse created = objectMapper.readValue(
            createdResult.getResponse().getContentAsString(), CreatedAccountResponse.class);
        UUID targetId = created.account().id();

        // 2. Edit — move them to the other bank.
        editDetails(token, targetId, Map.of("phone", created.account().phone(), "bloodBankId", bank(1).getId()))
            .andExpect(status().isOk());
        // 3. Suspend, 4. reactivate.
        setStatus(token, targetId, "SUSPENDED").andExpect(status().isOk());
        setStatus(token, targetId, "ACTIVE").andExpect(status().isOk());
        // A no-op (already ACTIVE) must not add a fifth entry.
        setStatus(token, targetId, "ACTIVE").andExpect(status().isOk());

        List<AuditLog> entries = auditLogRepository.findByTargetIdOldestFirst(targetId);
        assertThat(entries).extracting(AuditLog::getAction).containsExactly(
            AuditAction.ACCOUNT_CREATED, AuditAction.ACCOUNT_EDITED,
            AuditAction.ACCOUNT_SUSPENDED, AuditAction.ACCOUNT_REACTIVATED);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.getActor().getId()).isEqualTo(admin.getId());
            assertThat(entry.getTarget().getId()).isEqualTo(targetId);
            // The one-time password never reaches the log.
            assertThat(entry.getDetails()).doesNotContain(created.temporaryPassword());
        });
        assertThat(entries.get(1).getDetails())
            .isEqualTo("Blood bank changed from " + bank(0).getName() + " to " + bank(1).getName() + ".");

        // And the Audit Log page's endpoint shows them, newest first, with names,
        // inside the paged envelope (Business Rule 17's pagination).
        mockMvc.perform(get("/api/admin/audit-log").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].action").value("ACCOUNT_REACTIVATED"))
            .andExpect(jsonPath("$.content[0].actorName").value("Isatu Bangura"))
            .andExpect(jsonPath("$.content[0].targetName").value("Mohamed Kamara"))
            .andExpect(jsonPath("$.content[3].action").value("ACCOUNT_CREATED"));
    }

    // ---------- pagination (Business Rule 17) ----------

    /**
     * More entries than one page holds, created directly through the
     * {@code AuditLogService} bean rather than 25 real HTTP round trips — faster,
     * and the HTTP path for writing one entry is already proven above. Checks the
     * three positions that matter: the first page, the last (partial) page, and a
     * page past the end, plus the totals every page carries.
     */
    @Test
    void pagingSplitsMoreEntriesThanOnePageHoldsAcrossFirstLastAndPastTheEnd() throws Exception {
        User admin = seed("System Administrator", Role.ADMIN);
        User target = seed("Someone With Many Entries", Role.ADMIN);
        String token = signIn(admin);

        for (int i = 0; i < 25; i++) {
            auditLogService.record(admin.getId(), AuditAction.ACCOUNT_EDITED, target, "Edit number " + i + ".");
        }
        entityManager.flush();

        // First page: exactly `size` entries, size 10 of a total of 25 -> 3 pages.
        mockMvc.perform(get("/api/admin/audit-log").param("size", "10").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(10))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(10))
            .andExpect(jsonPath("$.totalElements").value(25))
            .andExpect(jsonPath("$.totalPages").value(3));

        // Last page: the remainder, 25 - 20 = 5.
        MvcResult lastPageResult = mockMvc.perform(get("/api/admin/audit-log")
                .param("page", "2").param("size", "10")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(5))
            .andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.totalElements").value(25))
            .andExpect(jsonPath("$.totalPages").value(3))
            .andReturn();

        // A page past the end: empty content, not an error, same totals.
        mockMvc.perform(get("/api/admin/audit-log")
                .param("page", "9").param("size", "10")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(25))
            .andExpect(jsonPath("$.totalPages").value(3));

        // The three pages together cover every entry exactly once — proof that
        // LIMIT/OFFSET paging and the newest-first tie-breaker never duplicate or
        // drop a row between pages.
        AuditLogPageResponse firstPage = objectMapper.readValue(
            mockMvc.perform(get("/api/admin/audit-log").param("size", "10")
                    .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(),
            AuditLogPageResponse.class);
        AuditLogPageResponse secondPage = objectMapper.readValue(
            mockMvc.perform(get("/api/admin/audit-log").param("page", "1").param("size", "10")
                    .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(),
            AuditLogPageResponse.class);
        AuditLogPageResponse thirdPage = objectMapper.readValue(
            lastPageResult.getResponse().getContentAsString(), AuditLogPageResponse.class);

        Set<UUID> allIds = new HashSet<>();
        for (AuditLogPageResponse page : List.of(firstPage, secondPage, thirdPage)) {
            for (AuditLogEntryResponse entry : page.content()) {
                allIds.add(entry.id());
            }
        }
        assertThat(allIds).hasSize(25);
    }

    // ---------- nothing can modify or delete an entry ----------

    @Test
    void everyModifyingHttpMethodOnTheAuditLogIsRefused() throws Exception {
        User admin = seed("System Administrator", Role.ADMIN);
        String token = signIn(admin);
        AuditLog entry = recordOneEntry(token);
        String bearer = "Bearer " + token;

        // The collection: only GET exists, so every other method is 405.
        mockMvc.perform(post("/api/admin/audit-log").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/admin/audit-log").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch("/api/admin/audit-log").header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/admin/audit-log").header("Authorization", bearer))
            .andExpect(status().isMethodNotAllowed());

        // A single entry: there is no route for one at all.
        String one = "/api/admin/audit-log/" + entry.getId();
        mockMvc.perform(put(one).header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"details\":\"rewritten\"}")).andExpect(status().isNotFound());
        mockMvc.perform(patch(one).header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"details\":\"rewritten\"}")).andExpect(status().isNotFound());
        mockMvc.perform(delete(one).header("Authorization", bearer)).andExpect(status().isNotFound());

        entityManager.clear();
        AuditLog after = auditLogRepository.findByTargetIdOldestFirst(entry.getTarget().getId()).get(0);
        assertThat(after.getDetails()).isEqualTo(entry.getDetails());
    }

    @Test
    void noRouteInTheWholeApplicationCanModifyTheAuditLog() {
        // Read back from Spring MVC itself: every mapped endpoint, every controller.
        // Any route touching the audit log — by path, or by being declared on its
        // controller — must answer GET and nothing else.
        Map<RequestMappingInfo, HandlerMethod> routes = handlerMapping.getHandlerMethods();
        assertThat(routes).isNotEmpty();

        List<String> offending = routes.entrySet().stream()
            .filter(route -> route.getKey().getPatternValues().stream().anyMatch(p -> p.contains("audit"))
                || route.getValue().getBeanType().getSimpleName().contains("AuditLog"))
            .filter(route -> !route.getKey().getMethodsCondition().getMethods().equals(Set.of(RequestMethod.GET)))
            .map(route -> route.getKey() + " -> " + route.getValue())
            .toList();

        assertThat(offending).as("routes that could modify or delete audit entries").isEmpty();

        // And the GET does exist — the check above is not passing on an empty set.
        assertThat(routes.keySet()).anyMatch(info -> info.getPatternValues().contains("/api/admin/audit-log"));
    }

    @Test
    void theDatabaseRefusesAnUpdateToAnEntry() throws Exception {
        recordOneEntry(signIn(seed("System Administrator", Role.ADMIN)));

        assertThatThrownBy(() -> entityManager.createNativeQuery("update audit_log set details = 'rewritten'").executeUpdate())
            .hasStackTraceContaining("audit_log is append-only: UPDATE is not permitted");
    }

    @Test
    void theDatabaseRefusesADeleteOfAnEntry() throws Exception {
        recordOneEntry(signIn(seed("System Administrator", Role.ADMIN)));

        assertThatThrownBy(() -> entityManager.createNativeQuery("delete from audit_log").executeUpdate())
            .hasStackTraceContaining("audit_log is append-only: DELETE is not permitted");
    }

    @Test
    void theDatabaseRefusesATruncate() throws Exception {
        recordOneEntry(signIn(seed("System Administrator", Role.ADMIN)));

        assertThatThrownBy(() -> entityManager.createNativeQuery("truncate audit_log").executeUpdate())
            .hasStackTraceContaining("audit_log is append-only: TRUNCATE is not permitted");
    }

    // ---------- helpers ----------

    /** Suspends a freshly seeded account, which writes exactly one entry, and
     *  returns it — flushed to the database so SQL against the table can see it. */
    private AuditLog recordOneEntry(String adminToken) throws Exception {
        User target = seed("Someone To Suspend", Role.ADMIN);
        setStatus(adminToken, target.getId(), "SUSPENDED").andExpect(status().isOk());
        entityManager.flush();
        return auditLogRepository.findByTargetIdOldestFirst(target.getId()).get(0);
    }

    private ResultActions editDetails(String token, UUID userId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(patch("/api/admin/users/{id}/details", userId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions setStatus(String token, UUID userId, String status) throws Exception {
        return mockMvc.perform(patch("/api/admin/users/{id}", userId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions login(String phone, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new LoginRequest(phone, password))));
    }

    private String signIn(User user) throws Exception {
        MvcResult result = login(user.getPhone(), PASSWORD).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class).token();
    }

    private User seed(String fullName, Role role) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail(uniqueEmail());
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        return userRepository.save(user);
    }

    private Hospital hospital(int index) {
        return hospitalRepository.findAllByOrderByNameAsc().get(index);
    }

    private BloodBank bank(int index) {
        return bloodBankRepository.findAllByOrderByNameAsc().get(index);
    }

    private String uniqueEmail() {
        return "audit.it." + UUID.randomUUID() + "@example.com";
    }

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "076" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
