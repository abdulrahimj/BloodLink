package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.auth.dto.RegisterRequest;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.donor.dto.DonorRosterEntryResponse;
import com.bloodlink.donor.dto.DonorRosterPageResponse;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;

// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.ObjectMapper;

/**
 * A bank's donor roster and per-bank history end to end (Business Rule 21), against a real two-table query.
 * Four donors across two banks cover the 90-day boundary and per-bank counts; rolled back. Run -Dtest=DonorRosterIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DonorRosterIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private DonorRepository donorRepository;
    @Autowired private DonationRepository donationRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;

    // ---------- scoping: only donors who gave at this bank ----------

    @Test
    void bankAsRosterIncludesOnlyDonorsWhoDonatedAtBankA() throws Exception {
        Fixture fx = new Fixture();

        List<UUID> idsAtA = rosterIds(fx.bankAToken, null);

        assertThat(idsAtA).contains(fx.aminataSesayId, fx.mohamedKamaraId, fx.aminataKoromaId);
        assertThat(idsAtA).doesNotContain(fx.fatmataContehId);
    }

    @Test
    void theHistoryRouteRefusesADonorWhoNeverGaveAtThisBank() throws Exception {
        Fixture fx = new Fixture();

        // Fatmata gave only at bank B — asking bank A for her history is a 404,
        // the same answer as an id that matches nobody at all.
        mockMvc.perform(get("/api/donors/roster/{id}/donations", fx.fatmataContehId)
                .header("Authorization", bearer(fx.bankAToken)))
            .andExpect(status().isNotFound());
    }

    // ---------- a donor who gave at both banks ----------

    @Test
    void aDonorWhoGaveAtBothBanksShowsTheRightCountAtEach() throws Exception {
        Fixture fx = new Fixture();

        assertThat(donationsHereFor(fx.aminataKoromaId, roster(fx.bankAToken, null))).isEqualTo(1);
        assertThat(donationsHereFor(fx.aminataKoromaId, roster(fx.bankBToken, null))).isEqualTo(3);
    }

    // ---------- eligibility, on each side of the 90-day line ----------

    @Test
    void theEligibleFilterExcludesADonorStillInsideTheWindow() throws Exception {
        Fixture fx = new Fixture();

        List<UUID> eligible = rosterIds(fx.bankAToken, "eligibility=ELIGIBLE");
        assertThat(eligible).contains(fx.aminataSesayId, fx.aminataKoromaId);
        // 90 days ago exactly is still the window's last ineligible day.
        assertThat(eligible).doesNotContain(fx.mohamedKamaraId);
    }

    @Test
    void theInRecoveryFilterIncludesOnlyThatBoundaryDayDonor() throws Exception {
        Fixture fx = new Fixture();

        List<UUID> recovering = rosterIds(fx.bankAToken, "eligibility=IN_RECOVERY");
        assertThat(recovering).containsExactly(fx.mohamedKamaraId);
    }

    // ---------- blood group filter and search ----------

    @Test
    void theBloodGroupFilterNarrowsToThatGroupOnly() throws Exception {
        Fixture fx = new Fixture();

        List<UUID> oNegative = rosterIds(fx.bankAToken, "bloodType=" + enc("O-"));
        assertThat(oNegative).containsExactlyInAnyOrder(fx.aminataSesayId, fx.aminataKoromaId);
    }

    @Test
    void searchingByNameMatchesBothDonorsSharingThatFirstName() throws Exception {
        Fixture fx = new Fixture();

        List<UUID> matches = rosterIds(fx.bankAToken, "q=Aminata");
        assertThat(matches).containsExactlyInAnyOrder(fx.aminataSesayId, fx.aminataKoromaId);
    }

    @Test
    void searchingByPhoneInLocalFormFindsTheSameDonorAsTheStoredInternationalForm() throws Exception {
        Fixture fx = new Fixture();
        String localForm = "0" + fx.aminataSesayPhone.substring(4); // +23276xxxxxxx -> 076xxxxxxx

        List<UUID> matches = rosterIds(fx.bankAToken, "q=" + enc(localForm));
        assertThat(matches).containsExactly(fx.aminataSesayId);
    }

    @Test
    void aPercentSignIsSearchedLiterallyNotAsAWildcardThatMatchesEveryone() throws Exception {
        Fixture fx = new Fixture();
        assertThat(rosterIds(fx.bankAToken, null)).hasSizeGreaterThanOrEqualTo(3);

        // No donor's name or phone contains "%", so a literal search finds nobody.
        // Before the shared LikePattern escaping, this returned the whole roster.
        mockMvc.perform(get("/api/donors/roster").param("q", "%").header("Authorization", bearer(fx.bankAToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---------- pagination: first, last, and past the end ----------

    @Test
    void pagingSplitsTheRosterAcrossFirstLastAndPastTheEnd() throws Exception {
        Fixture fx = new Fixture();
        // Three donors at bank A already; two more in an unused group give a clean 2-page roster of 4 at size 2.
        registerAndDonate("Extra Donor One", "AB+", fx.bankA, 91, 1);
        registerAndDonate("Extra Donor Two", "AB+", fx.bankA, 91, 1);

        mockMvc.perform(get("/api/donors/roster").header("Authorization", bearer(fx.bankAToken))
                .param("bloodType", "AB+").param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/donors/roster").header("Authorization", bearer(fx.bankAToken))
                .param("bloodType", "AB+").param("size", "1").param("page", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1));

        mockMvc.perform(get("/api/donors/roster").header("Authorization", bearer(fx.bankAToken))
                .param("bloodType", "AB+").param("size", "1").param("page", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(2));
    }

    // ---------- data minimisation ----------

    @Test
    void theRosterResponseNeverCarriesAddressEmailOrDateOfBirth() throws Exception {
        Fixture fx = new Fixture();

        String body = mockMvc.perform(get("/api/donors/roster").header("Authorization", bearer(fx.bankAToken)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"address\"", "\"email\"", "\"dateOfBirth\"");
    }

    // ---------- who may use this at all ----------

    @Test
    void aDonorHospitalStaffAndAnonymousCallerAreAllRefused() throws Exception {
        Fixture fx = new Fixture();

        mockMvc.perform(get("/api/donors/roster").header("Authorization", bearer(fx.aminataSesayToken)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/donors/roster").header("Authorization", bearer(fx.hospitalToken)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/donors/roster"))
            .andExpect(status().isUnauthorized());
    }

    // ---------- fixture and helpers ----------

    private final class Fixture {
        private final BloodBank bankA;
        private final BloodBank bankB;
        private final String bankAToken;
        private final String bankBToken;
        private final String hospitalToken;
        private final String aminataSesayToken;
        private final String aminataSesayPhone;
        private final UUID aminataSesayId;
        private final UUID mohamedKamaraId;
        private final UUID aminataKoromaId;
        private final UUID fatmataContehId;

        private Fixture() throws Exception {
            List<BloodBank> banks = bloodBankRepository.findAllByOrderByNameAsc();
            bankA = banks.get(0);
            bankB = banks.get(1);
            Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);

            bankAToken = signIn(staff("Bank A Technician", Role.BLOOD_BANK_STAFF, bankA, null));
            bankBToken = signIn(staff("Bank B Technician", Role.BLOOD_BANK_STAFF, bankB, null));
            hospitalToken = signIn(staff("Ward Clerk", Role.HOSPITAL_STAFF, null, hospital));

            Donor aminataSesay = registerAndDonate("Aminata Sesay", "O-", bankA, 91, 2);
            aminataSesayId = aminataSesay.getId();
            aminataSesayPhone = aminataSesay.getUser().getPhone();
            aminataSesayToken = signIn(aminataSesay.getUser());

            mohamedKamaraId = registerAndDonate("Mohamed Kamara", "A+", bankA, 90, 1).getId();

            // donationsHere counts rows, not units — three separate rows at
            // bank B, each its own act of giving, is what makes it 3.
            Donor aminataKoroma = registerDonor("Aminata Koroma", "O-");
            backdateDonation(aminataKoroma, bankA, LocalDate.now().minusDays(91), 1);
            backdateDonation(aminataKoroma, bankB, LocalDate.now().minusDays(95), 1);
            backdateDonation(aminataKoroma, bankB, LocalDate.now().minusDays(93), 1);
            backdateDonation(aminataKoroma, bankB, LocalDate.now().minusDays(91), 1);
            // Her most recent donation anywhere (91 days ago, shared by one row
            // at each bank) is still outside the 90-day window — eligible.
            setLastDonationDate(aminataKoroma, LocalDate.now().minusDays(91));
            aminataKoromaId = aminataKoroma.getId();

            fatmataContehId = registerAndDonate("Fatmata Conteh", "O+", bankB, 10, 2).getId();
        }
    }

    /** Registers a donor with one backdated donation at bank and a matching last_donation_date,
     * through the repositories, since the endpoint always dates donations today. */
    private Donor registerAndDonate(String fullName, String bloodType, BloodBank bank, int daysAgo, int units)
            throws Exception {
        Donor donor = registerDonor(fullName, bloodType);
        LocalDate date = LocalDate.now().minusDays(daysAgo);
        backdateDonation(donor, bank, date, units);
        setLastDonationDate(donor, date);
        return donor;
    }

    private void backdateDonation(Donor donor, BloodBank bank, LocalDate date, int units) {
        Donation donation = new Donation();
        donation.setDonor(donor);
        donation.setBloodBank(bank);
        donation.setDonationDate(date);
        donation.setUnitsDonated(units);
        donation.setBloodType(donor.getBloodType());
        donationRepository.save(donation);
    }

    private void setLastDonationDate(Donor donor, LocalDate date) {
        donor.setLastDonationDate(date);
        donorRepository.save(donor);
    }

    private Donor registerDonor(String fullName, String bloodType) throws Exception {
        String phone = uniqueLocalPhone();
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(
                    fullName, phone, null, LocalDate.of(1995, 1, 1), Gender.PREFER_NOT_TO_SAY,
                    bloodType, "Makeni", PASSWORD))))
            .andExpect(status().isCreated());

        User user = userRepository.findByPhone(PhoneNumber.normalize(phone)).orElseThrow();
        return donorRepository.findByUserId(user.getId()).orElseThrow();
    }

    /** A staff account with whichever facility its role needs, stored the way
     *  an administrator would have to create it (no self-registration path). */
    private User staff(String fullName, Role role, BloodBank bank, Hospital hospital) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail("roster.it." + UUID.randomUUID() + "@example.com");
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

    /** The roster's first page (default size 20) with an optional extra query string. */
    private DonorRosterPageResponse roster(String token, String extraQuery) throws Exception {
        String url = "/api/donors/roster" + (extraQuery == null ? "" : "?" + extraQuery);
        MvcResult result = mockMvc.perform(get(url).header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), DonorRosterPageResponse.class);
    }

    private List<UUID> rosterIds(String token, String extraQuery) throws Exception {
        return roster(token, extraQuery).content().stream().map(DonorRosterEntryResponse::donorId).toList();
    }

    private long donationsHereFor(UUID donorId, DonorRosterPageResponse page) {
        return page.content().stream()
            .filter(row -> row.donorId().equals(donorId))
            .findFirst()
            .orElseThrow()
            .donationsHere();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Distinct local-format numbers within a run — see AdminAccountCreationIT. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "076" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
