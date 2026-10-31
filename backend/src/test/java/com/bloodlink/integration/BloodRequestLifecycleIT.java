package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.auth.dto.RegisterRequest;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestAction;
import com.bloodlink.request.dto.RequestActionPayload;

// Spring Boot 4 ships Jackson 3, so this is tools.jackson's ObjectMapper, not com.fasterxml's.
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests through every layer on a real database: the full request lifecycle with notification,
 * and Business Rule 11's per-bank declines. @Transactional on bloodlink_test; run -Dtest=BloodRequestLifecycleIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BloodRequestLifecycleIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private NotificationRepository notificationRepository;

    @Test
    void donorRegistrationHospitalRequestBankFulfilmentAndNotificationAllWorkTogether() throws Exception {
        // ---------- 1. A donor registers, for real, over HTTP ----------
        // Typed the local way; step 1b signs in with the international spelling.
        String localPhone = uniqueLocalPhone();

        RegisterRequest registration = new RegisterRequest(
            "Aminata Sesay",
            localPhone,
            "aminata.it." + uniqueSuffix() + "@example.com",
            LocalDate.of(1998, 3, 12),
            Gender.FEMALE,
            "O+",
            "Wusum, Makeni",
            PASSWORD
        );

        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registration)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user.role").value("DONOR"))
            .andReturn();
        AuthResponse donorAuth = readBody(registerResult, AuthResponse.class);

        // The registration response is not just trusted — the token it issued is
        // proven to actually authenticate, against the donor's own profile.
        mockMvc.perform(get("/api/donors/me").header("Authorization", bearer(donorAuth.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fullName").value("Aminata Sesay"))
            .andExpect(jsonPath("$.eligibilityStatus").value("ELIGIBLE"));

        // ---------- 1b. The same donor signs in with the other format ----------
        // Works only because registration and login share one normalisation rule, proven on the real database.
        String internationalPhone = internationalSpellingOf(localPhone);
        assertThat(internationalPhone).isNotEqualTo(localPhone);

        MvcResult crossFormatLogin = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(internationalPhone, PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();

        // Same account, not merely a successful login: the stored number comes back
        // in the one format, whichever of the two was typed to reach it.
        AuthResponse reAuth = readBody(crossFormatLogin, AuthResponse.class);
        assertThat(reAuth.user().id()).isEqualTo(donorAuth.user().id());
        assertThat(reAuth.user().phone()).isEqualTo(PhoneNumber.normalize(localPhone));

        // ---------- 2. Seed the two staff accounts no self-registration path can create ----------
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        BloodBank bank = bloodBankRepository.findAllByOrderByNameAsc().get(0);

        User hospitalStaff = staffAccount(
            "Hospital Desk Clerk", uniqueLocalPhone(), Role.HOSPITAL_STAFF
        );
        hospitalStaff.setHospital(hospital);
        hospitalStaff = userRepository.save(hospitalStaff);

        User bankStaff = staffAccount(
            "Lab Technician", uniqueLocalPhone(), Role.BLOOD_BANK_STAFF
        );
        bankStaff.setBloodBank(bank);
        userRepository.save(bankStaff);

        String hospitalToken = loginAndGetToken(hospitalStaff.getPhone());
        String bankToken = loginAndGetToken(bankStaff.getPhone());

        // ---------- 3. The bank stocks up (Business Rule 7's accumulation, for real) ----------
        int startingOPlusUnits = readOPlusUnits(bank.getId(), bankToken);

        mockMvc.perform(patch("/api/inventory/{bankId}/{bloodType}", bank.getId(), "O+")
                .header("Authorization", bearer(bankToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new AddBatchRequest(20, LocalDate.now().plusMonths(2)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unitsAvailable").value(startingOPlusUnits + 20));

        // ---------- 4. The hospital submits a request ----------
        CreateRequestPayload requestPayload =
            new CreateRequestPayload("O+", 3, UrgencyLevel.URGENT, "Maternity Ward — Bed 4");

        MvcResult createResult = mockMvc.perform(post("/api/requests")
                .header("Authorization", bearer(hospitalToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestPayload)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.hospitalId").value(hospital.getId().toString()))
            .andReturn();
        BloodRequestResponse created = readBody(createResult, BloodRequestResponse.class);

        // ---------- 5. The bank contributes the whole amount in one step ----------
        // Business Rule 20's simple case: one contribution covers and closes the request.
        mockMvc.perform(patch("/api/requests/{id}", created.id())
                .header("Authorization", bearer(bankToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RequestActionPayload(RequestAction.CONTRIBUTE, null, 3))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULFILLED"))
            .andExpect(jsonPath("$.unitsContributed").value(3))
            .andExpect(jsonPath("$.unitsOutstanding").value(0))
            .andExpect(jsonPath("$.lastContributedByBloodBankId").value(bank.getId().toString()))
            // The contribution itself is on the request, named and counted.
            .andExpect(jsonPath("$.contributions.length()").value(1))
            .andExpect(jsonPath("$.contributions[0].bloodBankName").value(bank.getName()))
            .andExpect(jsonPath("$.contributions[0].unitsContributed").value(3));

        // The stock deduction Business Rule 20 requires actually happened:
        // 20 units added, 3 issued, so 17 more than whatever the bank started with.
        assertThat(readOPlusUnits(bank.getId(), bankToken)).isEqualTo(startingOPlusUnits + 17);

        // The hospital sees its own request as FULFILLED through its own read path too.
        mockMvc.perform(get("/api/requests").header("Authorization", bearer(hospitalToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(created.id().toString()))
            .andExpect(jsonPath("$[0].status").value("FULFILLED"));

        // ---------- 6. The chain's final link: the hospital was actually notified ----------
        // Read from the repository, since the push is after-commit and this transaction rolls back.
        List<Notification> hospitalNotifications =
            notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(hospitalStaff.getId());

        assertThat(hospitalNotifications)
            .extracting(Notification::getType)
            .containsOnly(NotificationType.REQUEST_STATUS_CHANGE);
        // One notification, not two: a single contribution covered the whole request (Business Rule 20).
        assertThat(hospitalNotifications).hasSize(1);
        assertThat(hospitalNotifications)
            .extracting(Notification::getMessage)
            .anySatisfy(message -> assertThat(message).contains("fulfilled in full"));
    }

    /**
     * Business Rule 11's per-bank declines against a real database, since the rule lives in a JPQL not-exists clause.
     * Walks every existing bank: PENDING after each decline but the last, REJECTED only when none are left.
     */
    @Test
    void oneBankDecliningKeepsARequestOpenForTheOthersAndTheLastDeclineClosesIt() throws Exception {
        List<BloodBank> banks = bloodBankRepository.findAllByOrderByNameAsc();
        // Needs more than one bank, or "some declined" and "all declined" would be the same state.
        assertThat(banks).hasSizeGreaterThan(1);

        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);

        User hospitalStaff = staffAccount(
            "Request Desk Clerk", uniqueLocalPhone(), Role.HOSPITAL_STAFF
        );
        hospitalStaff.setHospital(hospital);
        hospitalStaff = userRepository.save(hospitalStaff);
        String hospitalToken = loginAndGetToken(hospitalStaff.getPhone());

        // One staff account per bank, so each bank's queue can be read and acted on
        // as that bank — which is the only way the per-bank scoping can be observed.
        List<String> bankTokens = new ArrayList<>();
        for (BloodBank bank : banks) {
            User bankStaff = staffAccount(
                "Lab Technician " + bank.getName(), uniqueLocalPhone(), Role.BLOOD_BANK_STAFF
            );
            bankStaff.setBloodBank(bank);
            userRepository.save(bankStaff);
            bankTokens.add(loginAndGetToken(bankStaff.getPhone()));
        }

        // A group deliberately unlikely to be stocked is irrelevant here — declining
        // never touches inventory — so this uses the same shape as any other request.
        MvcResult createResult = mockMvc.perform(post("/api/requests")
                .header("Authorization", bearer(hospitalToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new CreateRequestPayload("AB-", 2, UrgencyLevel.URGENT, "ICU — Bed 8"))))
            .andExpect(status().isCreated())
            .andReturn();
        UUID requestId = readBody(createResult, BloodRequestResponse.class).id();

        // Before anyone responds, every bank can see it — Business Rule 6.
        for (String token : bankTokens) {
            assertThat(requestIdsVisibleTo(token)).contains(requestId);
        }

        // Each bank declines in turn. Every decline but the last must leave the
        // request PENDING and visible to the banks that have not yet responded.
        for (int i = 0; i < bankTokens.size(); i++) {
            boolean isLastBank = (i == bankTokens.size() - 1);
            String expectedStatus = isLastBank ? "REJECTED" : "PENDING";

            mockMvc.perform(patch("/api/requests/{id}", requestId)
                    .header("Authorization", bearer(bankTokens.get(i)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        new RequestActionPayload(RequestAction.REJECT, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                // A decline supplies nothing, so it never claims the request.
                .andExpect(jsonPath("$.lastContributedByBloodBankId").doesNotExist());

            // The declining bank has lost sight of it, for good.
            assertThat(requestIdsVisibleTo(bankTokens.get(i))).doesNotContain(requestId);

            // A second decline by the same bank is a 409, for different reasons before and after the request closes;
            // the message is asserted so it cannot pass for the wrong one.
            mockMvc.perform(patch("/api/requests/{id}", requestId)
                    .header("Authorization", bearer(bankTokens.get(i)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        new RequestActionPayload(RequestAction.REJECT, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString(
                    isLastBank ? "this one is rejected" : "already declined"
                )));

            // Every bank that has not responded yet still sees it and can still act
            // — the whole point of the change.
            for (int j = i + 1; j < bankTokens.size(); j++) {
                assertThat(requestIdsVisibleTo(bankTokens.get(j))).contains(requestId);
            }
        }

        // Only now, with no bank left to ask, is the hospital's request closed —
        // and the hospital is told once, on that final decline.
        mockMvc.perform(get("/api/requests").header("Authorization", bearer(hospitalToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(requestId.toString()))
            .andExpect(jsonPath("$[0].status").value("REJECTED"));

        List<Notification> hospitalNotifications =
            notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(hospitalStaff.getId());

        // Exactly one status-change notification, not one per decline: a decline
        // that leaves the request live is not news the hospital can act on.
        assertThat(hospitalNotifications)
            .extracting(Notification::getType)
            .containsOnly(NotificationType.REQUEST_STATUS_CHANGE);
        assertThat(hospitalNotifications).hasSize(1);
        assertThat(hospitalNotifications.get(0).getMessage()).contains("declined by every blood bank");
    }

    // ---------- helpers ----------

    /** The ids in one caller's own request feed, read through the real endpoint so the scoping is what the screen gets. */
    private List<UUID> requestIdsVisibleTo(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/requests").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        BloodRequestResponse[] rows = objectMapper.readValue(
            result.getResponse().getContentAsString(), BloodRequestResponse[].class
        );
        return List.of(rows).stream().map(BloodRequestResponse::id).toList();
    }

    /** An active staff account as an administrator would create it by hand,
     * with the phone stored through PhoneNumber.normalize so login works. */
    private User staffAccount(String fullName, String phone, Role role) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail("staff.it." + uniqueSuffix() + "@example.com");
        user.setPhone(PhoneNumber.normalize(phone));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        return user;
    }

    private String loginAndGetToken(String phone) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(phone, PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
        return readBody(result, AuthResponse.class).token();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private <T> T readBody(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type);
    }

    /** This bank's current O+ units via the real endpoint, so the arithmetic holds whatever it held at the start. */
    private int readOPlusUnits(UUID bankId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/inventory/{bankId}", bankId)
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        InventoryItemResponse[] rows = objectMapper.readValue(
            result.getResponse().getContentAsString(), InventoryItemResponse[].class
        );
        return List.of(rows).stream()
            .filter(row -> row.bloodType().equals("O+"))
            .findFirst()
            .orElseThrow()
            .unitsAvailable();
    }

    /**
     * A short digits-only suffix so repeated runs never collide on phone/email uniqueness before the rollback.
     */
    private String uniqueSuffix() {
        return String.valueOf(System.nanoTime()).substring(8);
    }

    /**
     * A distinct local-format phone for this run (0 plus eight digits, six from the clock),
     * so repeated runs don't collide on the unique phone constraint.
     */
    private String uniqueLocalPhone() {
        return "076" + String.format("%06d", System.nanoTime() % 1_000_000L);
    }

    /**
     * The same number written the other accepted way (076123456 becomes +232 76 123 456), for signing in.
     */
    private String internationalSpellingOf(String localPhone) {
        String national = localPhone.substring(1);
        return "+232 " + national.substring(0, 2)
            + " " + national.substring(2, 5)
            + " " + national.substring(5);
    }
}
