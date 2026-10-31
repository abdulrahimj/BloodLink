package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;

import jakarta.persistence.EntityManager;
// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the inventory JSON the Hospital Dashboard and Blood Bank screens read (names and types),
 * so batch-level stock cannot break them. Read as HOSPITAL_STAFF; rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InventoryApiContractIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";

    /** The fields every row carried before the cutover, which the screens read. */
    private static final List<String> ROW_FIELDS_BEFORE_CUTOVER =
        List.of("bloodType", "unitsAvailable", "expiryDate", "lastUpdated", "status", "appealAvailability");
    /** The fields the summary carried before the cutover. */
    private static final List<String> SUMMARY_FIELDS_BEFORE_CUTOVER = List.of(
        "bloodBankId", "bloodBankName", "totalUnits", "expiringSoonUnits", "criticalGroupsCount",
        "unitsIssuedThisMonth", "composition");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodInventoryBatchRepository batchRepository;

    @Test
    void theInventoryRowsKeepEveryFieldTheScreensReadWithTheSameTypes() throws Exception {
        BloodBank bank = newBank();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        batchRepository.save(BloodInventoryBatch.manual(bank, BloodType.O_NEGATIVE, 7, today.plusDays(10)));
        entityManager.flush();
        String token = hospitalToken();

        JsonNode rows = json(mockMvc.perform(get("/api/inventory/{id}", bank.getId())
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn());

        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isEqualTo(8);
        for (JsonNode row : rows) {
            assertThat(fieldNames(row)).containsAll(ROW_FIELDS_BEFORE_CUTOVER)
                // Added after them, by this stage.
                .contains("expiringSoonUnits", "expiredUnits", "batchCount");
            assertThat(row.get("bloodType").isString()).isTrue();
            assertThat(row.get("unitsAvailable").isIntegralNumber()).isTrue();
            assertThat(row.get("expiryDate").isString() || row.get("expiryDate").isNull()).isTrue();
            assertThat(row.get("lastUpdated").isString() || row.get("lastUpdated").isNull()).isTrue();
            assertThat(Set.of("SUFFICIENT", "LOW", "CRITICAL")).contains(row.get("status").asString());
            assertThat(row.get("appealAvailability").isString()).isTrue();
        }

        // The stocked row carries the same kinds of values as ever: an ISO date and
        // a number the hospital's availability panel sums.
        JsonNode stocked = null;
        for (JsonNode row : rows) {
            if (row.get("bloodType").asString().equals("O-")) {
                stocked = row;
            }
        }
        assertThat(stocked).isNotNull();
        assertThat(stocked.get("unitsAvailable").asInt()).isEqualTo(7);
        assertThat(stocked.get("expiryDate").asString()).isEqualTo(today.plusDays(10).toString());
        assertThat(stocked.get("status").asString()).isEqualTo("CRITICAL");
    }

    @Test
    void theSummaryKeepsEveryFieldTheInventoryPageReads() throws Exception {
        BloodBank bank = newBank();
        String token = hospitalToken();

        JsonNode summary = json(mockMvc.perform(get("/api/inventory/{id}/summary", bank.getId())
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn());

        assertThat(fieldNames(summary)).containsAll(SUMMARY_FIELDS_BEFORE_CUTOVER)
            .contains("expiredUnits", "expiringSoonWindowDays");
        assertThat(summary.get("totalUnits").isIntegralNumber()).isTrue();
        assertThat(summary.get("expiringSoonUnits").isIntegralNumber()).isTrue();
        assertThat(summary.get("composition").isArray()).isTrue();
        assertThat(summary.get("expiringSoonWindowDays").asInt()).isEqualTo(28);
    }

    // ---------- helpers ----------

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private BloodBank newBank() {
        BloodBank bank = new BloodBank();
        bank.setName("Contract Bank " + UUID.randomUUID().toString().substring(0, 8));
        bank.setLocation("Makeni");
        bank.setContactPhone("076 000 000");
        return bloodBankRepository.save(bank);
    }

    private String hospitalToken() throws Exception {
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        User user = new User();
        user.setFullName("Contract Ward Clerk");
        user.setEmail("contract.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.HOSPITAL_STAFF);
        user.setHospital(hospital);
        userRepository.save(user);

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

    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "078" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
