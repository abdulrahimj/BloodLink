package com.bloodlink.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.notification.NotificationService.AppealReach;
import com.bloodlink.notification.dto.NotificationResponse;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryRepository;
import com.bloodlink.repository.BloodRequestDeclineRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Covers the decisions this service makes that a screen cannot show you were wrong
 * until it is too late: whether a stock alert fires at all, who receives it, and the
 * two rules governing appeals — Business Rule 1, that one can only ever name a blood
 * bank as its source, and Business Rule 2, that one must never reach a donor still
 * inside their recovery window.
 *
 * The recovery-window tests deliberately pin both sides of the boundary. "More than
 * 90 days" is the kind of wording an off-by-one hides inside, and here an off-by-one
 * means prompting a donor to give a day too early.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID OTHER_BANK_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private DonorRepository donorRepository;
    @Mock private BloodInventoryRepository bloodInventoryRepository;
    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private BloodRequestDeclineRepository declineRepository;
    @Mock private NotificationPublisher publisher;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(
            notificationRepository, userRepository, donorRepository, bloodInventoryRepository,
            bloodBankRepository, declineRepository, publisher
        );
    }

    // ---------- fixtures ----------

    private static BloodBank bank(UUID id, String name) {
        BloodBank bank = new BloodBank();
        bank.setId(id);
        bank.setName(name);
        return bank;
    }

    private static User user(String fullName) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(fullName);
        return user;
    }

    /** A donor who has never given blood — eligible from the day they registered. */
    private static Donor donor(User user) {
        Donor donor = new Donor();
        donor.setId(UUID.randomUUID());
        donor.setUser(user);
        return donor;
    }

    /** A donor whose last donation was {@code daysAgo} days back. */
    private static Donor donorWhoGave(User user, int daysAgo) {
        Donor donor = donor(user);
        donor.setLastDonationDate(LocalDate.now().minusDays(daysAgo));
        return donor;
    }

    @Test
    void donorAcceptsTheirAppealAndRepeatAcceptanceIsIdempotent() {
        UUID recipientId = UUID.randomUUID();
        User recipient = user("Aminata Kamara");
        recipient.setId(recipientId);
        Donor donor = donor(recipient);
        BloodBank sourceBank = bank(BANK_ID, "Holy Spirit Blood Bank");
        User staff = user("Bank Staff");
        Notification appeal = new Notification();
        appeal.setId(UUID.randomUUID());
        appeal.setRecipient(recipient);
        appeal.setType(NotificationType.DONATION_APPEAL);
        appeal.setSourceBloodBank(sourceBank);
        appeal.setBloodType(BloodType.O_NEGATIVE);
        when(donorRepository.findByUserId(recipientId)).thenReturn(java.util.Optional.of(donor));
        when(notificationRepository.findByIdAndRecipientIdForUpdate(appeal.getId(), recipientId))
            .thenReturn(java.util.Optional.of(appeal));
        when(notificationRepository.save(appeal)).thenReturn(appeal);
        when(userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, BANK_ID)).thenReturn(List.of(staff));
        echoSavedNotifications();

        NotificationResponse first = service.acceptAppeal(appeal.getId(), recipientId);
        NotificationResponse second = service.acceptAppeal(appeal.getId(), recipientId);

        assertThat(first.accepted()).isTrue();
        assertThat(first.read()).isTrue();
        assertThat(second.accepted()).isTrue();
        verify(notificationRepository, times(1)).save(appeal);
        List<Notification> staffAlerts = capturedRows();
        assertThat(staffAlerts).singleElement().satisfies(alert -> {
            assertThat(alert.getRecipient()).isEqualTo(staff);
            assertThat(alert.getType()).isEqualTo(NotificationType.DONATION_APPEAL_ACCEPTED);
            assertThat(alert.getMessage()).contains("Aminata Kamara", "O-");
        });
    }

    @Test
    void repeatedRequestRepairsAnAcceptanceWithoutAStaffAlert() {
        UUID recipientId = UUID.randomUUID();
        User recipient = user("Aminata Kamara");
        recipient.setId(recipientId);
        Donor donor = donor(recipient);
        BloodBank sourceBank = bank(BANK_ID, "Holy Spirit Blood Bank");
        User staff = user("Bank Staff");
        Notification appeal = new Notification();
        appeal.setId(UUID.randomUUID());
        appeal.setRecipient(recipient);
        appeal.setType(NotificationType.DONATION_APPEAL);
        appeal.setAccepted(true); // recorded before staff alerts were deployed
        appeal.setSourceBloodBank(sourceBank);
        appeal.setBloodType(BloodType.O_NEGATIVE);
        when(donorRepository.findByUserId(recipientId)).thenReturn(java.util.Optional.of(donor));
        when(notificationRepository.findByIdAndRecipientIdForUpdate(appeal.getId(), recipientId))
            .thenReturn(java.util.Optional.of(appeal));
        when(notificationRepository.save(appeal)).thenReturn(appeal);
        when(userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, BANK_ID)).thenReturn(List.of(staff));
        echoSavedNotifications();

        NotificationResponse response = service.acceptAppeal(appeal.getId(), recipientId);

        assertThat(response.accepted()).isTrue();
        assertThat(appeal.isAcceptanceAlertSent()).isTrue();
        assertThat(capturedRows()).singleElement().satisfies(alert -> {
            assertThat(alert.getRecipient()).isEqualTo(staff);
            assertThat(alert.getType()).isEqualTo(NotificationType.DONATION_APPEAL_ACCEPTED);
            assertThat(alert.getMessage()).contains("Aminata Kamara", "O-");
        });
    }

    @Test
    void acceptingAnotherUsersAppealReturnsNotFound() {
        UUID recipientId = UUID.randomUUID();
        UUID appealId = UUID.randomUUID();
        when(donorRepository.findByUserId(recipientId)).thenReturn(java.util.Optional.of(donor(user("Donor"))));
        when(notificationRepository.findByIdAndRecipientIdForUpdate(appealId, recipientId))
            .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.acceptAppeal(appealId, recipientId))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void acceptingANonAppealNotificationReturnsNotFound() {
        UUID recipientId = UUID.randomUUID();
        UUID appealId = UUID.randomUUID();
        Notification notification = new Notification();
        notification.setType(NotificationType.LOW_STOCK_ALERT);
        when(donorRepository.findByUserId(recipientId)).thenReturn(java.util.Optional.of(donor(user("Donor"))));
        when(notificationRepository.findByIdAndRecipientIdForUpdate(appealId, recipientId))
            .thenReturn(java.util.Optional.of(notification));

        assertThatThrownBy(() -> service.acceptAppeal(appealId, recipientId))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void ineligibleDonorCannotAcceptAnAppeal() {
        UUID recipientId = UUID.randomUUID();
        when(donorRepository.findByUserId(recipientId))
            .thenReturn(java.util.Optional.of(donorWhoGave(user("Donor"), 10)));

        assertThatThrownBy(() -> service.acceptAppeal(UUID.randomUUID(), recipientId))
            .isInstanceOf(ConflictException.class);
    }

    private static BloodRequest request(RequestStatus status, BloodBank fulfiller) {
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        hospital.setName("Makeni Regional Hospital");

        BloodRequest request = new BloodRequest();
        request.setId(UUID.randomUUID());
        request.setHospital(hospital);
        request.setBloodType(BloodType.O_NEGATIVE);
        request.setUnitsRequested(4);
        request.setUrgencyLevel(UrgencyLevel.EMERGENCY);
        request.setStatus(status);
        request.setLastContributedByBloodBank(fulfiller);
        return request;
    }

    /** saveAll() returns what it was given, as the real repository does. Ids stay
     *  null, which is fine — nothing under test reads them back. */
    private void echoSavedNotifications() {
        when(notificationRepository.saveAll(any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** The rows one trigger wrote, in the order they were written. */
    private List<Notification> capturedRows() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    // ---------- LOW_STOCK_ALERT ----------

    @Test
    void aDropIntoLowAlertsThatBanksOwnStaff() {
        User staff = user("Aminata Kamara");
        when(userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, BANK_ID))
            .thenReturn(List.of(staff));
        echoSavedNotifications();

        // 42 -> 38 crosses the 40-unit Low threshold.
        service.notifyStockCrossing(bank(BANK_ID, "Makeni Regional Blood Bank"), BloodType.O_NEGATIVE, 42, 38);

        List<Notification> rows = capturedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.LOW_STOCK_ALERT);
        assertThat(rows.get(0).getRecipient()).isEqualTo(staff);
        assertThat(rows.get(0).getMessage())
            .contains("O-", "Makeni Regional Blood Bank", "38 units", "Low");
    }

    @Test
    void aDropIntoCriticalIsNamedAsCritical() {
        when(userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, BANK_ID))
            .thenReturn(List.of(user("Aminata Kamara")));
        echoSavedNotifications();

        // 20 -> 12 skips Low entirely and lands below the 15-unit Critical threshold.
        service.notifyStockCrossing(bank(BANK_ID, "Makeni Regional Blood Bank"), BloodType.A_POSITIVE, 20, 12);

        assertThat(capturedRows().get(0).getMessage()).contains("Critical");
    }

    @Test
    void fallingFurtherWithinTheSameLevelRaisesNothing() {
        // Already Critical at 12, still Critical at 10. Staff have been told once;
        // repeating it on every issue is what makes an alert worth ignoring.
        service.notifyStockCrossing(bank(BANK_ID, "Makeni Regional Blood Bank"), BloodType.O_NEGATIVE, 12, 10);

        verify(notificationRepository, never()).saveAll(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void restockingRaisesNothing() {
        // 10 -> 50 crosses two thresholds upwards. An alert here would tell staff
        // their stock had "fallen" immediately after they added to it.
        service.notifyStockCrossing(bank(BANK_ID, "Makeni Regional Blood Bank"), BloodType.O_NEGATIVE, 10, 50);

        verify(notificationRepository, never()).saveAll(any());
    }

    // ---------- DONATION_APPEAL ----------

    @Test
    void anAppealReachesEveryEligibleDonorOfThatGroupAndNamesTheBank() {
        User first = user("Fatmata Sesay");
        User second = user("Ibrahim Conteh");
        when(donorRepository.findByBloodTypeWithUser(BloodType.O_NEGATIVE))
            .thenReturn(List.of(donor(first), donorWhoGave(second, 200)));
        echoSavedNotifications();

        AppealReach reach = service.sendDonationAppeal(
            bank(BANK_ID, "Holy Spirit Blood Bank"), BloodType.O_NEGATIVE, "Drive at the lab on Saturday."
        );

        assertThat(reach.eligible()).isEqualTo(2);
        assertThat(reach.inRecovery()).isZero();
        List<Notification> rows = capturedRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getType()).isEqualTo(NotificationType.DONATION_APPEAL);
            assertThat(row.getSourceBloodBank().getId()).isEqualTo(BANK_ID);
            assertThat(row.getBloodType()).isEqualTo(BloodType.O_NEGATIVE);
            // Business Rule 1: the source a donor sees is a blood bank, never a hospital.
            assertThat(row.getMessage()).startsWith("Holy Spirit Blood Bank needs O- donors");
            assertThat(row.getMessage()).endsWith("Drive at the lab on Saturday.");
        });
        assertThat(rows).extracting(Notification::getRecipient).containsExactly(first, second);
    }

    @Test
    void anAppealNeverReachesADonorStillInsideTheRecoveryWindow() {
        User eligible = user("Fatmata Sesay");
        User recovering = user("Ibrahim Conteh");
        when(donorRepository.findByBloodTypeWithUser(BloodType.O_NEGATIVE))
            .thenReturn(List.of(donor(eligible), donorWhoGave(recovering, 10)));
        echoSavedNotifications();

        AppealReach reach = service.sendDonationAppeal(
            bank(BANK_ID, "Holy Spirit Blood Bank"), BloodType.O_NEGATIVE, null
        );

        // The safety rule: a donor who gave ten days ago is not prompted toward
        // another donation, matching blood group or not.
        assertThat(reach.eligible()).isEqualTo(1);
        assertThat(reach.inRecovery()).isEqualTo(1);
        assertThat(capturedRows()).extracting(Notification::getRecipient).containsExactly(eligible);
    }

    @Test
    void theRecoveryWindowsLastDayIsStillIneligible() {
        // Business Rule 2 says "more than 90 days", so day 90 is inside the window
        // and day 91 is the first day out of it. An off-by-one here would prompt a
        // donor a day early, which is the whole failure this rule exists to prevent.
        when(donorRepository.findByBloodTypeWithUser(BloodType.O_NEGATIVE))
            .thenReturn(List.of(donorWhoGave(user("Day 90"), 90)));
        echoSavedNotifications();

        AppealReach onTheBoundary = service.sendDonationAppeal(
            bank(BANK_ID, "Holy Spirit Blood Bank"), BloodType.O_NEGATIVE, null
        );

        assertThat(onTheBoundary.eligible()).isZero();
        assertThat(onTheBoundary.inRecovery()).isEqualTo(1);
        verify(notificationRepository).saveAll(List.of());
    }

    @Test
    void aDonorOneDayPastTheWindowIsReached() {
        User justEligible = user("Day 91");
        when(donorRepository.findByBloodTypeWithUser(BloodType.O_NEGATIVE))
            .thenReturn(List.of(donorWhoGave(justEligible, 91)));
        echoSavedNotifications();

        AppealReach reach = service.sendDonationAppeal(
            bank(BANK_ID, "Holy Spirit Blood Bank"), BloodType.O_NEGATIVE, null
        );

        assertThat(reach.eligible()).isEqualTo(1);
        assertThat(capturedRows()).extracting(Notification::getRecipient).containsExactly(justEligible);
    }

    @Test
    void anAppealWhereEveryMatchingDonorIsRecoveringWritesNothingButSaysWhy() {
        when(donorRepository.findByBloodTypeWithUser(BloodType.O_NEGATIVE))
            .thenReturn(List.of(donorWhoGave(user("A"), 5), donorWhoGave(user("B"), 40)));
        echoSavedNotifications();

        AppealReach reach = service.sendDonationAppeal(
            bank(BANK_ID, "Holy Spirit Blood Bank"), BloodType.O_NEGATIVE, null
        );

        // Distinguishable from "no donors have this group at all", which is what the
        // second figure exists for — the two need opposite responses from the bank.
        assertThat(reach.eligible()).isZero();
        assertThat(reach.inRecovery()).isEqualTo(2);
    }

    @Test
    void thePreviewCountsWhatTheSendWouldReachAndWritesNothing() {
        // The confirmation dialog promises a number; sending must honour exactly it.
        // The two are computed by one shared method precisely so they cannot drift,
        // and this is the test that would fail if someone split them apart again.
        when(donorRepository.findByBloodTypeWithUser(BloodType.O_NEGATIVE)).thenReturn(List.of(
            donor(user("Never donated")),
            donorWhoGave(user("Gave 200 days ago"), 200),
            donorWhoGave(user("Gave 30 days ago"), 30),
            donorWhoGave(user("Gave 90 days ago"), 90)
        ));

        AppealReach preview = service.previewAppealReach(BloodType.O_NEGATIVE);

        assertThat(preview.eligible()).isEqualTo(2);
        assertThat(preview.inRecovery()).isEqualTo(2);
        // Read-only: a preview must never leave a notification behind.
        verify(notificationRepository, never()).saveAll(any());
        verifyNoInteractions(publisher);

        echoSavedNotifications();
        AppealReach sent = service.sendDonationAppeal(
            bank(BANK_ID, "Holy Spirit Blood Bank"), BloodType.O_NEGATIVE, null
        );

        assertThat(sent).isEqualTo(preview);
        assertThat(capturedRows()).hasSize(preview.eligible());
    }

    @Test
    void anAppealWithNoMatchingDonorsReportsZeroRatherThanFailing() {
        when(donorRepository.findByBloodTypeWithUser(BloodType.AB_NEGATIVE)).thenReturn(List.of());
        echoSavedNotifications();

        AppealReach reach = service.sendDonationAppeal(
            bank(BANK_ID, "Holy Spirit Blood Bank"), BloodType.AB_NEGATIVE, null
        );

        assertThat(reach.eligible()).isZero();
        assertThat(reach.inRecovery()).isZero();
    }

    /**
     * The preview for a blood group nobody has registered as reports zero eligible
     * AND zero in recovery. That pair is what tells the confirmation dialog "no
     * donors registered" apart from "all donors recovering" (see
     * frontend/src/lib/appealReach.js) — so it is asserted here rather than
     * assumed: if the preview ever folded the two cases into one figure, the dialog
     * would go back to saying everyone is eligible while offering nobody to appeal to.
     */
    @Test
    void aPreviewForAGroupWithNoRegisteredDonorsReportsZeroEligibleAndZeroInRecovery() {
        when(donorRepository.findByBloodTypeWithUser(BloodType.AB_NEGATIVE)).thenReturn(List.of());

        AppealReach preview = service.previewAppealReach(BloodType.AB_NEGATIVE);

        assertThat(preview.eligible()).isZero();
        assertThat(preview.inRecovery()).isZero();
        // A preview writes nothing and pushes nothing.
        verify(notificationRepository, never()).saveAll(any());
        verifyNoInteractions(publisher);
    }

    // ---------- NEW_REQUEST ----------

    @Test
    void aNewRequestAlertsOnlyTheBanksThatCouldCoverIt() {
        User capableStaff = user("Aminata Kamara");
        when(bloodInventoryRepository.findBankIdsHoldingAtLeast(BloodType.O_NEGATIVE, 4))
            .thenReturn(List.of(BANK_ID));
        when(userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, BANK_ID))
            .thenReturn(List.of(capableStaff));
        echoSavedNotifications();

        service.notifyNewRequest(request(RequestStatus.PENDING, null));

        List<Notification> rows = capturedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.NEW_REQUEST);
        assertThat(rows.get(0).getRecipient()).isEqualTo(capableStaff);
        assertThat(rows.get(0).getMessage()).contains("Makeni Regional Hospital", "4 units", "O-", "Emergency");
        // The bank that could not cover it was never looked up, let alone written to.
        verify(userRepository, never()).findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, OTHER_BANK_ID);
    }

    @Test
    void aRequestNoBankCanCoverStillAlertsEveryBank() {
        when(bloodInventoryRepository.findBankIdsHoldingAtLeast(BloodType.O_NEGATIVE, 4))
            .thenReturn(List.of());
        when(userRepository.findByRole(Role.BLOOD_BANK_STAFF))
            .thenReturn(List.of(user("Aminata Kamara"), user("Mohamed Bangura")));
        echoSavedNotifications();

        service.notifyNewRequest(request(RequestStatus.PENDING, null));

        // The alternative is silence on exactly the request that most needs a human.
        assertThat(capturedRows()).hasSize(2);
    }

    // ---------- REQUEST_STATUS_CHANGE ----------

    @Test
    void aFulfilmentTellsTheRaisingHospitalWhichBankIssuedTheLastUnits() {
        User hospitalStaff = user("Isatu Turay");
        when(userRepository.findByRoleAndHospitalId(Role.HOSPITAL_STAFF, HOSPITAL_ID))
            .thenReturn(List.of(hospitalStaff));
        echoSavedNotifications();

        service.notifyRequestStatusChange(
            request(RequestStatus.FULFILLED, bank(BANK_ID, "Makeni Regional Blood Bank"))
        );

        List<Notification> rows = capturedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.REQUEST_STATUS_CHANGE);
        assertThat(rows.get(0).getRecipient()).isEqualTo(hospitalStaff);
        assertThat(rows.get(0).getMessage())
            .contains("fulfilled in full", "Makeni Regional Blood Bank");
    }

    /**
     * Business Rule 20's partial case. The hospital is told the figures rather than
     * a state, because "partially fulfilled" alone leaves a ward having to go and
     * look up the only two numbers it can act on: what arrived, and what has not.
     */
    @Test
    void aPartialContributionTellsTheHospitalHowManyUnitsArrivedAndHowManyAreLeft() {
        User hospitalStaff = user("Isatu Turay");
        when(userRepository.findByRoleAndHospitalId(Role.HOSPITAL_STAFF, HOSPITAL_ID))
            .thenReturn(List.of(hospitalStaff));
        echoSavedNotifications();

        // 3 of the 4 requested units now in, 1 still outstanding.
        service.notifyRequestPartlyFilled(
            request(RequestStatus.PARTIALLY_FULFILLED, bank(BANK_ID, "Makeni Regional Blood Bank")),
            bank(BANK_ID, "Makeni Regional Blood Bank"), 3, 3
        );

        List<Notification> rows = capturedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.REQUEST_STATUS_CHANGE);
        assertThat(rows.get(0).getMessage())
            .contains("Makeni Regional Blood Bank", "3 units", "3 of 4 units received", "1 unit still needed");
    }

    // ---------- REQUEST_PROGRESS (Business Rule 20) ----------

    /**
     * The alert that stops two banks covering the same gap: every bank still
     * watching the request hears what is left — except the one that just gave, which
     * already knows.
     */
    @Test
    void aContributionTellsTheOtherBanksWhatIsStillNeeded() {
        BloodBank contributor = bank(BANK_ID, "Makeni Regional Blood Bank");
        BloodBank otherBank = bank(OTHER_BANK_ID, "Holy Spirit Blood Bank");
        when(bloodBankRepository.findAll()).thenReturn(List.of(contributor, otherBank));
        User otherBankStaff = user("Mohamed Kamara");
        when(userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, OTHER_BANK_ID))
            .thenReturn(List.of(otherBankStaff));
        echoSavedNotifications();

        service.notifyRequestProgress(request(RequestStatus.PARTIALLY_FULFILLED, contributor), contributor, 3, 3);

        List<Notification> rows = capturedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.REQUEST_PROGRESS);
        assertThat(rows.get(0).getRecipient()).isEqualTo(otherBankStaff);
        assertThat(rows.get(0).getMessage()).contains("1 unit still needed");
        // Never back to the bank that made the contribution.
        verify(userRepository, never()).findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, BANK_ID);
    }

    @Test
    void aBankThatDeclinedTheRequestIsNotToldAboutItsProgress() {
        BloodBank contributor = bank(BANK_ID, "Makeni Regional Blood Bank");
        BloodBank otherBank = bank(OTHER_BANK_ID, "Holy Spirit Blood Bank");
        BloodRequest request = request(RequestStatus.PARTIALLY_FULFILLED, contributor);
        when(bloodBankRepository.findAll()).thenReturn(List.of(contributor, otherBank));
        // The only other bank has already said it cannot help.
        when(declineRepository.findBloodBankIdsByRequestId(request.getId())).thenReturn(List.of(OTHER_BANK_ID));

        service.notifyRequestProgress(request, contributor, 1, 1);

        // Nobody left to tell — and no empty row written for the sake of it.
        verify(notificationRepository).saveAll(List.of());
        verifyNoInteractions(publisher);
    }

    @Test
    void aContributionThatCompletesARequestTellsTheOtherBanksToStop() {
        BloodBank contributor = bank(BANK_ID, "Makeni Regional Blood Bank");
        BloodBank otherBank = bank(OTHER_BANK_ID, "Holy Spirit Blood Bank");
        when(bloodBankRepository.findAll()).thenReturn(List.of(contributor, otherBank));
        when(userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, OTHER_BANK_ID))
            .thenReturn(List.of(user("Mohamed Kamara")));
        echoSavedNotifications();

        // All 4 units now covered — the message a bank about to issue its own needs.
        service.notifyRequestProgress(request(RequestStatus.FULFILLED, contributor), contributor, 4, 4);

        assertThat(capturedRows().get(0).getMessage())
            .contains("fully covered", "No further units are needed");
    }

    @Test
    void aRejectionSaysEveryBankDeclinedItAndTheRequestIsClosed() {
        when(userRepository.findByRoleAndHospitalId(Role.HOSPITAL_STAFF, HOSPITAL_ID))
            .thenReturn(List.of(user("Isatu Turay")));
        echoSavedNotifications();

        // Under Business Rule 11 a request only reaches REJECTED once every bank has
        // declined it, so this message is sent once and means the request is closed.
        // Rejection also leaves no fulfilling bank, so it cannot name one.
        service.notifyRequestStatusChange(request(RequestStatus.REJECTED, null));

        assertThat(capturedRows().get(0).getMessage())
            .contains("declined by every blood bank", "closed", "submit a new request");
    }

    @Test
    void aRequestStillPendingNotifiesNobody() {
        service.notifyRequestStatusChange(request(RequestStatus.PENDING, null));

        verify(notificationRepository, never()).saveAll(any());
        verifyNoInteractions(publisher);
    }

    // ---------- delivery ----------

    @Test
    void everyWrittenRowIsAlsoHandedToThePublisher() {
        User staff = user("Aminata Kamara");
        when(userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, BANK_ID))
            .thenReturn(List.of(staff));
        echoSavedNotifications();

        service.notifyStockCrossing(bank(BANK_ID, "Makeni Regional Blood Bank"), BloodType.O_NEGATIVE, 42, 38);

        // The row is the record; the push is the accelerator. Both, or the live
        // screen and the database disagree about what happened.
        verify(publisher).publish(eq(staff.getId()), any(NotificationResponse.class));
    }

    @Test
    void oneRecipientReachedByTwoBanksIsWrittenToOnce() {
        User shared = user("Aminata Kamara");
        when(bloodInventoryRepository.findBankIdsHoldingAtLeast(BloodType.O_NEGATIVE, 4))
            .thenReturn(List.of(BANK_ID, OTHER_BANK_ID));
        when(userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, BANK_ID))
            .thenReturn(List.of(shared));
        when(userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, OTHER_BANK_ID))
            .thenReturn(List.of(shared));
        echoSavedNotifications();

        service.notifyNewRequest(request(RequestStatus.PENDING, null));

        assertThat(capturedRows()).hasSize(1);
    }
}
