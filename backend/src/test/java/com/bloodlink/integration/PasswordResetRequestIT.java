package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.PasswordResetRequest;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.PasswordResetRequestStatus;
import com.bloodlink.domain.enums.PasswordResetResolution;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.AuditLogRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.JwtService;

import jakarta.persistence.EntityManager;

/**
 * Business Rule 33 end to end through the real filter chain: the public "Forgot password?" form and the administrators'
 * Password requests page. Rolled back like every bloodlink_test consumer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PasswordResetRequestIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";
    private static final String ANSWER = "{\"message\":\"If this number is registered, an administrator will contact you.\"}";
    /** application-test.properties' minimum answer time. */
    private static final long MIN_MILLIS = 150;

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JwtService jwtService;

    // ---------- the public form ----------

    @Test
    void unknownSuspendedAndActiveNumbersAllGetTheIdenticalAnswerWithoutSigningIn() throws Exception {
        User suspended = seed("Aminata Sesay", Role.DONOR);
        suspended.setStatus(AccountStatus.SUSPENDED);
        userRepository.save(suspended);
        User active = seed("Fatmata Koroma", Role.DONOR);

        // 079 numbers are never seeded here (seed uses 077), so this one belongs to nobody.
        String unknownPhone = "+23279" + String.format("%06d", (PHONE_RUN_BASE + 400_000) % 1_000_000);
        for (String phone : List.of(unknownPhone, suspended.getPhone(), active.getPhone())) {
            long started = System.nanoTime();
            MvcResult result = ask(phone)
                .andExpect(status().isAccepted())
                .andReturn();
            long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(result.getResponse().getContentAsString()).isEqualTo(ANSWER);
            assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
            assertThat(tookMillis).as("answer time for " + phone).isGreaterThanOrEqualTo(MIN_MILLIS);
        }
    }

    @Test
    void anUnknownOrSuspendedNumberSavesNothingAndAlertsNobody() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        User suspended = seed("Aminata Sesay", Role.HOSPITAL_STAFF);
        suspended.setStatus(AccountStatus.SUSPENDED);
        userRepository.save(suspended);
        long requestsBefore = allRequests();

        ask("+23279" + String.format("%06d", (PHONE_RUN_BASE + 500_000) % 1_000_000)).andExpect(status().isAccepted());
        ask(suspended.getPhone()).andExpect(status().isAccepted());

        assertThat(allRequests()).isEqualTo(requestsBefore);
        assertThat(alerts(admin)).isEmpty();
    }

    @Test
    void anActiveAccountCreatesOneWaitingRequestAndAlertsActiveAdministratorsOnly() throws Exception {
        User activeAdmin = seed("Isatu Bangura", Role.ADMIN);
        User suspendedAdmin = seed("Abu Conteh", Role.ADMIN);
        suspendedAdmin.setStatus(AccountStatus.SUSPENDED);
        userRepository.save(suspendedAdmin);
        User requestingAdmin = seed("Mariama Kamara", Role.ADMIN);
        User donor = seed("Fatmata Koroma", Role.DONOR);

        // Typed in local format; matched against the stored +232 form, as sign-in does.
        ask("0" + donor.getPhone().substring(4)).andExpect(status().isAccepted());
        ask(requestingAdmin.getPhone()).andExpect(status().isAccepted());

        assertThat(requestsFor(donor)).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(PasswordResetRequestStatus.WAITING);
            assertThat(r.getResolution()).isNull();
            assertThat(r.getHandledBy()).isNull();
        });
        assertThat(alerts(activeAdmin)).extracting(Notification::getMessage).containsExactlyInAnyOrder(
            "Password reset requested for " + donor.getPhone() + " (Fatmata Koroma). Call back the registered number.",
            "Password reset requested for " + requestingAdmin.getPhone() + " (Mariama Kamara). Call back the registered number.");
        assertThat(alerts(suspendedAdmin)).isEmpty();
        // The administrator who asked is told about the donor, never about their own request.
        assertThat(alerts(requestingAdmin)).extracting(Notification::getMessage)
            .singleElement().asString().contains("Fatmata Koroma");
    }

    @Test
    void anAccountThatMustChangeItsPasswordMayAskToo() throws Exception {
        User staff = seed("Mohamed Kamara", Role.BLOOD_BANK_STAFF);
        staff.setMustChangePassword(true);
        userRepository.save(staff);

        ask(staff.getPhone()).andExpect(status().isAccepted());

        assertThat(requestsFor(staff)).hasSize(1);
    }

    @Test
    void askingAgainWithinAnHourAddsNothing() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        User donor = seed("Fatmata Koroma", Role.DONOR);

        ask(donor.getPhone()).andExpect(status().isAccepted());
        Instant firstAsked = requestsFor(donor).get(0).getRequestedAt();
        ask(donor.getPhone()).andExpect(status().isAccepted());

        assertThat(requestsFor(donor)).singleElement()
            .satisfies(r -> assertThat(r.getRequestedAt()).isEqualTo(firstAsked));
        assertThat(alerts(admin)).hasSize(1);
    }

    @Test
    void askingAgainAnHourOrMoreLaterReAlertsAndMovesTheTime() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        User donor = seed("Fatmata Koroma", Role.DONOR);
        ask(donor.getPhone()).andExpect(status().isAccepted());
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(61));
        entityManager.createQuery("update PasswordResetRequest r set r.requestedAt = :at where r.id = :id")
            .setParameter("at", longAgo).setParameter("id", requestsFor(donor).get(0).getId()).executeUpdate();
        // The bulk update bypasses the session, so its cached copy of the row is dropped.
        entityManager.clear();

        ask(donor.getPhone()).andExpect(status().isAccepted());

        assertThat(requestsFor(donor)).singleElement()
            .satisfies(r -> assertThat(r.getRequestedAt()).isAfter(longAgo.plus(Duration.ofMinutes(60))));
        assertThat(alerts(admin)).hasSize(2);
    }

    @Test
    void aMalformedNumberIsTheSame400AsSignIn() throws Exception {
        ask("07690000").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.phone").exists());
    }

    // ---------- closing a request ----------

    @Test
    void aResetFromManageClosesTheWaitingRequestAsResetByThatAdministrator() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        User donor = seed("Fatmata Koroma", Role.DONOR);
        ask(donor.getPhone()).andExpect(status().isAccepted());

        mockMvc.perform(post("/api/admin/users/{id}/password-reset", donor.getId()).header("Authorization", bearer(admin)))
            .andExpect(status().isOk());

        assertThat(requestsFor(donor)).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(PasswordResetRequestStatus.DONE);
            assertThat(r.getResolution()).isEqualTo(PasswordResetResolution.RESET);
            assertThat(r.getHandledBy().getId()).isEqualTo(admin.getId());
            assertThat(r.getHandledAt()).isNotNull();
            assertThat(r.getNote()).isNull();
        });
        assertThat(audit(donor, AuditAction.ADMIN_PASSWORD_RESET)).singleElement()
            .satisfies(e -> assertThat(e.getDetails()).contains("waiting password reset request is closed"));
        count(admin).andExpect(jsonPath("$.waiting").value(0));
    }

    @Test
    void markDoneClosesItAsClosedWithTheNoteAndIsAudited() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        User donor = seed("Fatmata Koroma", Role.DONOR);
        ask(donor.getPhone()).andExpect(status().isAccepted());
        UUID requestId = requestsFor(donor).get(0).getId();

        close(admin, requestId, "{\"note\": \"  Called back; she remembered it.  \"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DONE"))
            .andExpect(jsonPath("$.resolution").value("CLOSED"))
            .andExpect(jsonPath("$.handledByName").value("Isatu Bangura"))
            .andExpect(jsonPath("$.note").value("Called back; she remembered it."));

        assertThat(audit(donor, AuditAction.PASSWORD_REQUEST_CLOSED)).singleElement().satisfies(e -> {
            assertThat(e.getActor().getId()).isEqualTo(admin.getId());
            assertThat(e.getDetails()).isEqualTo(
                "Password reset request closed without a reset. Note: Called back; she remembered it.");
        });
        // Done is final: a second Mark done is a 409; an unknown id a 404.
        close(admin, requestId, "{}").andExpect(status().isConflict());
        close(admin, UUID.randomUUID(), "{}").andExpect(status().isNotFound());
    }

    @Test
    void theNoteIsOptionalAndAtMost200Characters() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        User first = seed("Fatmata Koroma", Role.DONOR);
        User second = seed("Aminata Sesay", Role.DONOR);
        ask(first.getPhone()).andExpect(status().isAccepted());
        ask(second.getPhone()).andExpect(status().isAccepted());

        close(admin, requestsFor(first).get(0).getId(), "{\"note\": \"" + "x".repeat(201) + "\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.note").exists());
        // No body at all, and a blank note, both close it with no note.
        mockMvc.perform(post("/api/admin/password-reset-requests/{id}/close", requestsFor(first).get(0).getId())
                .header("Authorization", bearer(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.note").doesNotExist());
        close(admin, requestsFor(second).get(0).getId(), "{\"note\": \"   \"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.note").doesNotExist());
    }

    // ---------- the administrators' list ----------

    @Test
    void theListShowsWaitingByDefaultAndDoneWithWhoHandledIt() throws Exception {
        User admin = seed("Isatu Bangura", Role.ADMIN);
        User waiting = seed("Fatmata Koroma", Role.HOSPITAL_STAFF);
        User closed = seed("Aminata Sesay", Role.DONOR);
        ask(waiting.getPhone()).andExpect(status().isAccepted());
        ask(closed.getPhone()).andExpect(status().isAccepted());
        close(admin, requestsFor(closed).get(0).getId(), "{\"note\": \"Wrong number\"}").andExpect(status().isOk());

        String waitingPage = mockMvc.perform(get("/api/admin/password-reset-requests?size=100")
                .header("Authorization", bearer(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andReturn().getResponse().getContentAsString();
        assertThat(waitingPage).contains(waiting.getPhone(), "\"role\":\"HOSPITAL_STAFF\"", "Fatmata Koroma")
            .doesNotContain(closed.getPhone());

        String donePage = mockMvc.perform(get("/api/admin/password-reset-requests?status=DONE&size=100")
                .header("Authorization", bearer(admin)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(donePage).contains(closed.getPhone(), "\"resolution\":\"CLOSED\"", "\"handledByName\":\"Isatu Bangura\"",
            "Wrong number");

        count(admin).andExpect(jsonPath("$.waiting").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mockMvc.perform(get("/api/admin/password-reset-requests?status=OPEN").header("Authorization", bearer(admin)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAnAdministratorReachesThePasswordRequestsEndpoints() throws Exception {
        User donor = seed("Fatmata Koroma", Role.DONOR);
        ask(donor.getPhone()).andExpect(status().isAccepted());
        UUID requestId = requestsFor(donor).get(0).getId();

        for (Role role : List.of(Role.DONOR, Role.HOSPITAL_STAFF, Role.BLOOD_BANK_STAFF)) {
            User caller = seed("Not An Admin", role);
            mockMvc.perform(get("/api/admin/password-reset-requests").header("Authorization", bearer(caller)))
                .andExpect(status().isForbidden());
            count(caller).andExpect(status().isForbidden());
            close(caller, requestId, "{}").andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/admin/password-reset-requests")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/password-reset-requests/count")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/password-reset-requests/{id}/close", requestId)).andExpect(status().isUnauthorized());
        assertThat(requestsFor(donor)).singleElement().satisfies(r -> assertThat(r.isWaiting()).isTrue());
    }

    @Test
    void theSupportContactIsPublicAndEmptyByDefault() throws Exception {
        mockMvc.perform(get("/api/auth/support-contact"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phone").doesNotExist());
    }

    // ---------- helpers ----------

    private ResultActions ask(String phone) throws Exception {
        return mockMvc.perform(post("/api/auth/password-reset-requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phone\": \"" + phone + "\"}"));
    }

    private ResultActions close(User admin, UUID requestId, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/password-reset-requests/{id}/close", requestId)
            .header("Authorization", bearer(admin))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private ResultActions count(User caller) throws Exception {
        return mockMvc.perform(get("/api/admin/password-reset-requests/count").header("Authorization", bearer(caller)));
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    private List<PasswordResetRequest> requestsFor(User user) {
        entityManager.flush();
        entityManager.clear();
        return entityManager.createQuery(
                "select r from PasswordResetRequest r left join fetch r.handledBy where r.user.id = :id",
                PasswordResetRequest.class)
            .setParameter("id", user.getId())
            .getResultList();
    }

    private long allRequests() {
        entityManager.flush();
        return entityManager.createQuery("select count(r) from PasswordResetRequest r", Long.class).getSingleResult();
    }

    private List<Notification> alerts(User admin) {
        entityManager.flush();
        return entityManager.createQuery(
                "select n from Notification n where n.recipient.id = :id and n.type = :type", Notification.class)
            .setParameter("id", admin.getId())
            .setParameter("type", NotificationType.PASSWORD_RESET_REQUESTED)
            .getResultList();
    }

    private List<AuditLog> audit(User target, AuditAction action) {
        entityManager.flush();
        return auditLogRepository.findByTargetIdOldestFirst(target.getId()).stream()
            .filter(entry -> entry.getAction() == action)
            .toList();
    }

    /** An ACTIVE account with PASSWORD; staff get the first hospital or blood bank, as their role requires. */
    private User seed(String fullName, Role role) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail("reset.request.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(AccountStatus.ACTIVE);
        if (role == Role.HOSPITAL_STAFF) user.setHospital(hospitalRepository.findAllByOrderByNameAsc().get(0));
        if (role == Role.BLOOD_BANK_STAFF) user.setBloodBank(bloodBankRepository.findAllByOrderByNameAsc().get(0));
        return userRepository.save(user);
    }

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "077" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
