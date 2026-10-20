package com.bloodlink.notification;

import java.util.ArrayList;
import java.util.HashSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.notification.dto.NotificationResponse;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodRequestDeclineRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.web.error.NotFoundException;

/**
 * Everything that produces a Notification, plus the bell's read side; each trigger writes a row per recipient
 * first, then pushes it live. Appeals take only a BloodBank, so a hospital never appears to a donor (Business Rule 1).
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final DonorRepository donorRepository;
    private final BatchStockService batchStockService;
    // Both only for notifyRequestProgress: which banks exist, and which of them
    // have declined the request and so are no longer watching it.
    private final BloodBankRepository bloodBankRepository;
    private final BloodRequestDeclineRepository declineRepository;
    private final NotificationPublisher publisher;

    NotificationService(
        NotificationRepository notificationRepository,
        UserRepository userRepository,
        DonorRepository donorRepository,
        BatchStockService batchStockService,
        BloodBankRepository bloodBankRepository,
        BloodRequestDeclineRepository declineRepository,
        NotificationPublisher publisher
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.donorRepository = donorRepository;
        this.batchStockService = batchStockService;
        this.bloodBankRepository = bloodBankRepository;
        this.declineRepository = declineRepository;
        this.publisher = publisher;
    }

    // ==================== Read side: what the bell icon and its dropdown are built from ====================

    /** This user's recent notifications, newest first. Never anyone else's: the
     *  caller's id comes from their token, and there is no id parameter to tamper with. */
    @Transactional(readOnly = true)
    public List<NotificationResponse> listForUser(UUID userId) {
        return notificationRepository.findTop50ByRecipientIdOrderByCreatedAtDesc(userId).stream()
            .map(NotificationResponse::from)
            .toList();
    }

    /** The number on the bell. */
    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notificationRepository.countByRecipientIdAndReadFalse(userId);
    }

    /** Latest appeal send times that have at least one donor acceptance, for one bank. */
    @Transactional(readOnly = true)
    public Map<BloodType, Instant> acceptedAppealTimes(UUID bloodBankId) {
        Map<BloodType, Instant> times = new LinkedHashMap<>();
        notificationRepository.findAcceptedAppealGroups(bloodBankId).forEach(row ->
            times.put(row.getBloodType(), row.getLatestAppealAt()));
        return times;
    }

    /**
     * Marks one notification read, looked up by (id, recipient) so another user's id is a 404.
     */
    @Transactional
    public NotificationResponse markRead(UUID notificationId, UUID userId) {
        Notification notification = notificationRepository.findByIdAndRecipientId(notificationId, userId)
            .orElseThrow(() -> new NotFoundException("No notification found for this id."));
        notification.setRead(true);
        return NotificationResponse.from(notificationRepository.save(notification));
    }

    /** Records a donor's acceptance of an appeal addressed to their account. */
    @Transactional
    public NotificationResponse acceptAppeal(UUID notificationId, UUID userId) {
        Donor donor = donorRepository.findByUserId(userId)
            .orElseThrow(() -> new NotFoundException("No donor profile found for this account."));
        if (!donor.isEligibleToDonate()) {
            throw new ConflictException("You must be eligible to donate to accept this appeal.");
        }
        Notification notification = notificationRepository.findByIdAndRecipientIdForUpdate(notificationId, userId)
            .filter(row -> row.getType() == NotificationType.DONATION_APPEAL)
            .orElseThrow(() -> new NotFoundException("No donation appeal found for this id."));
        boolean acceptanceUpdated = false;
        if (!notification.isAccepted()) {
            notification.setAccepted(true);
            notification.setRead(true);
            acceptanceUpdated = true;
        }

        // Also repairs acceptances recorded before staff alerts were deployed. The
        // lock plus this persisted flag keeps retries idempotent, including when
        // the donor's first acceptance committed without an alert.
        if (!notification.isAcceptanceAlertSent()) {
            BloodBank bank = notification.getSourceBloodBank();
            if (bank != null && notification.getBloodType() != null) {
                String message = "%s accepted your appeal for %s.".formatted(
                    donor.getUser().getFullName(), notification.getBloodType().getLabel()
                );
                notification.setAcceptanceAlertSent(true);
                acceptanceUpdated = true;
                notificationRepository.save(notification);
                dispatch(
                    userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, bank.getId()),
                    message,
                    NotificationType.DONATION_APPEAL_ACCEPTED
                );
            }
        }
        if (acceptanceUpdated && !notification.isAcceptanceAlertSent()) {
            notificationRepository.save(notification);
        }
        return NotificationResponse.from(notification);
    }

    /** "Mark all as read" — clears this user's badge, and returns how many it cleared. */
    @Transactional
    public int markAllRead(UUID userId) {
        return notificationRepository.markAllReadForRecipient(userId);
    }

    // ======================================== LOW_STOCK_ALERT ========================================

    /**
     * Alerts that bank's staff when a stock change crosses a Business Rule 3 threshold (Low < 40, Critical < 15),
     * judged on usable units; only a crossing to a worse level counts, so restocking and further drops are silent.
     */
    @Transactional
    public void notifyStockCrossing(BloodBank bank, BloodType bloodType, int unitsBefore, int unitsAfter) {
        StockStatus before = StockStatus.fromUnits(unitsBefore);
        StockStatus after = StockStatus.fromUnits(unitsAfter);

        if (!after.isWorseThan(before)) {
            return;
        }

        String message = "%s stock at %s has fallen to %s — now %s. %s".formatted(
            bloodType.getLabel(),
            bank.getName(),
            units(unitsAfter),
            after == StockStatus.CRITICAL ? "Critical" : "Low",
            after == StockStatus.CRITICAL
                ? "Consider appealing to donors straight away."
                : "Consider appealing to donors."
        );

        dispatch(
            userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, bank.getId()),
            message,
            NotificationType.LOW_STOCK_ALERT
        );
    }

    // ======================================== DONATION_APPEAL ========================================

    /**
     * How far an appeal reaches: eligible donors and those held back in recovery; tense-neutral so the preview
     * and the send share one record, and the second figure explains a small reach.
     */
    public record AppealReach(int eligible, int inRecovery) {}

    /** The eligible recipients for one group, and how many were held back. */
    private record AppealAudience(List<User> recipients, int inRecovery) {}

    /**
     * The single place an appeal's audience is computed, so the preview count and the send cannot disagree.
     * Eligibility comes from Donor.isEligibleToDonate(), filtered in memory so the rule is never copied into a query.
     */
    private AppealAudience appealAudienceFor(BloodType bloodType) {
        List<Donor> matchingGroup = donorRepository.findByBloodTypeWithUser(bloodType);
        List<User> recipients = matchingGroup.stream()
            .filter(Donor::isEligibleToDonate)
            .map(Donor::getUser)
            .toList();

        return new AppealAudience(recipients, matchingGroup.size() - recipients.size());
    }

    /**
     * How far an appeal for this group would reach, read-only: nothing is written or pushed.
     */
    @Transactional(readOnly = true)
    public AppealReach previewAppealReach(BloodType bloodType) {
        AppealAudience audience = appealAudienceFor(bloodType);
        return new AppealReach(audience.recipients().size(), audience.inRecovery());
    }

    /**
     * Sends a bank's appeal to every eligible donor of the group (Business Rule 1: always from a bank)
     * and reports its reach. Donors in recovery are skipped outright, a safety rule (Business Rule 2).
     */
    @Transactional
    public AppealReach sendDonationAppeal(BloodBank bank, BloodType bloodType, String note) {
        String message = "%s needs %s donors. Please donate if you are able.%s".formatted(
            bank.getName(),
            bloodType.getLabel(),
            note == null ? "" : " " + note
        );

        // The same audience calculation the preview uses, so what a staff member was
        // shown before confirming is what actually happens on confirm.
        AppealAudience audience = appealAudienceFor(bloodType);

        dispatch(audience.recipients(), message, NotificationType.DONATION_APPEAL, bank, bloodType);

        return new AppealReach(audience.recipients().size(), audience.inRecovery());
    }

    // ========================================== NEW_REQUEST ==========================================

    /**
     * Alerts staff of banks holding enough usable units to cover a new request (Business Rule 24);
     * if none does, every bank is told, since an unfillable request is the most urgent.
     */
    @Transactional
    public void notifyNewRequest(BloodRequest request) {
        String message = "%s has requested %s of %s (%s).%s".formatted(
            request.getHospital().getName(),
            units(request.getUnitsRequested()),
            request.getBloodType().getLabel(),
            titleCase(request.getUrgencyLevel().name()),
            request.getPatientReference() == null ? "" : " For " + request.getPatientReference() + "."
        );

        List<UUID> capableBankIds = batchStockService.bankIdsWithUsableUnitsAtLeast(
            request.getBloodType(), request.getUnitsRequested()
        );

        List<User> recipients = capableBankIds.isEmpty()
            ? userRepository.findByRole(Role.BLOOD_BANK_STAFF)
            : capableBankIds.stream()
                .flatMap(bankId -> userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, bankId).stream())
                .toList();

        dispatch(recipients, message, NotificationType.NEW_REQUEST);
    }

    // ===================================== REQUEST_STATUS_CHANGE =====================================

    /**
     * Tells the raising hospital's staff (only) what happened: units received with figures (Business Rule 20),
     * fully covered, or rejected by every bank, meaning a new request is needed.
     */
    @Transactional
    public void notifyRequestStatusChange(BloodRequest request) {
        BloodBank bank = request.getLastContributedByBloodBank();
        String bankName = bank == null ? "a blood bank" : bank.getName();
        String subject = "Your request for %s of %s".formatted(
            units(request.getUnitsRequested()), request.getBloodType().getLabel()
        );

        String message = switch (request.getStatus()) {
            case FULFILLED -> "%s has been fulfilled in full. The last units were issued by %s."
                .formatted(subject, bankName);
            case PARTIALLY_FULFILLED -> null; // said by notifyRequestPartlyFilled, which knows the figures
            case REJECTED -> ("%s was declined by every blood bank, so it is now closed. "
                + "Please submit a new request if the blood is still needed.").formatted(subject);
            // PENDING (created, or a decline that was not the last): nothing to tell the hospital.
            case PENDING -> null;
        };

        if (message == null) {
            return;
        }

        dispatch(
            userRepository.findByRoleAndHospitalId(Role.HOSPITAL_STAFF, request.getHospital().getId()),
            message,
            NotificationType.REQUEST_STATUS_CHANGE
        );
    }

    /**
     * Tells the hospital some units arrived and how many are still to come (Business Rule 20);
     * a contribution that completes the request is announced as a fulfilment instead.
     */
    @Transactional
    public void notifyRequestPartlyFilled(
        BloodRequest request, BloodBank contributingBank, int unitsContributed, int unitsContributedTotal
    ) {
        int outstanding = request.getUnitsRequested() - unitsContributedTotal;
        String message = "%s has issued %s towards your request for %s of %s — %d of %d units received, %s still needed."
            .formatted(
                contributingBank.getName(),
                units(unitsContributed),
                units(request.getUnitsRequested()),
                request.getBloodType().getLabel(),
                unitsContributedTotal,
                request.getUnitsRequested(),
                units(outstanding)
            );

        dispatch(
            userRepository.findByRoleAndHospitalId(Role.HOSPITAL_STAFF, request.getHospital().getId()),
            message,
            NotificationType.REQUEST_STATUS_CHANGE
        );
    }

    // ======================================== REQUEST_PROGRESS ========================================

    /**
     * Tells the other banks a request's outstanding units changed (Business Rule 20), so two don't cover the same gap.
     * Not the contributing bank, not banks that declined; everyone else, including when it was completed.
     */
    @Transactional
    public void notifyRequestProgress(
        BloodRequest request, BloodBank contributingBank, int unitsContributed, int unitsContributedTotal
    ) {
        int outstanding = request.getUnitsRequested() - unitsContributedTotal;
        String message = outstanding <= 0
            ? "%s of %s for %s is now fully covered — %s issued by %s. No further units are needed."
                .formatted(
                    units(request.getUnitsRequested()), request.getBloodType().getLabel(),
                    request.getHospital().getName(), units(unitsContributed), contributingBank.getName()
                )
            : "%s has issued %s towards %s's request for %s of %s — %s still needed."
                .formatted(
                    contributingBank.getName(), units(unitsContributed), request.getHospital().getName(),
                    units(request.getUnitsRequested()), request.getBloodType().getLabel(), units(outstanding)
                );

        Set<UUID> excluded = new HashSet<>(declineRepository.findBloodBankIdsByRequestId(request.getId()));
        excluded.add(contributingBank.getId());

        List<User> recipients = bloodBankRepository.findAll().stream()
            .map(BloodBank::getId)
            .filter(bankId -> !excluded.contains(bankId))
            .flatMap(bankId -> userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, bankId).stream())
            .toList();

        dispatch(recipients, message, NotificationType.REQUEST_PROGRESS);
    }

    // ======================================== Shared plumbing ========================================

    /**
     * Writes one Notification row per recipient (de-duplicated by user id) and queues each for live delivery
     * after commit, so a rollback takes the notifications with it.
     */
    private void dispatch(List<User> recipients, String message, NotificationType type) {
        dispatch(recipients, message, type, null, null);
    }

    /** Appeal variant: persist its origin and group so acceptance can be routed safely. */
    private void dispatch(
        List<User> recipients, String message, NotificationType type, BloodBank sourceBank, BloodType bloodType
    ) {
        Map<UUID, User> unique = new LinkedHashMap<>();
        recipients.forEach(user -> unique.putIfAbsent(user.getId(), user));

        List<Notification> rows = new ArrayList<>(unique.size());
        for (User recipient : unique.values()) {
            Notification notification = new Notification();
            notification.setRecipient(recipient);
            notification.setMessage(message);
            notification.setType(type);
            notification.setRead(false);
            notification.setSourceBloodBank(sourceBank);
            notification.setBloodType(bloodType);
            rows.add(notification);
        }

        notificationRepository.saveAll(rows)
            .forEach(saved -> publisher.publish(saved.getRecipient().getId(), NotificationResponse.from(saved)));
    }

    /** "1 unit" / "4 units" — the count and its noun, so no caller has to remember
     *  the singular case in the middle of composing a sentence. */
    private static String units(int count) {
        return count + (count == 1 ? " unit" : " units");
    }

    /** EMERGENCY -> "Emergency", matching how the portals already render enums. */
    private static String titleCase(String enumName) {
        return enumName.charAt(0) + enumName.substring(1).toLowerCase();
    }
}
