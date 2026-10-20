package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.admin.dto.AdminUserPageResponse;
import com.bloodlink.admin.dto.AdminUserResponse;
import com.bloodlink.admin.dto.UserCountsResponse;
import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;

// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.ObjectMapper;

/**
 * User Accounts search, filters, sort and paging against a real database: LIKE escaping, the name/email/phone OR,
 * matching counts and stable pages. Marked accounts, relative assertions; rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminUserSearchIT {

    private static final String PASSWORD = "correct-horse-battery-2026";
    /** Stored as the hash of accounts that never sign in, so the "no hash in the
     *  response" test has something unmistakable to look for. */
    private static final String SENTINEL_HASH = "HASH-MUST-NEVER-APPEAR-IN-A-RESPONSE";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;

    private String mark;
    private String adminToken;

    @BeforeEach
    void signInAsAdministrator() throws Exception {
        // Letters only: a marker with digits could also match a phone fragment.
        StringBuilder letters = new StringBuilder("Qz");
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) letters.append((char) ('a' + random.nextInt(26)));
        mark = letters.toString();
        adminToken = signIn(loginUser("Console Operator", Role.ADMIN));
    }

    // ---------- paging maths, and a page past the end ----------

    @Test
    void pagingSplitsTheMatchesAndAPagePastTheEndIsEmptyNotAnError() throws Exception {
        seedTwelve();

        AdminUserPageResponse first = page("q=" + mark + "&size=5");
        assertThat(first.totalElements()).isEqualTo(12);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.content()).hasSize(5);

        assertThat(page("q=" + mark + "&size=5&page=2").content()).hasSize(2);

        AdminUserPageResponse pastTheEnd = page("q=" + mark + "&size=5&page=9");
        assertThat(pastTheEnd.content()).isEmpty();
        assertThat(pastTheEnd.totalElements()).isEqualTo(12);
    }

    // ---------- filters: each alone, together, and with search ----------

    @Test
    void eachFilterAloneNarrowsTheTotal() throws Exception {
        seedTwelve();

        assertThat(total("q=" + mark)).isEqualTo(12);
        assertThat(total("q=" + mark + "&role=DONOR")).isEqualTo(9);
        assertThat(total("q=" + mark + "&role=BLOOD_BANK_STAFF")).isEqualTo(1);
        assertThat(total("q=" + mark + "&role=HOSPITAL_STAFF")).isEqualTo(1);
        assertThat(total("q=" + mark + "&role=ADMIN")).isEqualTo(1);
        assertThat(total("q=" + mark + "&status=SUSPENDED")).isEqualTo(1);
        assertThat(total("q=" + mark + "&status=ACTIVE")).isEqualTo(11);
    }

    @Test
    void theTotalChangesWhenAFilterIsAppliedWithoutAnySearchAtAll() throws Exception {
        long donorsBefore = total("role=DONOR");
        long allBefore = total("");
        seedTwelve();

        // The whole table, no search: the filter alone changes the count, counted in
        // the database (9 donors among the 12 created).
        assertThat(total("") - allBefore).isEqualTo(12);
        assertThat(total("role=DONOR") - donorsBefore).isEqualTo(9);
    }

    @Test
    void roleStatusAndSearchCombineIntoOneRequest() throws Exception {
        seedTwelve();

        // "Donor + Active + name contains Kamara": Aminata (active donor) only —
        // Fatmata is a suspended donor, Mohamed is blood bank staff.
        AdminUserPageResponse result = page("q=Kamara" + mark + "&role=DONOR&status=ACTIVE&sort=name");
        assertThat(result.content()).extracting(AdminUserResponse::fullName)
            .containsExactly("Aminata Kamara" + mark);
    }

    // ---------- search: name, email, phone in three forms, wildcards ----------

    @Test
    void searchMatchesNameCaseInsensitively() throws Exception {
        seedTwelve();
        assertThat(names("q=" + ("hawa bangura" + mark).toUpperCase())).containsExactly("Hawa Bangura" + mark);
    }

    @Test
    void searchMatchesEmailCaseInsensitivelyAndAnAccountWithNoEmailStillLists() throws Exception {
        List<User> seeded = seedTwelve();
        User aminata = seeded.get(0);

        assertThat(ids("q=" + aminata.getEmail().toUpperCase())).containsExactly(aminata.getId());
        // Hawa has no email (Business Rule 18) and is still found by name.
        assertThat(seeded.get(5).getEmail()).isNull();
        assertThat(ids("q=Hawa Bangura" + mark)).containsExactly(seeded.get(5).getId());
    }

    @Test
    void searchMatchesPhoneTypedLocallyInternationallyWithSpacesOrAsPartialDigits() throws Exception {
        User aminata = seedTwelve().get(0);
        String stored = aminata.getPhone();                                  // +23276xxxxxx
        String local = "0" + stored.substring(4);                            // 076xxxxxx
        String spaced = "+232 " + stored.substring(4, 6) + " " + stored.substring(6); // +232 76 xxxxxx

        assertThat(ids("q=" + local)).contains(aminata.getId());
        assertThat(ids("q=" + spaced)).contains(aminata.getId());
        assertThat(ids("q=" + stored.substring(6))).contains(aminata.getId()); // last six digits
    }

    @Test
    void percentAndUnderscoreAreLiteralNotWildcards() throws Exception {
        long percentBefore = total("q=%");
        long underscoreBefore = total("q=_");
        seedTwelve();

        // Exactly one account created here has a "%" in it; none has an "_".
        // Unescaped, "%" would have matched all twelve and "_" every one too.
        assertThat(total("q=%") - percentBefore).isEqualTo(1);
        assertThat(total("q=_") - underscoreBefore).isZero();
        assertThat(names("q=100%" + mark)).containsExactly("Rate 100%" + mark);
    }

    // ---------- sort, direction, and a stable order across pages ----------

    @Test
    void sortByNameAscendingAndDescendingAreExactReverses() throws Exception {
        seedTwelve();
        List<UUID> ascending = ids("q=" + mark + "&size=100&sort=name&direction=asc");
        List<UUID> descending = ids("q=" + mark + "&size=100&sort=name&direction=desc");

        assertThat(names("q=" + mark + "&size=100&sort=name&direction=asc").get(0)).isEqualTo("Abu Turay" + mark);
        assertThat(names("q=" + mark + "&size=100&sort=name&direction=desc").get(0)).isEqualTo("Same Name" + mark);
        // Same twelve accounts either way. Not an exact mirror: the five identical
        // names keep id-ascending order in both directions (the tie-breaker).
        assertThat(new HashSet<>(ascending)).isEqualTo(new HashSet<>(descending)).hasSize(12);
    }

    @Test
    void walkingThePagesVisitsEveryAccountOnceEvenWithIdenticalNames() throws Exception {
        seedTwelve();

        List<UUID> walked = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            walked.addAll(ids("q=" + mark + "&size=5&sort=name&page=" + p));
        }
        List<UUID> inOneGo = ids("q=" + mark + "&size=100&sort=name");

        // Five accounts share one name; without the id tie-breaker they could swap
        // between page requests and one could appear twice and another never.
        assertThat(walked).hasSize(12).doesNotHaveDuplicates().isEqualTo(inOneGo);
    }

    @Test
    void roleStatusAndCreatedDateSortsAllWork() throws Exception {
        seedTwelve();

        assertThat(page("q=" + mark + "&sort=role&direction=asc").content().get(0).role()).isEqualTo(Role.ADMIN);
        assertThat(page("q=" + mark + "&sort=status&direction=desc").content().get(0).status())
            .isEqualTo(AccountStatus.SUSPENDED);
        assertThat(page("q=" + mark + "&sort=createdAt&direction=desc").content()).isNotEmpty();
    }

    // ---------- bad input ----------

    @Test
    void unknownSortRoleStatusAndDirectionAreEach400NamingTheField() throws Exception {
        for (String[] bad : new String[][] {
            {"sort", "password_hash"}, {"role", "SUPERUSER"}, {"status", "DISABLED"},
            {"direction", "up"}, {"size", "101"}, {"page", "-1"}
        }) {
            String body = mockMvc.perform(get("/api/admin/users").param(bad[0], bad[1]).header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
            assertThat(body).as(bad[0]).contains("\"" + bad[0] + "\"");
        }
    }

    // ---------- who may call it, and what never comes back ----------

    @Test
    void donorHospitalAndBankStaffAreRefusedAndAnonymousIsUnauthenticated() throws Exception {
        for (Role role : List.of(Role.DONOR, Role.HOSPITAL_STAFF, Role.BLOOD_BANK_STAFF)) {
            String token = signIn(loginUser("Not An Admin", role));
            mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/reports/users").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void noPasswordHashEverAppearsInTheResponse() throws Exception {
        seedTwelve();
        String body = mockMvc.perform(get("/api/admin/users").param("q", mark).param("size", "100")
                .header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(SENTINEL_HASH).doesNotContain("passwordHash").doesNotContain("$2a$");
    }

    // ---------- the Dashboard's counts ----------

    @Test
    void theCountEndpointMovesByExactlyWhatWasCreated() throws Exception {
        UserCountsResponse before = counts();
        seedTwelve();
        UserCountsResponse after = counts();

        assertThat(after.total() - before.total()).isEqualTo(12);
        assertThat(after.donors() - before.donors()).isEqualTo(9);
        assertThat(after.bloodBankStaff() - before.bloodBankStaff()).isEqualTo(1);
        assertThat(after.hospitalStaff() - before.hospitalStaff()).isEqualTo(1);
        assertThat(after.admins() - before.admins()).isEqualTo(1);
    }

    // ---------- fixture and helpers ----------

    /**
     * Twelve marked accounts: 9 donors (one suspended, one without email, one with "%", five sharing a name),
     * one of each staff role and an admin. Index 0 is Aminata Kamara, 5 is Hawa Bangura (no email).
     */
    private List<User> seedTwelve() {
        List<User> seeded = new ArrayList<>();
        seeded.add(account("Aminata Kamara", Role.DONOR, AccountStatus.ACTIVE, true));
        seeded.add(account("Fatmata Kamara", Role.DONOR, AccountStatus.SUSPENDED, true));
        seeded.add(account("Mohamed Kamara", Role.BLOOD_BANK_STAFF, AccountStatus.ACTIVE, true));
        seeded.add(account("Isatu Sesay", Role.HOSPITAL_STAFF, AccountStatus.ACTIVE, true));
        seeded.add(account("Abu Turay", Role.ADMIN, AccountStatus.ACTIVE, true));
        seeded.add(account("Hawa Bangura", Role.DONOR, AccountStatus.ACTIVE, false));
        seeded.add(account("Rate 100%", Role.DONOR, AccountStatus.ACTIVE, false));
        for (int i = 0; i < 5; i++) {
            seeded.add(account("Same Name", Role.DONOR, AccountStatus.ACTIVE, false));
        }
        return seeded;
    }

    private User account(String name, Role role, AccountStatus status, boolean withEmail) {
        User user = new User();
        user.setFullName(name + mark);
        // Staff and administrators must have an email (V13); donors may not.
        user.setEmail(withEmail || role != Role.DONOR
            ? (name.replaceAll("[^A-Za-z]", "").toLowerCase() + "." + mark + "." + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com").toLowerCase()
            : null);
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(SENTINEL_HASH);
        user.setRole(role);
        user.setStatus(status);
        if (role == Role.BLOOD_BANK_STAFF) user.setBloodBank(bloodBankRepository.findAllByOrderByNameAsc().get(0));
        if (role == Role.HOSPITAL_STAFF) user.setHospital(hospitalRepository.findAllByOrderByNameAsc().get(0));
        return userRepository.save(user);
    }

    /** An account that signs in — a real BCrypt hash, and no marker in its name. */
    private User loginUser(String name, Role role) {
        User user = new User();
        user.setFullName(name);
        user.setEmail("search.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        if (role == Role.BLOOD_BANK_STAFF) user.setBloodBank(bloodBankRepository.findAllByOrderByNameAsc().get(0));
        if (role == Role.HOSPITAL_STAFF) user.setHospital(hospitalRepository.findAllByOrderByNameAsc().get(0));
        return userRepository.save(user);
    }

    /**
     * One page for unencoded "key=value&..." pairs; MockMvc encodes them once, so "%" stays literal.
     */
    private AdminUserPageResponse page(String query) throws Exception {
        var request = get("/api/admin/users").header("Authorization", bearer(adminToken));
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                request.param(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }
        MvcResult result = mockMvc.perform(request)
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AdminUserPageResponse.class);
    }

    private long total(String query) throws Exception {
        return page(query).totalElements();
    }

    private List<UUID> ids(String query) throws Exception {
        return page(query).content().stream().map(AdminUserResponse::id).toList();
    }

    private List<String> names(String query) throws Exception {
        return page(query).content().stream().map(AdminUserResponse::fullName).toList();
    }

    private UserCountsResponse counts() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/reports/users").header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), UserCountsResponse.class);
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

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "076" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
