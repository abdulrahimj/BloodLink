package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.AuditLogRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.JwtService;

import jakarta.persistence.EntityManager;
// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.ObjectMapper;

/**
 * Business Rule 34 through the real filter chain, on the real numbers (the test profile sets them out of reach, so they
 * are named here). Counters outlive a test, so each test uses its own numbers and addresses. Rolled back as usual.
 */
@SpringBootTest(properties = {
    "bloodlink.rate-limit.sign-in.phone.max-failures=3",
    "bloodlink.rate-limit.sign-in.address.max-failures=30",
    "bloodlink.rate-limit.password-reset.max-requests=10",
    "bloodlink.rate-limit.registration.max-accounts=10"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BatchStockCutoverIT.MutableClockConfig.class)
@Transactional
class RateLimitIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";
    private static final String WRONG = "Wrong-horse-battery-2026";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JwtService jwtService;
    @Autowired private BatchStockCutoverIT.MutableClock clock;

    // ---------- Rule 1: per phone number ----------

    @Test
    void theThirdFailureLocksAndEvenTheCorrectPasswordIsThenRefused() throws Exception {
        User donor = seed(Role.DONOR);
        String address = uniqueAddress();

        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());

        login(donor.getPhone(), PASSWORD, address)
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "900"))
            .andExpect(jsonPath("$.message").value("Too many attempts. Please wait 15 minutes and try again."));
    }

    /** The site and the API are on different addresses, so the browser hides the wait unless it is exposed. */
    @Test
    void aPageOnAnotherAddressMayReadTheWait() throws Exception {
        User donor = seed(Role.DONOR);
        String address = uniqueAddress();
        lock(donor.getPhone(), address);

        mockMvc.perform(from(address, post("/api/auth/login"))
                .header("Origin", "http://localhost:5173")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(donor.getPhone(), PASSWORD))))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
            .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));
    }

    @Test
    void theLockHoldsFromEveryAddress() throws Exception {
        User donor = seed(Role.DONOR);
        lock(donor.getPhone(), uniqueAddress());

        login(donor.getPhone(), PASSWORD, uniqueAddress()).andExpect(status().isTooManyRequests());
    }

    @Test
    void theLockLiftsAfterFifteenMinutesAndTheMessageCountsDown() throws Exception {
        User donor = seed(Role.DONOR);
        String address = uniqueAddress();
        lock(donor.getPhone(), address);

        clock.advance(Duration.ofMinutes(14));
        login(donor.getPhone(), PASSWORD, address)
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "60"))
            .andExpect(jsonPath("$.message").value("Too many attempts. Please wait 1 minute and try again."));

        clock.advance(Duration.ofMinutes(1));
        login(donor.getPhone(), PASSWORD, address).andExpect(status().isOk());
    }

    @Test
    void aSuccessfulSignInResetsTheCount() throws Exception {
        User donor = seed(Role.DONOR);
        String address = uniqueAddress();

        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), PASSWORD, address).andExpect(status().isOk());
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());

        login(donor.getPhone(), PASSWORD, address).andExpect(status().isOk());
    }

    @Test
    void failuresOlderThanFifteenMinutesNoLongerCount() throws Exception {
        User donor = seed(Role.DONOR);
        String address = uniqueAddress();
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());

        clock.advance(Duration.ofMinutes(15));
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());

        login(donor.getPhone(), PASSWORD, address).andExpect(status().isOk());
    }

    @Test
    void anUnregisteredNumberBehavesExactlyLikeARegisteredOne() throws Exception {
        String registered = seed(Role.DONOR).getPhone();
        String unregistered = uniquePhone();

        for (int attempt = 1; attempt <= 5; attempt++) {
            var known = login(registered, WRONG, uniqueAddress()).andReturn().getResponse();
            var unknown = login(unregistered, WRONG, uniqueAddress()).andReturn().getResponse();

            assertThat(unknown.getStatus()).as("attempt %d", attempt).isEqualTo(known.getStatus());
            assertThat(unknown.getHeader("Retry-After")).isEqualTo(known.getHeader("Retry-After"));
            assertThat(withoutTimestamp(unknown.getContentAsString())).isEqualTo(withoutTimestamp(known.getContentAsString()));
        }
    }

    @Test
    void theSameNumberTypedTwoWaysIsOneCount() throws Exception {
        String local = "0" + uniquePhone().substring(4);
        String international = "+232 " + local.substring(1);
        User donor = seed(Role.DONOR, com.bloodlink.common.PhoneNumber.normalize(local));
        String address = uniqueAddress();

        login(local, WRONG, address).andExpect(status().isUnauthorized());
        login(international, WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());

        login(local, PASSWORD, address).andExpect(status().isTooManyRequests());
    }

    @Test
    void aMalformedNumberIsStillThe400AndCountsAsNothing() throws Exception {
        String address = uniqueAddress();

        for (int i = 0; i < 40; i++) {
            login("07690000", WRONG, address)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.phone").exists());
        }

        login(seed(Role.DONOR).getPhone(), PASSWORD, address).andExpect(status().isOk());
    }

    @Test
    void aSuspendedAccountWithTheRightPasswordIsNotAFailedAttempt() throws Exception {
        User donor = seed(Role.DONOR);
        donor.setStatus(AccountStatus.SUSPENDED);
        userRepository.saveAndFlush(donor);
        String address = uniqueAddress();

        for (int i = 0; i < 5; i++) {
            login(donor.getPhone(), PASSWORD, address).andExpect(status().isForbidden());
        }
    }

    // ---------- Rule 2: per internet address ----------

    @Test
    void thirtyFailuresFromOneAddressStopItWhileAnotherStillWorks() throws Exception {
        User donor = seed(Role.DONOR);
        String crowded = uniqueAddress();
        // Two each, so no number locks and only the address limit is being tested.
        for (int i = 0; i < 15; i++) {
            String phone = uniquePhone();
            login(phone, WRONG, crowded).andExpect(status().isUnauthorized());
            login(phone, WRONG, crowded).andExpect(status().isUnauthorized());
        }

        login(donor.getPhone(), PASSWORD, crowded)
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "900"));

        login(donor.getPhone(), PASSWORD, uniqueAddress()).andExpect(status().isOk());
        assertThat(lockEntriesFor(donor.getPhone())).as("no audit entry for an address limit").isEmpty();

        clock.advance(Duration.ofMinutes(15));
        login(donor.getPhone(), PASSWORD, crowded).andExpect(status().isOk());
    }

    // ---------- Rule 3: "Forgot password?" per address ----------

    @Test
    void theEleventhForgotPasswordRequestInAnHourIsRefused() throws Exception {
        String address = uniqueAddress();
        for (int i = 0; i < 10; i++) {
            forgot(uniquePhone(), address).andExpect(status().isAccepted());
        }

        forgot(uniquePhone(), address)
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "3600"))
            .andExpect(jsonPath("$.message").value("Too many attempts. Please wait 60 minutes and try again."));

        forgot(uniquePhone(), uniqueAddress()).andExpect(status().isAccepted());

        clock.advance(Duration.ofHours(1));
        forgot(uniquePhone(), address).andExpect(status().isAccepted());
    }

    @Test
    void aFakedForwardedHeaderIsIgnoredUnlessTrusted() throws Exception {
        String address = uniqueAddress();
        for (int i = 0; i < 10; i++) {
            forgot(uniquePhone(), address, "8.8.8." + i).andExpect(status().isAccepted());
        }

        forgot(uniquePhone(), address, "9.9.9.9").andExpect(status().isTooManyRequests());
    }

    // ---------- Rule 4: registration per address ----------

    @Test
    void theEleventhNewAccountFromOneAddressIsRefusedAndAClashIsNotCounted() throws Exception {
        String address = uniqueAddress();
        String existing = seed(Role.DONOR).getPhone();

        register(existing, address).andExpect(status().isConflict());
        for (int i = 0; i < 10; i++) {
            register(uniquePhone(), address).andExpect(status().isCreated());
        }

        String next = uniquePhone();
        register(next, address)
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "3600"));
        assertThat(userRepository.existsByPhone(next)).isFalse();

        register(next, uniqueAddress()).andExpect(status().isCreated());
    }

    // ---------- Rule 6: an administrator's reset ----------

    @Test
    void anAdministratorsResetClearsTheLock() throws Exception {
        User donor = seed(Role.DONOR);
        String address = uniqueAddress();
        lock(donor.getPhone(), address);

        String body = mockMvc.perform(post("/api/admin/users/{id}/password-reset", donor.getId())
                .header("Authorization", "Bearer " + jwtService.issueToken(seed(Role.ADMIN))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String temporary = objectMapper.readTree(body).get("temporaryPassword").asString();

        login(donor.getPhone(), temporary, address)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.mustChangePassword").value(true));
    }

    @Test
    void anAdministratorsResetClearsTheCountToo() throws Exception {
        User donor = seed(Role.DONOR);
        String address = uniqueAddress();
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/users/{id}/password-reset", donor.getId())
                .header("Authorization", "Bearer " + jwtService.issueToken(seed(Role.ADMIN))))
            .andExpect(status().isOk());

        // Two more would have locked it had the count survived.
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), WRONG, address).andExpect(status().isUnauthorized());
        login(donor.getPhone(), WRONG, address).andExpect(status().isTooManyRequests());
    }

    // ---------- Rule 7: the audit log ----------

    @Test
    void oneLockIsExactlyOneEntryPointingAtTheAccountWithNoActor() throws Exception {
        User donor = seed(Role.DONOR);
        String address = uniqueAddress();
        lock(donor.getPhone(), address);
        for (int i = 0; i < 5; i++) {
            login(donor.getPhone(), PASSWORD, address).andExpect(status().isTooManyRequests());
        }

        assertThat(lockEntriesFor(donor.getPhone())).singleElement().satisfies(entry -> {
            assertThat(entry.getActor()).isNull();
            assertThat(entry.getTarget().getId()).isEqualTo(donor.getId());
            assertThat(entry.getDetails()).isEqualTo("Sign-in locked for " + donor.getPhone() + " after 3 failed attempts");
        });
    }

    @Test
    void aSecondLockIsASecondEntry() throws Exception {
        User donor = seed(Role.DONOR);
        String address = uniqueAddress();
        lock(donor.getPhone(), address);

        clock.advance(Duration.ofMinutes(15));
        lock(donor.getPhone(), address);

        assertThat(lockEntriesFor(donor.getPhone())).hasSize(2);
    }

    @Test
    void anUnregisteredNumbersLockIsRecordedWithNoSubjectAndTheAuditLogPageCanShowIt() throws Exception {
        String phone = uniquePhone();
        lock(phone, uniqueAddress());

        assertThat(lockEntriesFor(phone)).singleElement().satisfies(entry -> {
            assertThat(entry.getActor()).isNull();
            assertThat(entry.getTarget()).isNull();
        });

        String page = mockMvc.perform(get("/api/admin/audit-log?size=100")
                .header("Authorization", "Bearer " + jwtService.issueToken(seed(Role.ADMIN))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var entry = java.util.stream.StreamSupport.stream(objectMapper.readTree(page).get("content").spliterator(), false)
            .filter(row -> row.get("details").asString().contains(phone))
            .findFirst().orElseThrow();
        assertThat(entry.get("action").asString()).isEqualTo("SIGN_IN_LOCKED");
        assertThat(entry.get("actorName").asString()).isEqualTo("System");
        assertThat(entry.get("actorId").isNull()).isTrue();
        assertThat(entry.get("targetType").asString()).isEqualTo("PHONE_NUMBER");
        assertThat(entry.get("targetName").asString()).isEqualTo("Unregistered number");
    }

    @Test
    void noOtherActionMayLoseItsActor() {
        User donor = seed(Role.DONOR);
        entityManager.flush();

        assertThatThrownBy(() -> entityManager.createNativeQuery(
                "insert into audit_log (action, target_user_id, details) values ('ACCOUNT_EDITED', :target, 'x')")
                .setParameter("target", donor.getId())
                .executeUpdate())
            .hasStackTraceContaining("audit_log_actor_absent_only_for_sign_in_lock");
    }

    // ---------- helpers ----------

    /** Three failures from one address: the number is locked when this returns. */
    private void lock(String phone, String address) throws Exception {
        for (int i = 0; i < 3; i++) {
            login(phone, WRONG, address).andExpect(status().isUnauthorized());
        }
        login(phone, WRONG, address).andExpect(status().isTooManyRequests());
    }

    private ResultActions login(String phone, String password, String address) throws Exception {
        return mockMvc.perform(from(address, post("/api/auth/login"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new LoginRequest(phone, password))));
    }

    private ResultActions forgot(String phone, String address, String... forwardedFor) throws Exception {
        MockHttpServletRequestBuilder request = from(address, post("/api/auth/password-reset-requests"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phone\": \"" + phone + "\"}");
        for (String value : forwardedFor) {
            request.header("X-Forwarded-For", value);
        }
        return mockMvc.perform(request);
    }

    private ResultActions register(String phone, String address) throws Exception {
        return mockMvc.perform(from(address, post("/api/auth/register"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\": \"Aminata Sesay\", \"phone\": \"" + phone + "\", \"email\": null"
                + ", \"dateOfBirth\": \"1998-03-12\", \"gender\": \"FEMALE\", \"bloodType\": \"O+\""
                + ", \"address\": \"Wusum, Makeni\", \"password\": \"" + PASSWORD + "\"}"));
    }

    private static MockHttpServletRequestBuilder from(String address, MockHttpServletRequestBuilder request) {
        return request.with(sent -> {
            sent.setRemoteAddr(address);
            return sent;
        });
    }

    private List<AuditLog> lockEntriesFor(String phone) {
        entityManager.flush();
        return auditLogRepository.findByActionOldestFirst(AuditAction.SIGN_IN_LOCKED).stream()
            .filter(entry -> entry.getDetails().contains(phone))
            .toList();
    }

    private static String withoutTimestamp(String json) {
        return json.replaceAll("\"timestamp\":\"[^\"]*\"", "");
    }

    private User seed(Role role) {
        return seed(role, uniquePhone());
    }

    private User seed(Role role, String phone) {
        User user = new User();
        user.setFullName("Rate Limit IT " + phone);
        user.setEmail("rate.limit.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(AccountStatus.ACTIVE);
        return userRepository.save(user);
    }

    private static String uniquePhone() {
        return "+23278%06d".formatted(SEQUENCE.incrementAndGet());
    }

    private static String uniqueAddress() {
        int next = SEQUENCE.incrementAndGet();
        return "10.77.%d.%d".formatted(next / 250, next % 250 + 1);
    }
}
