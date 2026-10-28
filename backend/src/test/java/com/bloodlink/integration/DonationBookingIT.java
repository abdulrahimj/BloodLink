package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.auth.dto.LoginRequest;
import com.bloodlink.booking.DonationBookingService;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.DonationBooking;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BookingStatus;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.notification.BatchExpiryNotificationJob;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.DonationBookingRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;

import jakarta.persistence.EntityManager;
// Jackson 3 under Spring Boot 4 — see the same note on BloodRequestLifecycleIT.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Business Rule 29 end to end: booking, refusals, cancelling, Record Donation closing a booking, the staff list
 * and the daily job (reminder once, catch-up, missed). A MutableClock moves the date. @Transactional, rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(BatchStockCutoverIT.MutableClockConfig.class)
class DonationBookingIT {

    private static final String PASSWORD = "correct-horse-battery-2026";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private DonorRepository donorRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private DonationBookingRepository bookingRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private DonationBookingService donationBookingService;
    @Autowired private BatchStockCutoverIT.MutableClock clock;

    private BatchExpiryNotificationJob job;
    private BloodBank bank;
    private BloodBank otherBank;
    private User staff;
    private User suspendedStaff;
    private User otherStaff;
    private String staffToken;
    private String otherStaffToken;
    private String hospitalToken;
    private String adminToken;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        today = LocalDate.now(clock);
        job = new BatchExpiryNotificationJob(notificationService, bloodBankRepository, donationBookingService, clock,
            BatchExpiryNotificationJob.DEFAULT_CRON);
        bank = newBank("Booking Bank");
        otherBank = newBank("Booking Other Bank");
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        staff = user("Booking Technician", Role.BLOOD_BANK_STAFF, bank, null);
        suspendedStaff = user("Booking Suspended Technician", Role.BLOOD_BANK_STAFF, bank, null);
        suspendedStaff.setStatus(AccountStatus.SUSPENDED);
        userRepository.save(suspendedStaff);
        otherStaff = user("Booking Other Technician", Role.BLOOD_BANK_STAFF, otherBank, null);
        staffToken = signIn(staff);
        otherStaffToken = signIn(otherStaff);
        hospitalToken = signIn(user("Booking Ward Clerk", Role.HOSPITAL_STAFF, null, hospital));
        adminToken = signIn(user("Booking Administrator", Role.ADMIN, null, null));
    }

    // ---------- booking ----------

    @Test
    void anEligibleActiveDonorBooksAndTheBanksActiveStaffAreTold() throws Exception {
        Donor donor = donor("Aminata Kamara", null);
        String token = signIn(donor.getUser());

        book(token, bank, today.plusDays(3), "AFTERNOON").andExpect(status().isCreated())
            .andExpect(jsonPath("$.bloodBankName").value(bank.getName()))
            .andExpect(jsonPath("$.bookingDate").value(today.plusDays(3).toString()))
            .andExpect(jsonPath("$.timeOfDay").value("AFTERNOON"))
            .andExpect(jsonPath("$.status").value("BOOKED"));
        flush();

        assertThat(messages(staff, NotificationType.DONATION_BOOKED)).containsExactly(
            "Aminata Kamara booked a donation for " + DATE.format(today.plusDays(3)) + ", afternoon.");
        assertThat(messages(suspendedStaff, NotificationType.DONATION_BOOKED)).isEmpty();
        assertThat(messages(otherStaff, NotificationType.DONATION_BOOKED)).isEmpty();
        mockMvc.perform(get("/api/bookings/me").header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.bloodBankId").value(bank.getId().toString()));
    }

    @Test
    void badDatesTimesBanksAndASecondOpenBookingAreRefusedAndNothingExtraIsSaved() throws Exception {
        Donor donor = donor("Fatmata Sesay", null);
        String token = signIn(donor.getUser());

        book(token, bank, today.minusDays(1), "MORNING").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.bookingDate").value("The date cannot be in the past"));
        book(token, bank, today.plusDays(31), "MORNING").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.bookingDate").value("Choose a date within the next 30 days"));
        book(token, bank, today.plusDays(1), "EVENING").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.timeOfDay").exists());
        Map<String, Object> noBank = new HashMap<>(Map.of("bookingDate", today.plusDays(1).toString(), "timeOfDay", "MORNING"));
        postBooking(token, noBank).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.bloodBankId").exists());
        BloodBank missing = new BloodBank();
        missing.setId(UUID.randomUUID());
        book(token, missing, today.plusDays(1), "MORNING").andExpect(status().isNotFound());

        book(token, bank, today.plusDays(30), "MORNING").andExpect(status().isCreated());
        book(token, otherBank, today.plusDays(2), "AFTERNOON").andExpect(status().isConflict());
        flush();

        assertThat(bookingRepository.findByDonorIdOrderByCreatedAtAsc(donor.getId())).singleElement()
            .satisfies(b -> assertThat(b.getBookingDate()).isEqualTo(today.plusDays(30)));
    }

    @Test
    void anIneligibleOrSuspendedDonorAndEveryOtherRoleAreRefused() throws Exception {
        Donor recovering = donor("Recovering Donor", LocalDate.now().minusDays(10));
        book(signIn(recovering.getUser()), bank, today.plusDays(1), "MORNING").andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("You can book a donation from")));

        Donor suspended = donor("Suspended Donor", null);
        String suspendedToken = signIn(suspended.getUser());
        suspended.getUser().setStatus(AccountStatus.SUSPENDED);
        userRepository.save(suspended.getUser());
        flush();
        // A suspended account's token stops working at once (the filter leaves it unauthenticated).
        book(suspendedToken, bank, today.plusDays(1), "MORNING").andExpect(status().isUnauthorized());

        for (String token : List.of(staffToken, hospitalToken, adminToken)) {
            book(token, bank, today.plusDays(1), "MORNING").andExpect(status().isForbidden());
            mockMvc.perform(get("/api/bookings/me").header("Authorization", bearer(token))).andExpect(status().isForbidden());
        }
        flush();

        assertThat(bookingRepository.findByDonorIdOrderByCreatedAtAsc(recovering.getId())).isEmpty();
        assertThat(bookingRepository.findByDonorIdOrderByCreatedAtAsc(suspended.getId())).isEmpty();
    }

    // ---------- cancelling ----------

    @Test
    void aDonorCancelsOnlyTheirOwnOpenBookingAndTheBankIsTold() throws Exception {
        Donor donor = donor("Mariama Bangura", null);
        String token = signIn(donor.getUser());
        Donor someoneElse = donor("Someone Else", null);
        book(token, bank, today, "MORNING").andExpect(status().isCreated());

        // There is no booking id in the address: another donor can only ever reach their own (none).
        cancel(signIn(someoneElse.getUser())).andExpect(status().isNotFound());
        cancel(token).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        cancel(token).andExpect(status().isNotFound());
        flush();

        assertThat(messages(staff, NotificationType.DONATION_BOOKING_CANCELLED)).containsExactly(
            "Mariama Bangura cancelled their donation booking for " + DATE.format(today) + ", morning.");
        mockMvc.perform(get("/api/bookings/me").header("Authorization", bearer(token))).andExpect(status().isNoContent());
        // The booking is closed, so a new one may be made.
        book(token, otherBank, today.plusDays(1), "AFTERNOON").andExpect(status().isCreated());
    }

    @Test
    void aBookingCannotBeCancelledOnceItsDateHasPassed() throws Exception {
        Donor donor = donor("Late Canceller", null);
        String token = signIn(donor.getUser());
        book(token, bank, today, "AFTERNOON").andExpect(status().isCreated());
        flush();

        clock.advance(Duration.ofDays(1));
        cancel(token).andExpect(status().isConflict());
        flush();

        assertThat(statusOf(donor)).isEqualTo(BookingStatus.BOOKED);
        assertThat(messages(staff, NotificationType.DONATION_BOOKING_CANCELLED)).isEmpty();
    }

    // ---------- Record Donation closes the booking ----------

    @Test
    void aDonationAtTheBookedBankMarksTheBookingAttendedAndLinksIt() throws Exception {
        Donor donor = donor("Attending Donor", null);
        book(signIn(donor.getUser()), bank, today, "MORNING").andExpect(status().isCreated());

        JsonNode receipt = json(recordDonation(staffToken, donor).andExpect(status().isCreated()).andReturn());
        flush();

        DonationBooking booking = bookingRepository.findByDonorIdOrderByCreatedAtAsc(donor.getId()).get(0);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.ATTENDED);
        assertThat(booking.getDonation().getId().toString()).isEqualTo(receipt.get("donationId").asString());
        assertThat(booking.getClosedAt()).isNotNull();
        assertThat(messages(staff, NotificationType.DONATION_BOOKING_CANCELLED)).isEmpty();
    }

    @Test
    void aDonationAtAnotherBankCancelsTheBookingAndTellsTheBookedBankOnly() throws Exception {
        Donor donor = donor("Elsewhere Donor", null);
        book(signIn(donor.getUser()), bank, today.plusDays(5), "AFTERNOON").andExpect(status().isCreated());

        recordDonation(otherStaffToken, donor).andExpect(status().isCreated());
        flush();

        DonationBooking booking = bookingRepository.findByDonorIdOrderByCreatedAtAsc(donor.getId()).get(0);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getDonation()).isNull();
        assertThat(messages(staff, NotificationType.DONATION_BOOKING_CANCELLED)).containsExactly(
            "Elsewhere Donor's donation booking for " + DATE.format(today.plusDays(5))
                + ", afternoon was cancelled automatically: they have since donated at another blood bank.");
        assertThat(messages(otherStaff, NotificationType.DONATION_BOOKING_CANCELLED)).isEmpty();
    }

    // ---------- the staff list ----------

    @Test
    void staffSeeTheirOwnBanksUpcomingOpenVisitsOnlyInDateAndHalfDayOrder() throws Exception {
        Donor afternoonToday = donor("Afternoon Today", null);
        Donor morningTomorrow = donor("Morning Tomorrow", null);
        Donor morningToday = donor("Morning Today", null);
        morningToday.setBloodType(com.bloodlink.domain.enums.BloodType.O_NEGATIVE);
        donorRepository.save(morningToday);
        Donor cancelled = donor("Cancelled Visit", null);
        Donor elsewhere = donor("Other Bank Visit", null);
        book(signIn(afternoonToday.getUser()), bank, today, "AFTERNOON").andExpect(status().isCreated());
        book(signIn(morningTomorrow.getUser()), bank, today.plusDays(1), "MORNING").andExpect(status().isCreated());
        book(signIn(morningToday.getUser()), bank, today, "MORNING").andExpect(status().isCreated());
        String cancelledToken = signIn(cancelled.getUser());
        book(cancelledToken, bank, today.plusDays(2), "MORNING").andExpect(status().isCreated());
        cancel(cancelledToken).andExpect(status().isOk());
        book(signIn(elsewhere.getUser()), otherBank, today.plusDays(1), "MORNING").andExpect(status().isCreated());
        flush();

        JsonNode rows = upcoming(staffToken);
        assertThat(rows).extracting(row -> row.get("donorName").asString())
            .containsExactly("Morning Today", "Afternoon Today", "Morning Tomorrow");
        assertThat(rows.get(0).get("bloodType").asString()).isEqualTo("O-");
        assertThat(rows.get(1).get("bloodType").isNull()).isTrue();
        assertThat(upcoming(otherStaffToken)).extracting(row -> row.get("donorName").asString())
            .containsExactly("Other Bank Visit");

        // Tomorrow, today's visits are no longer upcoming.
        clock.advance(Duration.ofDays(1));
        assertThat(upcoming(staffToken)).extracting(row -> row.get("donorName").asString())
            .containsExactly("Morning Tomorrow");

        for (String token : List.of(adminToken, hospitalToken, signIn(elsewhere.getUser()))) {
            mockMvc.perform(get("/api/bookings/upcoming").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        }
    }

    // ---------- the daily job ----------

    @Test
    void theReminderGoesOnceTheDayBeforeAndTheStartupCatchUpSendsADueOne() throws Exception {
        Donor tomorrow = donor("Tomorrow Donor", null);
        Donor later = donor("Later Donor", null);
        book(signIn(tomorrow.getUser()), bank, today.plusDays(1), "MORNING").andExpect(status().isCreated());
        book(signIn(later.getUser()), otherBank, today.plusDays(2), "AFTERNOON").andExpect(status().isCreated());
        flush();

        job.runBookings();
        job.runBookings();
        flush();
        assertThat(messages(tomorrow.getUser(), NotificationType.DONATION_BOOKING_REMINDER))
            .containsExactly("Your donation at " + bank.getName() + " is tomorrow morning.");
        assertThat(messages(later.getUser(), NotificationType.DONATION_BOOKING_REMINDER)).isEmpty();

        // The next day the server starts at 09:00, after the 06:00 run it missed: the catch-up sends the due one.
        moveClockTo(today.plusDays(1), 9);
        job.catchUpOnStartup();
        flush();
        assertThat(messages(later.getUser(), NotificationType.DONATION_BOOKING_REMINDER))
            .containsExactly("Your donation at " + otherBank.getName() + " is tomorrow afternoon.");
        assertThat(messages(tomorrow.getUser(), NotificationType.DONATION_BOOKING_REMINDER)).hasSize(1);
    }

    @Test
    void aSuspendedDonorGetsNoReminder() throws Exception {
        Donor donor = donor("Suspended Before Visit", null);
        book(signIn(donor.getUser()), bank, today.plusDays(1), "MORNING").andExpect(status().isCreated());
        donor.getUser().setStatus(AccountStatus.SUSPENDED);
        userRepository.save(donor.getUser());
        flush();

        job.runBookings();
        flush();

        assertThat(messages(donor.getUser(), NotificationType.DONATION_BOOKING_REMINDER)).isEmpty();
        assertThat(bookingRepository.findByDonorIdOrderByCreatedAtAsc(donor.getId()).get(0).getReminderSentAt()).isNull();
    }

    @Test
    void anOpenBookingWhoseDatePassedBecomesMissedAndTodaysStaysBooked() throws Exception {
        Donor past = donor("Missed Donor", null);
        Donor current = donor("Current Donor", null);
        String pastToken = signIn(past.getUser());
        book(pastToken, bank, today, "MORNING").andExpect(status().isCreated());
        book(signIn(current.getUser()), bank, today.plusDays(1), "AFTERNOON").andExpect(status().isCreated());
        flush();

        clock.advance(Duration.ofDays(1));
        job.runBookings();
        flush();

        DonationBooking missed = bookingRepository.findByDonorIdOrderByCreatedAtAsc(past.getId()).get(0);
        assertThat(missed.getStatus()).isEqualTo(BookingStatus.MISSED);
        assertThat(missed.getClosedAt()).isNotNull();
        assertThat(statusOf(current)).isEqualTo(BookingStatus.BOOKED);
        mockMvc.perform(get("/api/bookings/me").header("Authorization", bearer(pastToken))).andExpect(status().isNoContent());
    }

    // ---------- helpers ----------

    private ResultActions book(String token, BloodBank at, LocalDate date, String timeOfDay) throws Exception {
        return postBooking(token, Map.of("bloodBankId", at.getId().toString(), "bookingDate", date.toString(), "timeOfDay", timeOfDay));
    }

    private ResultActions postBooking(String token, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/bookings")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions cancel(String token) throws Exception {
        return mockMvc.perform(post("/api/bookings/me/cancel").header("Authorization", bearer(token)));
    }

    private JsonNode upcoming(String token) throws Exception {
        return json(mockMvc.perform(get("/api/bookings/upcoming").header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andReturn());
    }

    private ResultActions recordDonation(String token, Donor donor) throws Exception {
        return mockMvc.perform(post("/api/donations")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "donorId", donor.getId().toString(), "bloodType", "O+", "unitsDonated", 1))));
    }

    private void moveClockTo(LocalDate date, int hourUtc) {
        clock.advance(Duration.between(clock.instant(), date.atTime(hourUtc, 0).toInstant(ZoneOffset.UTC)));
    }

    private BookingStatus statusOf(Donor donor) {
        return bookingRepository.findByDonorIdOrderByCreatedAtAsc(donor.getId()).get(0).getStatus();
    }

    private List<String> messages(User user, NotificationType type) {
        return notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(notification -> notification.getType() == type)
            .map(Notification::getMessage)
            .toList();
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private Donor donor(String fullName, LocalDate lastDonation) {
        Donor donor = new Donor();
        donor.setUser(user(fullName, Role.DONOR, null, null));
        donor.setDateOfBirth(LocalDate.of(1995, 6, 1));
        donor.setGender(Gender.FEMALE);
        donor.setAddress("Makeni");
        donor.setLastDonationDate(lastDonation);
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
        user.setEmail(role == Role.DONOR ? null : "booking.it." + UUID.randomUUID() + "@example.com");
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

    /** Distinct local-format numbers (see AdminAccountCreationIT), with prefix 072 to avoid the other ITs' numbers. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "072" + String.format("%06d", value);
    }

    private static final long PHONE_RUN_BASE = System.nanoTime() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
