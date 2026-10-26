package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.inventory.BatchWriteOffService;
import com.bloodlink.inventory.dto.WriteOffBatchRequest;
import com.bloodlink.notification.BatchExpiryNotificationJob;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.persistence.EntityManager;

/**
 * The daily batch expiry notices end to end (Business Rule 26), on a movable clock: who is told, about which
 * batches, when, and never twice in a day. The job's scheduled bean is off in tests, so it is built here; rolled back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(BatchStockCutoverIT.MutableClockConfig.class)
class BatchExpiryNotificationIT {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final long PHONE_RUN_BASE = System.currentTimeMillis() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();

    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodInventoryBatchRepository batchRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationService notificationService;
    @Autowired private BatchWriteOffService writeOffService;
    @Autowired private BatchStockCutoverIT.MutableClock clock;

    private BatchExpiryNotificationJob job;
    private BloodBank bank;
    private BloodBank otherBank;
    private User staff;
    private User secondStaff;
    private User suspendedStaff;
    private User otherBankStaff;
    private User admin;
    private User hospitalStaff;
    private User donor;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        clock.reset();
        today = LocalDate.now(clock);
        job = new BatchExpiryNotificationJob(
            notificationService, bloodBankRepository, clock, BatchExpiryNotificationJob.DEFAULT_CRON);
        bank = newBank("Expiry Bank");
        otherBank = newBank("Expiry Other Bank");
        Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        staff = user("Expiry Technician", Role.BLOOD_BANK_STAFF, bank, null, AccountStatus.ACTIVE);
        secondStaff = user("Expiry Second Technician", Role.BLOOD_BANK_STAFF, bank, null, AccountStatus.ACTIVE);
        suspendedStaff = user("Expiry Suspended Technician", Role.BLOOD_BANK_STAFF, bank, null, AccountStatus.SUSPENDED);
        otherBankStaff = user("Expiry Other Technician", Role.BLOOD_BANK_STAFF, otherBank, null, AccountStatus.ACTIVE);
        admin = user("Expiry Administrator", Role.ADMIN, null, null, AccountStatus.ACTIVE);
        hospitalStaff = user("Expiry Ward Clerk", Role.HOSPITAL_STAFF, null, hospital, AccountStatus.ACTIVE);
        donor = user("Expiry Donor", Role.DONOR, null, null, AccountStatus.ACTIVE);
    }

    // ---------- the seven-day reminder ----------

    @Test
    void exactlySevenDaysAwayIsRemindedSixAndEightAreNot() {
        UUID sixDays = addBatch(BloodType.O_POSITIVE, 4, today.plusDays(6));
        UUID sevenDays = addBatch(BloodType.A_NEGATIVE, 5, today.plusDays(7));
        UUID eightDays = addBatch(BloodType.B_POSITIVE, 6, today.plusDays(8));

        run();

        assertThat(messages(staff, NotificationType.BATCH_EXPIRY_REMINDER)).containsExactly(
            "1 batch at " + bank.getName() + " will expire on " + DATE.format(today.plusDays(7)) + ": A- (5 units).");
        assertThat(noticed(sevenDays)).isTrue();
        assertThat(noticed(sixDays)).isFalse();
        assertThat(noticed(eightDays)).isFalse();

        // Next day: the 8-day batch reaches 7 and is reminded; the one already reminded (now 6) is not again,
        // and the batch that arrived with only 6 days left (now 5) never is.
        clock.advance(Duration.ofDays(1));
        run();

        List<String> reminders = messages(staff, NotificationType.BATCH_EXPIRY_REMINDER);
        assertThat(reminders).hasSize(2);
        assertThat(reminders.get(0)).isEqualTo(
            "1 batch at " + bank.getName() + " will expire on " + DATE.format(today.plusDays(8)) + ": B+ (6 units).");
        assertThat(noticed(sixDays)).isFalse();
    }

    @Test
    void aSeventhDayMissedWhileTheServerWasOffIsCaughtUpOnTheNextRun() {
        UUID batch = addBatch(BloodType.O_POSITIVE, 3, today.plusDays(7));

        // No run on the seventh day or the sixth; the next run, with 5 days left, still sends it, once.
        clock.advance(Duration.ofDays(2));
        run();
        run();

        assertThat(messages(staff, NotificationType.BATCH_EXPIRY_REMINDER)).containsExactly(
            "1 batch at " + bank.getName() + " will expire on " + DATE.format(today.plusDays(7)) + ": O+ (3 units).");
        assertThat(noticed(batch)).isTrue();
    }

    @Test
    void aBatchWithNoUnitsLeftIsNeverMentioned() {
        UUID usedUp = addBatch(BloodType.O_POSITIVE, 2, today.plusDays(7));
        UUID expiredUsedUp = addBatch(BloodType.A_NEGATIVE, 2, today.minusDays(3));
        emptyBatch(usedUp);
        emptyBatch(expiredUsedUp);

        run();

        assertThat(batchNotices(staff)).isEmpty();
        assertThat(noticed(usedUp)).isFalse();
    }

    // ---------- the expired notice ----------

    @Test
    void nothingOnTheExpiryDateThenAWriteOffNoticeEveryDayAfter() {
        addBatch(BloodType.O_POSITIVE, 5, today);

        run(); // the expiry date itself: still usable, nothing to write off
        assertThat(messages(staff, NotificationType.BATCH_EXPIRED)).isEmpty();

        String expected = "1 batch at " + bank.getName() + " has expired and still holds units. Please write it off: "
            + "O+ (5 units, expired " + DATE.format(today) + ").";
        for (int day = 1; day <= 3; day++) {
            clock.advance(Duration.ofDays(1));
            run();
            assertThat(messages(staff, NotificationType.BATCH_EXPIRED)).hasSize(day).allMatch(expected::equals);
        }
    }

    @Test
    void writingTheUnitsOffStopsTheExpiredNotices() {
        UUID batch = addBatch(BloodType.O_POSITIVE, 3, today.minusDays(2));
        run();
        assertThat(messages(staff, NotificationType.BATCH_EXPIRED)).hasSize(1);

        writeOffService.writeOff(bank.getId(), batch, new WriteOffBatchRequest(3, "EXPIRED", null),
            AuthenticatedUser.from(staff));
        flush();
        clock.advance(Duration.ofDays(1));
        run();
        clock.advance(Duration.ofDays(1));
        run();

        assertThat(messages(staff, NotificationType.BATCH_EXPIRED)).hasSize(1);
    }

    @Test
    void aBatchStampedByTheV19CutoverStillGetsTheWriteOffNotice() {
        UUID batch = addBatch(BloodType.AB_POSITIVE, 2, today.minusDays(30));
        batchRepository.markExpiryNoticed(List.of(batch), clock.instant().minus(Duration.ofDays(20)));

        run();

        assertThat(messages(staff, NotificationType.BATCH_EXPIRED)).singleElement().asString()
            .contains("AB+ (2 units, expired " + DATE.format(today.minusDays(30)) + ")");
    }

    // ---------- grouping, recipients, duplicates ----------

    @Test
    void severalBatchesMakeOneNoticeOfEachKindListingThemAll() {
        addBatch(BloodType.O_POSITIVE, 5, today.minusDays(1));
        addBatch(BloodType.A_NEGATIVE, 1, today.minusDays(4));
        addBatch(BloodType.O_POSITIVE, 2, today.minusDays(4));
        addBatch(BloodType.B_POSITIVE, 7, today.plusDays(7));
        addBatch(BloodType.O_POSITIVE, 3, today.plusDays(7));

        run();

        String day = DATE.format(today.minusDays(4));
        assertThat(messages(staff, NotificationType.BATCH_EXPIRED)).singleElement().asString()
            .startsWith("3 batches at " + bank.getName() + " have expired and still hold units. Please write them off: ")
            .contains("A- (1 unit, expired " + day + ")", "O+ (2 units, expired " + day + ")",
                "O+ (5 units, expired " + DATE.format(today.minusDays(1)) + ")");
        assertThat(messages(staff, NotificationType.BATCH_EXPIRY_REMINDER)).singleElement().asString()
            .startsWith("2 batches at " + bank.getName() + " will expire on " + DATE.format(today.plusDays(7)) + ": ")
            .contains("B+ (7 units)", "O+ (3 units)");
    }

    @Test
    void onlyThatBanksActiveStaffAreTold() {
        addBatch(BloodType.O_POSITIVE, 5, today.minusDays(1));
        addBatch(BloodType.A_NEGATIVE, 2, today.plusDays(7));

        run();

        assertThat(batchNotices(staff)).hasSize(2);
        assertThat(batchNotices(secondStaff)).hasSize(2);
        for (User nobody : List.of(suspendedStaff, otherBankStaff, admin, hospitalStaff, donor)) {
            assertThat(batchNotices(nobody)).as(nobody.getFullName()).isEmpty();
        }
    }

    @Test
    void runningTwiceOnTheSameDaySendsNothingMoreAndTheNextDayListsEverything() {
        addBatch(BloodType.O_POSITIVE, 5, today.minusDays(1));
        addBatch(BloodType.A_NEGATIVE, 2, today.plusDays(7));
        run();

        // A restart later the same day, with one more expired batch found meanwhile.
        addBatch(BloodType.B_POSITIVE, 1, today.minusDays(2));
        run();

        assertThat(messages(staff, NotificationType.BATCH_EXPIRED)).hasSize(1);
        assertThat(messages(staff, NotificationType.BATCH_EXPIRY_REMINDER)).hasSize(1);

        clock.advance(Duration.ofDays(1));
        run();
        assertThat(messages(staff, NotificationType.BATCH_EXPIRED)).hasSize(2).first().asString()
            .startsWith("2 batches at " + bank.getName());
        assertThat(messages(staff, NotificationType.BATCH_EXPIRY_REMINDER)).hasSize(1);
    }

    @Test
    void aBankWithNoActiveStaffKeepsItsReminderForWhenItHasSome() {
        BloodBank thirdBank = newBank("Expiry Third Bank");
        User onlyStaff = user("Expiry Lone Technician", Role.BLOOD_BANK_STAFF, thirdBank, null, AccountStatus.SUSPENDED);
        UUID batch = addBatch(thirdBank, BloodType.O_POSITIVE, 4, today.plusDays(7));

        run();
        assertThat(batchNotices(onlyStaff)).isEmpty();
        assertThat(noticed(batch)).isFalse();

        User reactivated = userRepository.findById(onlyStaff.getId()).orElseThrow();
        reactivated.setStatus(AccountStatus.ACTIVE);
        userRepository.save(reactivated);
        run();

        assertThat(messages(onlyStaff, NotificationType.BATCH_EXPIRY_REMINDER)).hasSize(1);
        assertThat(noticed(batch)).isTrue();
    }

    // ---------- existing notifications are unchanged ----------

    @Test
    void aLowStockAlertTheSameDayNeitherBlocksNorIsBlockedByTheExpiryNotices() {
        addBatch(BloodType.O_POSITIVE, 5, today.minusDays(1));
        notificationService.notifyStockCrossing(bank, BloodType.A_NEGATIVE, 42, 38);
        flush();

        run();
        notificationService.notifyStockCrossing(bank, BloodType.B_POSITIVE, 20, 10);
        flush();

        assertThat(messages(staff, NotificationType.LOW_STOCK_ALERT)).hasSize(2);
        assertThat(messages(staff, NotificationType.BATCH_EXPIRED)).hasSize(1);
        // Like every notification, low-stock alerts reach active accounts only.
        assertThat(messages(suspendedStaff, NotificationType.LOW_STOCK_ALERT)).isEmpty();
    }

    // ---------- helpers ----------

    private void run() {
        job.runForAllBanks();
        flush();
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    /** This user's notices of one type, newest first. */
    private List<String> messages(User user, NotificationType type) {
        return notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(notification -> notification.getType() == type)
            .map(Notification::getMessage)
            .toList();
    }

    private List<Notification> batchNotices(User user) {
        return notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(notification -> notification.getType() == NotificationType.BATCH_EXPIRY_REMINDER
                || notification.getType() == NotificationType.BATCH_EXPIRED)
            .toList();
    }

    private boolean noticed(UUID batchId) {
        flush();
        return batchRepository.findById(batchId).orElseThrow().getExpiryNoticedAt() != null;
    }

    private UUID addBatch(BloodType type, int units, LocalDate expiry) {
        return addBatch(bank, type, units, expiry);
    }

    /** Saved directly, so expired dates are possible; received_at is the real time, i.e. day 0 of the clock. */
    private UUID addBatch(BloodBank owner, BloodType type, int units, LocalDate expiry) {
        UUID id = batchRepository.save(BloodInventoryBatch.manual(owner, type, units, expiry)).getId();
        flush();
        return id;
    }

    private void emptyBatch(UUID batchId) {
        BloodInventoryBatch batch = batchRepository.findById(batchId).orElseThrow();
        batch.deductUnits(batch.getUnitsRemaining(), Instant.now());
        batchRepository.save(batch);
        flush();
    }

    private BloodBank newBank(String name) {
        BloodBank created = new BloodBank();
        created.setName(name + " " + UUID.randomUUID().toString().substring(0, 8));
        created.setLocation("Makeni");
        created.setContactPhone("076 000 000");
        return bloodBankRepository.save(created);
    }

    private User user(String fullName, Role role, BloodBank bloodBank, Hospital hospital, AccountStatus status) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail(role == Role.DONOR ? null : "expiry.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash("not-used-no-sign-in-here");
        user.setRole(role);
        user.setBloodBank(bloodBank);
        user.setHospital(hospital);
        user.setStatus(status);
        return userRepository.save(user);
    }

    /** Distinct local-format numbers within a run, with a prefix of their own (075) so other ITs' cannot meet them. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "075" + String.format("%06d", value);
    }
}
