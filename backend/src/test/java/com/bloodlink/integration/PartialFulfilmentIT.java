package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.NotificationType;
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

// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.ObjectMapper;

/**
 * Partial, multi-bank fulfilment end to end (Business Rule 20): shared filling, bank queues, no auto-close after
 * declines, the one-way doors, REQUEST_PROGRESS. Rolled back; the race is in PartialFulfilmentRaceIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PartialFulfilmentIT {

    private static final String PASSWORD = "correct-horse-battery-2026";
    /** The group these tests trade in — kept off O+ so they never depend on, or
     *  interfere with, whatever the other integration tests are doing. */
    private static final String GROUP = "AB-";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private NotificationRepository notificationRepository;

    // ---------- two banks filling one request ----------

    /**
     * The case the feature exists for: a 4-unit request neither bank can cover alone, filled by both together.
     */
    @Test
    void twoBanksEachContributingPartOfARequestTogetherFulfilIt() throws Exception {
        Fixture fixture = new Fixture(4, 2, 3); // 4 units wanted; bank A holds 2, bank B 3

        // Bank A gives everything it has: 2 of the 4.
        contribute(fixture.bankAToken, fixture.requestId, 2)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PARTIALLY_FULFILLED"))
            .andExpect(jsonPath("$.unitsContributed").value(2))
            .andExpect(jsonPath("$.unitsOutstanding").value(2))
            .andExpect(jsonPath("$.lastContributedByBloodBankId").value(fixture.bankA.getId().toString()));

        // Its stock really moved, and only by what it gave.
        assertThat(unitsInStock(fixture.bankA.getId(), fixture.bankAToken)).isZero();

        // Bank B finishes the job with the remaining 2, out of the 3 it holds.
        contribute(fixture.bankBToken, fixture.requestId, 2)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULFILLED"))
            .andExpect(jsonPath("$.unitsContributed").value(4))
            .andExpect(jsonPath("$.unitsOutstanding").value(0))
            // The pointer follows the most recent contributor, never the first.
            .andExpect(jsonPath("$.lastContributedByBloodBankId").value(fixture.bankB.getId().toString()))
            // And both contributions are on the request, in the order they happened.
            .andExpect(jsonPath("$.contributions.length()").value(2))
            .andExpect(jsonPath("$.contributions[0].bloodBankName").value(fixture.bankA.getName()))
            .andExpect(jsonPath("$.contributions[0].unitsContributed").value(2))
            .andExpect(jsonPath("$.contributions[1].bloodBankName").value(fixture.bankB.getName()))
            .andExpect(jsonPath("$.contributions[1].unitsContributed").value(2));

        assertThat(unitsInStock(fixture.bankB.getId(), fixture.bankBToken)).isEqualTo(1); // 3 - 2

        // The hospital's own view says the same thing, from its own read path.
        mockMvc.perform(get("/api/requests").header("Authorization", bearer(fixture.hospitalToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("FULFILLED"))
            .andExpect(jsonPath("$[0].unitsContributed").value(4));

        // And it was told twice, once per event: units arrived, then the request was
        // complete — with the figures, not just a state.
        List<Notification> forHospital = notificationsFor(fixture.hospitalStaffId);
        assertThat(forHospital)
            .extracting(Notification::getType)
            .containsOnly(NotificationType.REQUEST_STATUS_CHANGE);
        assertThat(forHospital).extracting(Notification::getMessage)
            .anySatisfy(message -> assertThat(message).contains("2 of 4 units received", "2 units still needed"))
            .anySatisfy(message -> assertThat(message).contains("fulfilled in full"));
    }

    /** A bank offering more than is left gets capped rather than refused: between
     *  the screen being drawn and the button being pressed, the gap shrank. */
    @Test
    void aBankOfferingMoreUnitsThanAreLeftContributesOnlyWhatIsStillNeeded() throws Exception {
        Fixture fixture = new Fixture(4, 3, 9);

        contribute(fixture.bankAToken, fixture.requestId, 3).andExpect(status().isOk());

        // Bank B offers 9 — only 1 is wanted.
        contribute(fixture.bankBToken, fixture.requestId, 9)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULFILLED"))
            .andExpect(jsonPath("$.unitsContributed").value(4));

        // Exactly one unit left bank B's shelf, not nine.
        assertThat(unitsInStock(fixture.bankB.getId(), fixture.bankBToken)).isEqualTo(8);
    }

    // ---------- what each bank's queue shows ----------

    /**
     * A bank keeps a request it has partly filled — it may well contribute again —
     * and never sees a request another bank completed on its own.
     */
    @Test
    void aBankKeepsARequestItPartlyFilledAndNeverSeesOneAnotherBankCompletedAlone() throws Exception {
        Fixture fixture = new Fixture(4, 2, 9);

        // Bank A part-fills this one...
        contribute(fixture.bankAToken, fixture.requestId, 2).andExpect(status().isOk());
        // ...so it stays in its queue, still needing units.
        assertThat(requestIdsVisibleTo(fixture.bankAToken)).contains(fixture.requestId);

        // A second request, which bank B covers entirely by itself.
        UUID otherRequestId = submitRequest(fixture.hospitalToken, 1);
        contribute(fixture.bankBToken, otherRequestId, 1)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULFILLED"));

        // Bank A never touched it and it is closed, so it is not bank A's business.
        assertThat(requestIdsVisibleTo(fixture.bankAToken)).doesNotContain(otherRequestId);
        // Bank B, which filled it, keeps it in view as its own history.
        assertThat(requestIdsVisibleTo(fixture.bankBToken)).contains(otherRequestId);

        // And bank A can contribute again to the one it part-filled, once restocked.
        addStock(fixture.bankAToken, fixture.bankA.getId(), 2);
        contribute(fixture.bankAToken, fixture.requestId, 2)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULFILLED"))
            .andExpect(jsonPath("$.contributions.length()").value(2));
    }

    // ---------- declines against a request that has already received units ----------

    /**
     * A request with real progress never closes by decline (Business Rule 20's difference from Rule 11).
     */
    @Test
    void aPartiallyFulfilledRequestStaysOpenEvenAfterEveryOtherBankDeclinesTheRemainder() throws Exception {
        Fixture fixture = new Fixture(4, 2, 9);

        contribute(fixture.bankAToken, fixture.requestId, 2)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PARTIALLY_FULFILLED"));

        // Every other bank declines the remaining 2 units (the contributor itself cannot decline).
        for (String token : declineTokensOtherThan(fixture)) {
            mockMvc.perform(patch("/api/requests/{id}", fixture.requestId)
                    .header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        new RequestActionPayload(RequestAction.REJECT, null, null))))
                .andExpect(status().isOk())
                // Never REJECTED: units have already arrived against this request.
                .andExpect(jsonPath("$.status").value("PARTIALLY_FULFILLED"));
        }

        // The hospital still sees a live request it can still be helped with.
        mockMvc.perform(get("/api/requests").header("Authorization", bearer(fixture.hospitalToken)))
            .andExpect(jsonPath("$[0].status").value("PARTIALLY_FULFILLED"))
            .andExpect(jsonPath("$[0].unitsOutstanding").value(2));

        // And it was never told the request closed, because it never did.
        assertThat(notificationsFor(fixture.hospitalStaffId))
            .extracting(Notification::getMessage)
            .noneSatisfy(message -> assertThat(message).contains("declined by every blood bank"));
    }

    @Test
    void aBankThatContributedCannotDeclineAndABankThatDeclinedCannotContribute() throws Exception {
        Fixture fixture = new Fixture(4, 2, 9);

        // Bank A gives units, then tries to decline the rest.
        contribute(fixture.bankAToken, fixture.requestId, 2).andExpect(status().isOk());
        mockMvc.perform(patch("/api/requests/{id}", fixture.requestId)
                .header("Authorization", bearer(fixture.bankAToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new RequestActionPayload(RequestAction.REJECT, null, null))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("already contributed")));

        // Bank B declines, then tries to contribute anyway.
        mockMvc.perform(patch("/api/requests/{id}", fixture.requestId)
                .header("Authorization", bearer(fixture.bankBToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new RequestActionPayload(RequestAction.REJECT, null, null))))
            .andExpect(status().isOk());
        contribute(fixture.bankBToken, fixture.requestId, 2)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("already declined")));

        // Neither refusal moved any stock.
        assertThat(unitsInStock(fixture.bankB.getId(), fixture.bankBToken)).isEqualTo(9);
    }

    // ---------- keeping the other banks informed ----------

    /**
     * The alert that stops two banks covering the same shrinking gap: when one
     * contributes, every other bank still watching the request is told what is left.
     */
    @Test
    void contributingTellsTheOtherBanksHowManyUnitsAreStillNeeded() throws Exception {
        Fixture fixture = new Fixture(4, 2, 9);

        contribute(fixture.bankAToken, fixture.requestId, 2).andExpect(status().isOk());

        // Bank B's staff hear about it...
        assertThat(notificationsFor(fixture.bankBStaffId))
            .filteredOn(row -> row.getType() == NotificationType.REQUEST_PROGRESS)
            .singleElement()
            .satisfies(row -> assertThat(row.getMessage())
                .contains(fixture.bankA.getName(), "2 units still needed"));

        // ...and the bank that made the contribution does not: it already knows.
        assertThat(notificationsFor(fixture.bankAStaffId))
            .noneSatisfy(row -> assertThat(row.getType()).isEqualTo(NotificationType.REQUEST_PROGRESS));
    }

    // ---------- fixture and helpers ----------

    /**
     * One request against the two seeded banks, stock added through the real endpoint,
     * and a signed-in account for each side; requests use GROUP, unused by the seed.
     */
    private final class Fixture {
        private final BloodBank bankA;
        private final BloodBank bankB;
        private final String bankAToken;
        private final String bankBToken;
        private final String hospitalToken;
        private final UUID bankAStaffId;
        private final UUID bankBStaffId;
        private final UUID hospitalStaffId;
        private final UUID requestId;

        private Fixture(int unitsRequested, int bankAStock, int bankBStock) throws Exception {
            List<BloodBank> banks = bloodBankRepository.findAllByOrderByNameAsc();
            bankA = banks.get(0);
            bankB = banks.get(1);
            Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);

            User bankAStaff = staff("Bank A Technician", Role.BLOOD_BANK_STAFF, bankA, null);
            User bankBStaff = staff("Bank B Technician", Role.BLOOD_BANK_STAFF, bankB, null);
            User hospitalStaff = staff("Ward Clerk", Role.HOSPITAL_STAFF, null, hospital);
            bankAStaffId = bankAStaff.getId();
            bankBStaffId = bankBStaff.getId();
            hospitalStaffId = hospitalStaff.getId();

            bankAToken = signIn(bankAStaff);
            bankBToken = signIn(bankBStaff);
            hospitalToken = signIn(hospitalStaff);

            addStock(bankAToken, bankA.getId(), bankAStock);
            addStock(bankBToken, bankB.getId(), bankBStock);

            requestId = submitRequest(hospitalToken, unitsRequested);
        }
    }

    /** Signed-in staff for every bank except the one that contributed — the banks
     *  that can still decline the remainder. */
    private List<String> declineTokensOtherThan(Fixture fixture) {
        return List.of(fixture.bankBToken);
    }

    private ResultActions contribute(String token, UUID requestId, int units) throws Exception {
        return mockMvc.perform(patch("/api/requests/{id}", requestId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                new RequestActionPayload(RequestAction.CONTRIBUTE, null, units))));
    }

    private UUID submitRequest(String hospitalToken, int units) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/requests")
                .header("Authorization", bearer(hospitalToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateRequestPayload(
                    GROUP, units, UrgencyLevel.URGENT, "Theatre 2"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.unitsOutstanding").value(units))
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), BloodRequestResponse.class).id();
    }

    /** Adds units of {@link #GROUP} to one bank through the real endpoint, so the
     *  accumulation rules (Business Rule 7) apply exactly as they would in use. */
    private void addStock(String token, UUID bankId, int units) throws Exception {
        if (units == 0) {
            return;
        }
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

    /** The ids in one caller's own feed, read through the real endpoint so the
     *  scoping under test is the one the screen actually gets. */
    private List<UUID> requestIdsVisibleTo(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/requests").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        BloodRequestResponse[] rows = objectMapper.readValue(
            result.getResponse().getContentAsString(), BloodRequestResponse[].class);
        return List.of(rows).stream().map(BloodRequestResponse::id).toList();
    }

    private List<Notification> notificationsFor(UUID userId) {
        return notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(userId);
    }

    /** A staff account with whichever facility its role needs, stored the way an
     *  administrator would have to create it (there is no self-registration path). */
    private User staff(String fullName, Role role, BloodBank bank, Hospital hospital) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail("partial.it." + UUID.randomUUID() + "@example.com");
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
        return "076" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
