package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.notification.NotificationService.AppealReach;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * Every notification type reaches active accounts only: for each, an active account receives it and a suspended
 * account at the same bank or hospital (or matching the same appeal) does not. Real queries; rolled back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationRecipientsIT {

    private static final long PHONE_RUN_BASE = System.currentTimeMillis() % 1_000_000L;
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();

    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private DonorRepository donorRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodInventoryBatchRepository batchRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationService notificationService;

    private BloodBank bank;
    private BloodBank otherBank;
    private Hospital hospital;
    private User activeStaff;
    private User suspendedStaff;
    private User activeHospitalStaff;
    private User suspendedHospitalStaff;
    private User activeDonor;
    private User suspendedDonor;

    @BeforeEach
    void setUp() {
        bank = newBank("Recipients Bank");
        otherBank = newBank("Recipients Other Bank");
        hospital = newHospital();
        activeStaff = user("Recipients Active Technician", Role.BLOOD_BANK_STAFF, bank, null, AccountStatus.ACTIVE);
        suspendedStaff = user("Recipients Suspended Technician", Role.BLOOD_BANK_STAFF, bank, null,
            AccountStatus.SUSPENDED);
        activeHospitalStaff = user("Recipients Active Clerk", Role.HOSPITAL_STAFF, null, hospital, AccountStatus.ACTIVE);
        suspendedHospitalStaff = user("Recipients Suspended Clerk", Role.HOSPITAL_STAFF, null, hospital,
            AccountStatus.SUSPENDED);
        activeDonor = donor("Recipients Active Donor", AccountStatus.ACTIVE, null).getUser();
        suspendedDonor = donor("Recipients Suspended Donor", AccountStatus.SUSPENDED, null).getUser();
    }

    // ---------- bank staff ----------

    @Test
    void aLowStockAlertSkipsASuspendedStaffMember() {
        notificationService.notifyStockCrossing(bank, BloodType.O_POSITIVE, 42, 38);
        flush();

        assertReachesOnlyTheActive(NotificationType.LOW_STOCK_ALERT, activeStaff, suspendedStaff);
    }

    @Test
    void anAppealAcceptanceSkipsASuspendedStaffMember() {
        notificationService.sendDonationAppeal(bank, BloodType.AB_NEGATIVE, null);
        flush();
        Notification appeal = notices(activeDonor, NotificationType.DONATION_APPEAL).get(0);

        notificationService.acceptAppeal(appeal.getId(), activeDonor.getId());
        flush();

        assertReachesOnlyTheActive(NotificationType.DONATION_APPEAL_ACCEPTED, activeStaff, suspendedStaff);
    }

    @Test
    void aNewRequestSkipsASuspendedStaffMemberAtABankThatCanCoverIt() {
        // Only this bank holds 9,000 units, so it is the one "capable" bank.
        batchRepository.save(BloodInventoryBatch.manual(bank, BloodType.AB_NEGATIVE, 9_000, today().plusDays(30)));
        flush();

        notificationService.notifyNewRequest(request(BloodType.AB_NEGATIVE, 9_000, RequestStatus.PENDING));
        flush();

        assertReachesOnlyTheActive(NotificationType.NEW_REQUEST, activeStaff, suspendedStaff);
    }

    @Test
    void aNewRequestNoBankCanCoverSkipsSuspendedStaffToo() {
        // The every-bank fallback: nobody holds two million units.
        notificationService.notifyNewRequest(request(BloodType.O_POSITIVE, 2_000_000, RequestStatus.PENDING));
        flush();

        assertReachesOnlyTheActive(NotificationType.NEW_REQUEST, activeStaff, suspendedStaff);
    }

    @Test
    void requestProgressSkipsASuspendedStaffMemberAtAWatchingBank() {
        notificationService.notifyRequestProgress(
            request(BloodType.O_POSITIVE, 4, RequestStatus.PARTIALLY_FULFILLED), otherBank, 1, 1);
        flush();

        assertReachesOnlyTheActive(NotificationType.REQUEST_PROGRESS, activeStaff, suspendedStaff);
    }

    @Test
    void theDailyExpiryNoticesSkipASuspendedStaffMember() {
        batchRepository.save(BloodInventoryBatch.manual(bank, BloodType.O_POSITIVE, 3, today().minusDays(1)));
        batchRepository.save(BloodInventoryBatch.manual(bank, BloodType.A_NEGATIVE, 2, today().plusDays(7)));
        flush();

        notificationService.notifyBatchExpiry(bank.getId(), Instant.now());
        flush();

        assertReachesOnlyTheActive(NotificationType.BATCH_EXPIRED, activeStaff, suspendedStaff);
        assertReachesOnlyTheActive(NotificationType.BATCH_EXPIRY_REMINDER, activeStaff, suspendedStaff);
    }

    // ---------- hospital staff ----------

    @Test
    void aStatusChangeSkipsASuspendedHospitalStaffMember() {
        BloodRequest fulfilled = request(BloodType.O_POSITIVE, 4, RequestStatus.FULFILLED);
        fulfilled.setLastContributedByBloodBank(bank);

        notificationService.notifyRequestStatusChange(fulfilled);
        flush();

        assertReachesOnlyTheActive(NotificationType.REQUEST_STATUS_CHANGE, activeHospitalStaff, suspendedHospitalStaff);
    }

    @Test
    void aPartFilledRequestSkipsASuspendedHospitalStaffMember() {
        notificationService.notifyRequestPartlyFilled(
            request(BloodType.O_POSITIVE, 4, RequestStatus.PARTIALLY_FULFILLED), bank, 1, 1);
        flush();

        assertReachesOnlyTheActive(NotificationType.REQUEST_STATUS_CHANGE, activeHospitalStaff, suspendedHospitalStaff);
    }

    // ---------- donors ----------

    @Test
    void anAppealSkipsASuspendedEligibleDonor() {
        notificationService.sendDonationAppeal(bank, BloodType.AB_NEGATIVE, null);
        flush();

        assertReachesOnlyTheActive(NotificationType.DONATION_APPEAL, activeDonor, suspendedDonor);
    }

    @Test
    void theAppealPreviewCountsNoSuspendedDonor() {
        User recovering = donor("Recipients Suspended Recovering Donor", AccountStatus.SUSPENDED,
            today().minusDays(10)).getUser();
        AppealReach before = notificationService.previewAppealReach(BloodType.AB_NEGATIVE);

        // Reactivating both adds one to each figure, so neither was being counted while suspended.
        reactivate(suspendedDonor);
        reactivate(recovering);
        AppealReach after = notificationService.previewAppealReach(BloodType.AB_NEGATIVE);

        assertThat(after.eligible()).isEqualTo(before.eligible() + 1);
        assertThat(after.inRecovery()).isEqualTo(before.inRecovery() + 1);
    }

    // ---------- helpers ----------

    private void assertReachesOnlyTheActive(NotificationType type, User active, User suspended) {
        assertThat(notices(active, type)).as("active " + active.getFullName()).hasSize(1);
        assertThat(notices(suspended, type)).as("suspended " + suspended.getFullName()).isEmpty();
    }

    private List<Notification> notices(User user, NotificationType type) {
        return notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(notification -> notification.getType() == type)
            .toList();
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private void reactivate(User user) {
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setStatus(AccountStatus.ACTIVE);
        userRepository.save(reloaded);
        flush();
    }

    /** In memory only: the notification methods read the request's fields, never its row. */
    private BloodRequest request(BloodType type, int units, RequestStatus status) {
        BloodRequest request = new BloodRequest();
        request.setId(UUID.randomUUID());
        request.setHospital(hospital);
        request.setBloodType(type);
        request.setUnitsRequested(units);
        request.setUrgencyLevel(UrgencyLevel.URGENT);
        request.setStatus(status);
        return request;
    }

    private BloodBank newBank(String name) {
        BloodBank created = new BloodBank();
        created.setName(name + " " + UUID.randomUUID().toString().substring(0, 8));
        created.setLocation("Makeni");
        created.setContactPhone("076 000 000");
        return bloodBankRepository.save(created);
    }

    private Hospital newHospital() {
        Hospital created = new Hospital();
        created.setName("Recipients Hospital " + UUID.randomUUID().toString().substring(0, 8));
        created.setLocation("Makeni");
        created.setContactPhone("076 000 001");
        return hospitalRepository.save(created);
    }

    /** An AB- donor; a null last donation means eligible. */
    private Donor donor(String fullName, AccountStatus status, LocalDate lastDonation) {
        Donor donor = new Donor();
        donor.setUser(user(fullName, Role.DONOR, null, null, status));
        donor.setBloodType(BloodType.AB_NEGATIVE);
        donor.setDateOfBirth(LocalDate.of(1995, 1, 1));
        donor.setGender(Gender.PREFER_NOT_TO_SAY);
        donor.setAddress("Makeni");
        donor.setLastDonationDate(lastDonation);
        return donorRepository.save(donor);
    }

    private User user(String fullName, Role role, BloodBank bloodBank, Hospital facility, AccountStatus status) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail(role == Role.DONOR ? null : "recipients.it." + UUID.randomUUID() + "@example.com");
        user.setPhone(PhoneNumber.normalize(uniqueLocalPhone()));
        user.setPasswordHash("not-used-no-sign-in-here");
        user.setRole(role);
        user.setBloodBank(bloodBank);
        user.setHospital(facility);
        user.setStatus(status);
        return userRepository.save(user);
    }

    /** Distinct local-format numbers within a run, with a prefix of their own (074) so other ITs' cannot meet them. */
    private String uniqueLocalPhone() {
        long value = (PHONE_RUN_BASE + PHONE_SEQUENCE.incrementAndGet()) % 1_000_000L;
        return "074" + String.format("%06d", value);
    }
}
