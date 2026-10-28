package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
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
import com.bloodlink.domain.AuditLog;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.AuditLogRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;

import jakarta.persistence.EntityManager;
// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Business Rule 28 end to end: Record Donation fills or (confirmed) changes the profile group, a manual correction
 * by any bank's active staff, the audit entries, and appeals reaching a newly typed donor. @Transactional, rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DonorBloodGroupIT {

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
    @Autowired private NotificationRepository notificationRepository;

    private BloodBank bank;
    private BloodBank otherBank;
    private User staff;
    private String staffToken;
    private String otherStaffToken;
    private String hospitalToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        bank = newBank("Typing Bank");
        otherBank = newBank("Typing Other Bank");
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        staff = user("Typing Technician", Role.BLOOD_BANK_STAFF, bank, null);
        staffToken = signIn(staff);
        otherStaffToken = signIn(user("Typing Other Technician", Role.BLOOD_BANK_STAFF, otherBank, null));
        hospitalToken = signIn(user("Typing Ward Clerk", Role.HOSPITAL_STAFF, null, hospital));
        adminToken = signIn(user("Typing Administrator", Role.ADMIN, null, null));
    }

    // ---------- Part 1: Record Donation ----------

    @Test
    void anEmptyProfileIsFilledFromTheTestAndAudited() throws Exception {
        Donor donor = donor(null);

        recordDonation(staffToken, donor, "B+", null).andExpect(status().isCreated())
            .andExpect(jsonPath("$.bloodType").value("B+"))
            .andExpect(jsonPath("$.profileBloodTypeUpdated").value(true));
        flush();

        assertThat(profileGroupOf(donor)).isEqualTo(BloodType.B_POSITIVE);
        List<AuditLog> entries = groupEntriesFor(donor);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getActor().getId()).isEqualTo(staff.getId());
        assertThat(entries.get(0).getCreatedAt()).isNotNull();
        assertThat(entries.get(0).getDetails()).isEqualTo(
            "Blood group set to B+ (none was recorded before). Source: donation at " + bank.getName() + ".");
    }

    @Test
    void theSameGroupChangesNothingAndWritesNoAuditEntry() throws Exception {
        Donor donor = donor(BloodType.O_POSITIVE);

        recordDonation(staffToken, donor, "O+", null).andExpect(status().isCreated())
            .andExpect(jsonPath("$.profileBloodTypeUpdated").value(false));
        flush();

        assertThat(profileGroupOf(donor)).isEqualTo(BloodType.O_POSITIVE);
        assertThat(groupEntriesFor(donor)).isEmpty();
    }

    @Test
    void aDifferentGroupWithoutConfirmationIsA409WithItsCodeAndSavesNothingNotEvenTheDonation() throws Exception {
        Donor donor = donor(BloodType.A_POSITIVE);
        long auditBefore = auditLogRepository.count();

        recordDonation(staffToken, donor, "B+", null).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BLOOD_GROUP_CHANGE_UNCONFIRMED"))
            .andExpect(jsonPath("$.message").value(
                "This donor's profile says A+, but the tested group is B+. Confirm the change to update their profile."));
        // An explicit false is the same as no confirmation.
        recordDonation(staffToken, donor, "B+", false).andExpect(status().isConflict());
        flush();

        assertThat(profileGroupOf(donor)).isEqualTo(BloodType.A_POSITIVE);
        assertThat(count("select count(d) from Donation d where d.donor.id = :id", donor.getId())).isZero();
        assertThat(count("select count(b) from BloodInventoryBatch b where b.bloodBank.id = :id", bank.getId())).isZero();
        assertThat(donorRepository.findById(donor.getId()).orElseThrow().getLastDonationDate()).isNull();
        assertThat(auditLogRepository.count()).isEqualTo(auditBefore);
    }

    @Test
    void aConfirmedDifferentGroupUpdatesTheProfileAuditsItAndTheDonationKeepsTheTestedGroup() throws Exception {
        Donor donor = donor(BloodType.A_POSITIVE);

        recordDonation(staffToken, donor, "B+", true).andExpect(status().isCreated())
            .andExpect(jsonPath("$.profileBloodTypeUpdated").value(true));
        flush();

        assertThat(profileGroupOf(donor)).isEqualTo(BloodType.B_POSITIVE);
        Object donationGroup = entityManager
            .createQuery("select d.bloodType from Donation d where d.donor.id = :id")
            .setParameter("id", donor.getId()).getSingleResult();
        assertThat(donationGroup).isEqualTo(BloodType.B_POSITIVE);
        assertThat(groupEntriesFor(donor)).singleElement().satisfies(entry -> assertThat(entry.getDetails())
            .isEqualTo("Blood group changed from A+ to B+. Source: donation at " + bank.getName() + "."));
    }

    // ---------- Part 2: manual correction ----------

    @Test
    void staffOfAnyBankCanSetAndThenCorrectAGroupWithANote() throws Exception {
        // A donor who never gave at either bank: donors are city-wide.
        Donor donor = donor(null);

        correct(otherStaffToken, donor, "A-", "Typed at the screening clinic").andExpect(status().isOk())
            .andExpect(jsonPath("$.previousBloodType").value(nullValue()))
            .andExpect(jsonPath("$.bloodType").value("A-"));
        correct(staffToken, donor, "AB-", "Retested at our lab, sample 4471").andExpect(status().isOk())
            .andExpect(jsonPath("$.previousBloodType").value("A-"))
            .andExpect(jsonPath("$.bloodType").value("AB-"));
        flush();

        assertThat(profileGroupOf(donor)).isEqualTo(BloodType.AB_NEGATIVE);
        List<AuditLog> entries = groupEntriesFor(donor);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).getActor().getId()).isEqualTo(staff.getId());
        assertThat(entries.get(1).getDetails()).isEqualTo("Blood group changed from A- to AB-. Source: manual "
            + "correction by staff of " + bank.getName() + ". Note: Retested at our lab, sample 4471");
    }

    @Test
    void aNoteIsRequiredAndBoundedAndTheSameGroupIsRefused() throws Exception {
        Donor donor = donor(BloodType.O_NEGATIVE);

        correct(staffToken, donor, "O+", "   ").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.note").exists());
        correct(staffToken, donor, "O+", null).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.note").exists());
        correct(staffToken, donor, "O+", "x".repeat(501)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.note").value("The note must be 500 characters or fewer"));
        correct(staffToken, donor, "O-", "Retested").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.bloodType").exists());
        correct(staffToken, donor, "Z+", "Retested").andExpect(status().isBadRequest());
        flush();

        assertThat(profileGroupOf(donor)).isEqualTo(BloodType.O_NEGATIVE);
        assertThat(groupEntriesFor(donor)).isEmpty();
    }

    @Test
    void administratorsHospitalStaffDonorsAndSuspendedStaffAreRefused() throws Exception {
        Donor donor = donor(BloodType.A_POSITIVE);
        String donorToken = signIn(donor.getUser());
        User suspended = user("Typing Suspended Technician", Role.BLOOD_BANK_STAFF, bank, null);
        String suspendedToken = signIn(suspended);
        suspended.setStatus(AccountStatus.SUSPENDED);
        userRepository.save(suspended);
        flush();

        correct(adminToken, donor, "B+", "Retested").andExpect(status().isForbidden());
        correct(hospitalToken, donor, "B+", "Retested").andExpect(status().isForbidden());
        correct(donorToken, donor, "B+", "Retested").andExpect(status().isForbidden());
        // A suspended account's token stops working at once (the filter leaves it unauthenticated).
        correct(suspendedToken, donor, "B+", "Retested").andExpect(status().isUnauthorized());
        flush();

        assertThat(profileGroupOf(donor)).isEqualTo(BloodType.A_POSITIVE);
        assertThat(groupEntriesFor(donor)).isEmpty();
    }

    // ---------- appeals reach the donor once typed ----------

    @Test
    void aDonorWithNoGroupGetsNoAppealUntilTheGroupIsSetThenDoes() throws Exception {
        Donor donor = donor(null);

        sendAppeal(staffToken, "B+");
        flush();
        assertThat(appealsReceivedBy(donor)).isZero();

        correct(staffToken, donor, "B+", "Typed at the screening clinic").andExpect(status().isOk());
        flush();
        // A second bank's appeal, so the first bank's cooldown (Business Rule 12) plays no part.
        sendAppeal(otherStaffToken, "B+");
        flush();
        assertThat(appealsReceivedBy(donor)).isEqualTo(1);
    }

    @Test
    void aDonorTypedByTheirFirstDonationCountsForThatGroupButIsInRecovery() throws Exception {
        Donor donor = donor(null);
        int before = reach(otherStaffToken, "AB+").get("donorsInRecovery").asInt();

        recordDonation(staffToken, donor, "AB+", null).andExpect(status().isCreated());
        flush();

        JsonNode after = reach(otherStaffToken, "AB+");
        assertThat(after.get("donorsInRecovery").asInt()).isEqualTo(before + 1);
    }

    // ---------- the Admin Audit Log shows it ----------

    @Test
    void theAuditLogPageShowsTheEntryAgainstTheDonorsAccount() throws Exception {
        Donor donor = donor(null);
        correct(staffToken, donor, "O-", "Typed at the screening clinic").andExpect(status().isOk());
        flush();

        JsonNode page = json(mockMvc.perform(get("/api/admin/audit-log?size=100")
                .header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk()).andReturn());
        JsonNode entry = null;
        for (JsonNode row : page.get("content")) {
            if (row.get("targetId").asString().equals(donor.getUser().getId().toString())) {
                entry = row;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.get("action").asString()).isEqualTo("DONOR_BLOOD_GROUP_UPDATED");
        assertThat(entry.get("targetType").asString()).isEqualTo("USER");
        assertThat(entry.get("targetRole").asString()).isEqualTo("DONOR");
        assertThat(entry.get("actorName").asString()).isEqualTo("Typing Technician");
        assertThat(entry.get("details").asString()).startsWith("Blood group set to O- (none was recorded before).");
    }

    // ---------- helpers ----------

    private ResultActions recordDonation(String token, Donor donor, String group, Boolean confirm) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
            "donorId", donor.getId().toString(), "bloodType", group, "unitsDonated", 1));
        if (confirm != null) {
            body.put("confirmBloodGroupChange", confirm);
        }
        return mockMvc.perform(post("/api/donations")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions correct(String token, Donor donor, String group, String note) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("bloodType", group);
        body.put("note", note);
        return mockMvc.perform(patch("/api/donors/{id}/blood-group", donor.getId())
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    private void sendAppeal(String token, String group) throws Exception {
        mockMvc.perform(post("/api/appeals")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("bloodType", group))))
            .andExpect(status().isCreated());
    }

    private JsonNode reach(String token, String group) throws Exception {
        return json(mockMvc.perform(get("/api/appeals/reach").param("bloodType", group)
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andReturn());
    }

    private long appealsReceivedBy(Donor donor) {
        return notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(donor.getUser().getId()).stream()
            .filter(n -> n.getType() == NotificationType.DONATION_APPEAL)
            .count();
    }

    private BloodType profileGroupOf(Donor donor) {
        return donorRepository.findById(donor.getId()).orElseThrow().getBloodType();
    }

    private List<AuditLog> groupEntriesFor(Donor donor) {
        return auditLogRepository.findByTargetIdOldestFirst(donor.getUser().getId()).stream()
            .filter(entry -> entry.getAction() == AuditAction.DONOR_BLOOD_GROUP_UPDATED)
            .toList();
    }

    private long count(String jpql, UUID id) {
        return entityManager.createQuery(jpql, Long.class).setParameter("id", id).getSingleResult();
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private Donor donor(BloodType profileGroup) {
        Donor donor = new Donor();
        donor.setUser(user("Typing Donor " + UUID.randomUUID().toString().substring(0, 6), Role.DONOR, null, null));
        donor.setBloodType(profileGroup);
        donor.setDateOfBirth(LocalDate.of(1994, 3, 2));
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
        user.setEmail(role == Role.DONOR ? null : "typing.it." + UUID.randomUUID() + "@example.com");
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

    /** Distinct local-format numbers (see AdminAccountCreationIT), with prefix 073 to avoid the other ITs' numbers. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "073" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
