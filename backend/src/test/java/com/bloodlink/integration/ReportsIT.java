package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

import com.bloodlink.admin.report.dto.ReportDtos.CountRow;
import com.bloodlink.admin.report.dto.ReportDtos.DonationReport;
import com.bloodlink.admin.report.dto.ReportDtos.DonorReport;
import com.bloodlink.admin.report.dto.ReportDtos.RequestReport;
import com.bloodlink.admin.report.dto.ReportDtos.StockBankRow;
import com.bloodlink.admin.report.dto.ReportDtos.StockReport;
import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;

import jakarta.persistence.EntityManager;
// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.ObjectMapper;

/**
 * The Admin Reports numbers from the real aggregate queries against seed data with known answers (Business Rule 23):
 * 2001-dated requests/donations, new banks for stock, donor counts as deltas. Rolled back; run -Dtest=ReportsIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportsIT {

    private static final String PASSWORD = "Correct-horse-battery-2026";
    private static final LocalDate TODAY = LocalDate.now();
    /** The stock section is as of today in UTC, StockPolicy's "today" (Business Rule 24). */
    private static final LocalDate STOCK_TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private DonorRepository donorRepository;
    @Autowired private DonationRepository donationRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodInventoryBatchRepository batchRepository;
    @Autowired private BloodRequestRepository requestRepository;
    @Autowired private BloodRequestContributionRepository contributionRepository;

    private String mark;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        mark = UUID.randomUUID().toString().substring(0, 8);
        adminToken = signIn(user("Report Administrator", Role.ADMIN));
    }

    // ---------- 1. stock ----------

    @Test
    void stockPerBankCountsCriticalAndLowAtTheThresholdEdgesAndMissingGroupsAsCritical() throws Exception {
        BloodBank seeded = bank("Report Stock Bank " + mark);
        BloodBank empty = bank("Report Empty Bank " + mark);
        stock(seeded, BloodType.A_POSITIVE, 14, STOCK_TODAY.plusDays(100));   // Critical (just under 15)
        stock(seeded, BloodType.A_NEGATIVE, 15, STOCK_TODAY.plusDays(100));   // Low (exactly 15)
        stock(seeded, BloodType.B_POSITIVE, 39, STOCK_TODAY.plusDays(100));   // Low (just under 40)
        stock(seeded, BloodType.B_NEGATIVE, 40, STOCK_TODAY.plusDays(100));   // Sufficient (exactly 40)
        flush();

        StockReport report = stockReport();

        // Critical = 5: the one row under 15 (A+) plus the 4 groups with no row at all,
        // which are zero units — Critical, exactly as the bank's own page shows them.
        assertThat(bankRow(report, seeded)).isEqualTo(new StockBankRow(seeded.getName(), 108, 5, 2));
        assertThat(bankRow(report, empty)).isEqualTo(new StockBankRow(empty.getName(), 0, 8, 0));
    }

    /**
     * An expired batch is not stock (Business Rule 24): its units appear only in pastExpiryUnits,
     * never in totals or "expiring soon".
     */
    @Test
    void stockTotalsMoveByExactlyWhatWasAddedAndAnExpiredBatchIsExcludedAndReportedSeparately() throws Exception {
        StockReport before = stockReport();
        BloodBank bank = bank("Report Expiry Bank " + mark);
        stock(bank, BloodType.O_POSITIVE, 5, STOCK_TODAY.minusDays(1));        // already expired
        stock(bank, BloodType.A_POSITIVE, 14, STOCK_TODAY.plusDays(10));       // expiring soon
        stock(bank, BloodType.AB_NEGATIVE, 40, STOCK_TODAY.plusDays(100));     // fine
        flush();

        StockReport after = stockReport();

        // 14 + 40 usable; the 5 expired units are not stock.
        assertThat(after.totalUnits() - before.totalUnits()).isEqualTo(54);
        // Only the 14 usable units within 28 days; the expired 5 are not "expiring soon".
        assertThat(after.expiringSoonUnits() - before.expiringSoonUnits()).isEqualTo(14);
        assertThat(after.pastExpiryUnits() - before.pastExpiryUnits()).isEqualTo(5);
        assertThat(after.expiringSoonWindowDays()).isEqualTo(28);
        assertThat(units(after, "O+") - units(before, "O+")).isZero();
        assertThat(units(after, "A+") - units(before, "A+")).isEqualTo(14);
        assertThat(units(after, "AB-") - units(before, "AB-")).isEqualTo(40);
        assertThat(after.groups()).hasSize(8);
        // And the bank's own row counts the expired group as the zero it is: Critical.
        assertThat(bankRow(after, bank)).isEqualTo(new StockBankRow(bank.getName(), 54, 7, 0));
    }

    /** A batch expiring today is still stock (usable through its expiry date). */
    @Test
    void aBatchExpiringTodayIsStillCountedAsUsableStock() throws Exception {
        StockReport before = stockReport();
        BloodBank bank = bank("Report Today Bank " + mark);
        stock(bank, BloodType.O_NEGATIVE, 6, STOCK_TODAY);
        flush();

        StockReport after = stockReport();

        assertThat(after.totalUnits() - before.totalUnits()).isEqualTo(6);
        assertThat(after.expiringSoonUnits() - before.expiringSoonUnits()).isEqualTo(6);
        assertThat(after.pastExpiryUnits() - before.pastExpiryUnits()).isZero();
    }

    // ---------- 2. requests ----------

    @Test
    void requestTotalsInARangeAreExactIncludingOneFilledByTwoBanks() throws Exception {
        BloodBank first = bank("Report Bank One " + mark);
        BloodBank second = bank("Report Bank Two " + mark);

        BloodRequest twoBanks = request(BloodType.O_POSITIVE, UrgencyLevel.EMERGENCY, RequestStatus.FULFILLED, "2001-03-10T09:00:00Z");
        contribution(twoBanks, first, 2);
        contribution(twoBanks, second, 2);
        BloodRequest oneBank = request(BloodType.A_POSITIVE, UrgencyLevel.URGENT, RequestStatus.FULFILLED, "2001-03-15T09:00:00Z");
        contribution(oneBank, first, 3);
        contribution(oneBank, first, 1);   // the same bank twice is still one bank
        request(BloodType.B_POSITIVE, UrgencyLevel.ROUTINE, RequestStatus.REJECTED, "2001-06-01T09:00:00Z");
        request(BloodType.O_POSITIVE, UrgencyLevel.ROUTINE, RequestStatus.PENDING, "2001-07-01T09:00:00Z");
        BloodRequest partial = request(BloodType.AB_NEGATIVE, UrgencyLevel.URGENT, RequestStatus.PARTIALLY_FULFILLED,
            "2001-12-31T23:30:00Z");                                       // last minutes of the last day: in
        contribution(partial, second, 1);
        request(BloodType.O_NEGATIVE, UrgencyLevel.EMERGENCY, RequestStatus.PENDING, "2002-01-01T00:00:00Z"); // out
        flush();

        RequestReport report = page("/api/admin/reports/summary/requests?from=2001-01-01&to=2001-12-31", RequestReport.class);

        assertThat(report.total()).isEqualTo(5);
        assertThat(report.byStatus()).containsExactly(
            new CountRow("PENDING", 1), new CountRow("PARTIALLY_FULFILLED", 1),
            new CountRow("FULFILLED", 2), new CountRow("REJECTED", 1));
        assertThat(report.decided()).isEqualTo(3);
        assertThat(report.fulfilmentRate()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(report.filledByMoreThanOneBank()).isEqualTo(1);
        assertThat(count(report.byBloodType(), "O+")).isEqualTo(2);
        assertThat(count(report.byBloodType(), "AB-")).isEqualTo(1);
        assertThat(count(report.byBloodType(), "O-")).isZero();               // the 2002 request is outside
        assertThat(report.byBloodType()).hasSize(8);
        assertThat(report.byUrgency()).containsExactly(
            new CountRow("ROUTINE", 2), new CountRow("URGENT", 2), new CountRow("EMERGENCY", 1));
    }

    @Test
    void aRangeWithNoRequestsHasNoFulfilmentRateRatherThanZeroOrNaN() throws Exception {
        mockMvc.perform(get("/api/admin/reports/summary/requests").param("from", "1990-01-01").param("to", "1990-12-31")
                .header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.fulfilmentRate").doesNotExist());
    }

    // ---------- 3. donations ----------

    @Test
    void donationsPerMonthBankAndGroupAreExactAndEmptyMonthsAreZero() throws Exception {
        BloodBank first = bank("Report Donation Bank One " + mark);
        BloodBank second = bank("Report Donation Bank Two " + mark);
        Donor donor = donor("Report Donor " + mark, BloodType.O_POSITIVE, LocalDate.of(2001, 3, 5));
        donation(donor, first, LocalDate.of(2001, 1, 1), BloodType.O_POSITIVE);    // first day: in
        donation(donor, second, LocalDate.of(2001, 1, 20), BloodType.O_POSITIVE);
        donation(donor, first, LocalDate.of(2001, 3, 31), BloodType.A_POSITIVE);   // last day: in
        donation(donor, first, LocalDate.of(2001, 4, 1), BloodType.B_POSITIVE);    // the day after: out
        flush();

        DonationReport report = page("/api/admin/reports/summary/donations?from=2001-01-01&to=2001-03-31", DonationReport.class);

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.byMonth()).extracting(m -> m.label() + "=" + m.count())
            .containsExactly("Jan 2001=2", "Feb 2001=0", "Mar 2001=1");
        assertThat(count(report.byBank(), first.getName())).isEqualTo(2);
        assertThat(count(report.byBank(), second.getName())).isEqualTo(1);
        assertThat(count(report.byBloodType(), "O+")).isEqualTo(2);
        assertThat(count(report.byBloodType(), "A+")).isEqualTo(1);
        assertThat(count(report.byBloodType(), "B+")).isZero();
    }

    // ---------- 4. donors ----------

    @Test
    void donorCountsMoveExactlyAtTheNinetyDayEdgeAndUnknownGroupIsCounted() throws Exception {
        DonorReport before = page("/api/admin/reports/summary/donors", DonorReport.class);
        donor("Never Donated " + mark, BloodType.A_POSITIVE, null);                 // eligible
        donor("Ninety One Days " + mark, BloodType.A_POSITIVE, TODAY.minusDays(91)); // eligible
        donor("Ninety Days " + mark, BloodType.B_NEGATIVE, TODAY.minusDays(90));     // recovery: day 90 is still in
        donor("Not Yet Typed " + mark, null, TODAY.minusDays(10));                   // recovery, group unknown
        flush();

        DonorReport after = page("/api/admin/reports/summary/donors", DonorReport.class);

        assertThat(after.total() - before.total()).isEqualTo(4);
        assertThat(after.eligible() - before.eligible()).isEqualTo(2);
        assertThat(after.inRecovery() - before.inRecovery()).isEqualTo(2);
        assertThat(count(after.byBloodType(), "A+") - count(before.byBloodType(), "A+")).isEqualTo(2);
        assertThat(count(after.byBloodType(), "Unknown") - count(before.byBloodType(), "Unknown")).isEqualTo(1);
        assertThat(after.eligible() + after.inRecovery()).isEqualTo(after.total());
    }

    @Test
    void theEligibleCountIsExactlyWhatIsEligibleToDonateSaysForEveryDonor() throws Exception {
        donor("Edge A " + mark, BloodType.O_POSITIVE, TODAY.minusDays(90));
        donor("Edge B " + mark, BloodType.O_POSITIVE, TODAY.minusDays(91));
        donor("Edge C " + mark, BloodType.O_POSITIVE, null);
        flush();

        DonorReport report = page("/api/admin/reports/summary/donors", DonorReport.class);

        // Loading every donor is fine in a test: it is the reference answer the
        // database count must match.
        long javaCount = donorRepository.findAll().stream().filter(Donor::isEligibleToDonate).count();
        assertThat(report.eligible()).isEqualTo(javaCount);
        assertThat(report.total()).isEqualTo(donorRepository.count());
    }

    // ---------- no personal data, anywhere ----------

    @Test
    void noSectionAndNoCsvCarriesADonorsNamePhoneOrEmail() throws Exception {
        Donor donor = donor("Private Person " + mark, BloodType.O_POSITIVE, LocalDate.of(2001, 2, 2));
        donation(donor, bank("Report Privacy Bank " + mark), LocalDate.of(2001, 2, 2), BloodType.O_POSITIVE);
        flush();
        List<String> secrets = List.of(donor.getUser().getFullName(), donor.getUser().getPhone(),
            donor.getUser().getEmail(), donor.getAddress());

        for (String path : List.of("stock", "requests?from=2001-01-01&to=2001-12-31",
                "donations?from=2001-01-01&to=2001-12-31", "donors")) {
            String json = raw("/api/admin/reports/summary/" + path);
            String csv = raw("/api/admin/reports/summary/" + path.replaceFirst("(\\?|$)", "/export$1"));
            for (String secret : secrets) {
                assertThat(json).as(path + " json").doesNotContain(secret);
                assertThat(csv).as(path + " csv").doesNotContain(secret);
            }
        }
    }

    // ---------- CSV ----------

    @Test
    void theCsvIsUtf8WithABomNamedForItsSectionAndRangeAndDefusesFormulas() throws Exception {
        bank("=HYPERLINK(\"http://evil\") " + mark);   // a bank name an administrator could type
        flush();

        MvcResult result = mockMvc.perform(get("/api/admin/reports/summary/stock/export").header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
            .andExpect(header().string("Content-Disposition",
                "attachment; filename=\"bloodlink-report-stock-" + STOCK_TODAY + ".csv\""))
            .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        String text = new String(body, StandardCharsets.UTF_8);

        assertThat(body[0]).isEqualTo((byte) 0xEF);
        assertThat(body[1]).isEqualTo((byte) 0xBB);
        assertThat(body[2]).isEqualTo((byte) 0xBF);
        // Apostrophe-prefixed so a spreadsheet shows it as text, and quotes doubled.
        assertThat(text).contains("\"'=HYPERLINK(\"\"http://evil\"\") " + mark + "\"");
        assertThat(text).doesNotContain("\"=HYPERLINK");
        // The stock section says plainly that it counts usable stock, and that expired
        // units are reported apart from it (Business Rules 23 and 24).
        assertThat(text)
            .contains("\"Total usable units\"")
            .contains("\"Usable units expiring within 28 days\"")
            .contains("\"Expired units awaiting discard (not counted as stock)\"")
            .doesNotContain("including already expired");

        // Called from the frontend's own origin, the file name header must be readable
        // by the page (CORS-exposed), or every download gets a generic name.
        mockMvc.perform(get("/api/admin/reports/summary/requests/export").param("from", "2001-01-01").param("to", "2001-12-31")
                .header("Authorization", bearer(adminToken))
                .header("Origin", "http://localhost:5173"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",
                "attachment; filename=\"bloodlink-report-requests-2001-01-01-to-2001-12-31.csv\""))
            .andExpect(header().string("Access-Control-Expose-Headers", containsString("Content-Disposition")));
    }

    // ---------- date range validation ----------

    @Test
    void aBackwardsRangeABadDateAndARangeOverFiveYearsAreEach400NamingTheField() throws Exception {
        expect400("/api/admin/reports/summary/requests?from=2026-06-01&to=2026-05-01", "from");
        expect400("/api/admin/reports/summary/donations?from=2026-13-01&to=2026-05-01", "from");
        expect400("/api/admin/reports/summary/donations?from=2026-01-01&to=not-a-date", "to");
        expect400("/api/admin/reports/summary/requests?from=2020-01-01&to=2025-01-02", "to");
        expect400("/api/admin/reports/summary/requests/export?from=2026-06-01&to=2026-05-01", "from");

        // Exactly five years is allowed; no dates at all means the last six months.
        mockMvc.perform(get("/api/admin/reports/summary/requests?from=2020-01-01&to=2025-01-01")
                .header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk());
        RequestReport defaults = page("/api/admin/reports/summary/requests", RequestReport.class);
        assertThat(defaults.to()).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));
        assertThat(defaults.from()).isEqualTo(java.time.YearMonth.from(defaults.to()).minusMonths(5).atDay(1));
    }

    // ---------- who may see it ----------

    @Test
    void donorHospitalAndBankStaffAreRefusedAndNoTokenIsUnauthenticated() throws Exception {
        List<String> paths = List.of("/api/admin/reports/summary/stock", "/api/admin/reports/summary/requests",
            "/api/admin/reports/summary/donations", "/api/admin/reports/summary/donors",
            "/api/admin/reports/summary/donors/export");
        for (Role role : List.of(Role.DONOR, Role.HOSPITAL_STAFF, Role.BLOOD_BANK_STAFF)) {
            String token = signIn(user("Not Admin", role));
            for (String path : paths) {
                mockMvc.perform(get(path).header("Authorization", bearer(token))).andExpect(status().isForbidden());
            }
        }
        for (String path : paths) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    // ---------- seed helpers ----------

    private BloodBank bank(String name) {
        BloodBank bank = new BloodBank();
        bank.setName(name);
        bank.setLocation("Makeni");
        bank.setContactPhone("076 000 000");
        return bloodBankRepository.save(bank);
    }

    /** One batch of stock: since Business Rule 24 a bank's stock is its batches. */
    private void stock(BloodBank bank, BloodType type, int units, LocalDate expiry) {
        batchRepository.save(BloodInventoryBatch.manual(bank, type, units, expiry));
    }

    private BloodRequest request(BloodType type, UrgencyLevel urgency, RequestStatus status, String createdAt) {
        BloodRequest request = new BloodRequest();
        request.setHospital(hospitalRepository.findAllByOrderByNameAsc().get(0));
        request.setBloodType(type);
        request.setUnitsRequested(4);
        request.setUrgencyLevel(urgency);
        request.setStatus(status);
        request.setCreatedAt(Instant.parse(createdAt));
        return requestRepository.save(request);
    }

    private void contribution(BloodRequest request, BloodBank bank, int units) {
        BloodRequestContribution contribution = new BloodRequestContribution();
        contribution.setBloodRequest(request);
        contribution.setBloodBank(bank);
        contribution.setUnitsContributed(units);
        contributionRepository.save(contribution);
    }

    private Donor donor(String name, BloodType type, LocalDate lastDonation) {
        User user = user(name, Role.DONOR);
        Donor donor = new Donor();
        donor.setUser(user);
        donor.setBloodType(type);
        donor.setDateOfBirth(LocalDate.of(1990, 1, 1));
        donor.setGender(Gender.FEMALE);
        donor.setLastDonationDate(lastDonation);
        donor.setAddress("Private Street " + mark);
        return donorRepository.save(donor);
    }

    private void donation(Donor donor, BloodBank bank, LocalDate date, BloodType type) {
        Donation donation = new Donation();
        donation.setDonor(donor);
        donation.setBloodBank(bank);
        donation.setDonationDate(date);
        donation.setUnitsDonated(1);
        donation.setBloodType(type);
        donationRepository.save(donation);
    }

    private User user(String name, Role role) {
        User user = new User();
        user.setFullName(name);
        user.setEmail("reports.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        if (role == Role.BLOOD_BANK_STAFF) user.setBloodBank(bloodBankRepository.findAllByOrderByNameAsc().get(0));
        if (role == Role.HOSPITAL_STAFF) user.setHospital(hospitalRepository.findAllByOrderByNameAsc().get(0));
        return userRepository.save(user);
    }

    private void flush() {
        entityManager.flush();
    }

    // ---------- reading helpers ----------

    private StockReport stockReport() throws Exception {
        return page("/api/admin/reports/summary/stock", StockReport.class);
    }

    private <T> T page(String path, Class<T> type) throws Exception {
        return objectMapper.readValue(raw(path), type);
    }

    private String raw(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk())
            .andReturn();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void expect400(String path, String field) throws Exception {
        mockMvc.perform(get(path).header("Authorization", bearer(adminToken)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors." + field).exists());
    }

    private static StockBankRow bankRow(StockReport report, BloodBank bank) {
        return report.banks().stream().filter(row -> row.bankName().equals(bank.getName())).findFirst().orElseThrow();
    }

    private static long units(StockReport report, String group) {
        return report.groups().stream().filter(row -> row.bloodType().equals(group)).findFirst().orElseThrow().units();
    }

    private static long count(List<CountRow> rows, String label) {
        return rows.stream().filter(row -> row.label().equals(label)).findFirst().orElseThrow().count();
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
        return "077" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
