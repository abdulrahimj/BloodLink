package com.bloodlink.notification;

import java.util.ArrayList;
import java.util.HashSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.DonationBooking;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.Notification;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.BookingCancellation;
import com.bloodlink.domain.enums.NotificationType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.notification.dto.NotificationResponse;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodRequestDeclineRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.stock.BatchStockService;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Everything that produces a Notification, plus the bell's read side; each trigger writes a row per recipient
 * first, then pushes it live. Appeals take only a BloodBank, so a hospital never appears to a donor (Business Rule 1).
 */
@Service
public class NotificationService {

    /** Dates in the expiry and booking notices: "31 Oct 2026", the way the portals show dates. */
    private static final DateTimeFormatter EXPIRY_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

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

        // Also repairs acceptances recorded before staff alerts existed; the row lock plus this
        // persisted flag keeps retries idempotent, so the bank's staff are alerted exactly once.
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
                    activeBankStaff(bank.getId()),
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
            activeBankStaff(bank.getId()),
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
     * The single place an appeal's audience is computed, so the preview count and the send cannot disagree. Suspended
     * donors are left out by the query; eligibility comes from Donor.isEligibleToDonate(), filtered in memory.
     */
    private AppealAudience appealAudienceFor(BloodType bloodType) {
        List<Donor> matchingGroup = donorRepository.findByBloodTypeWithUser(bloodType, AccountStatus.ACTIVE);
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

        dispatch(audience.recipients(), message, NotificationType.DONATION_APPEAL, bank, bloodType, null);

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
            ? userRepository.findByRoleAndStatus(Role.BLOOD_BANK_STAFF, AccountStatus.ACTIVE)
            : capableBankIds.stream()
                .flatMap(bankId -> activeBankStaff(bankId).stream())
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
            // Cancelling is the hospital's own act, and act() never reaches here with this status.
            case CANCELLED -> null;
        };

        if (message == null) {
            return;
        }

        dispatch(
            activeHospitalStaff(request.getHospital().getId()),
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
            activeHospitalStaff(request.getHospital().getId()),
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
            .flatMap(bankId -> activeBankStaff(bankId).stream())
            .toList();

        dispatch(recipients, message, NotificationType.REQUEST_PROGRESS);
    }

    // ======================================== REQUEST_CANCELLED ========================================

    /**
     * Tells every bank that could see or had contributed to a request that it was cancelled (Business Rule 35): every bank
     * but those that declined, as a contributor cannot decline. The note stays out, as it may name a patient.
     */
    @Transactional
    public void notifyRequestCancelled(BloodRequest request) {
        Set<UUID> declined = new HashSet<>(declineRepository.findBloodBankIdsByRequestId(request.getId()));

        List<User> recipients = bloodBankRepository.findAll().stream()
            .map(BloodBank::getId)
            .filter(bankId -> !declined.contains(bankId))
            .flatMap(bankId -> activeBankStaff(bankId).stream())
            .toList();

        dispatch(recipients, requestCancelledMessage(request), NotificationType.REQUEST_CANCELLED);
    }

    /** "Request #3F2A9C1B (O+, 5 units) was cancelled by Holy Spirit Hospital: No longer needed." */
    static String requestCancelledMessage(BloodRequest request) {
        return "Request #%s (%s, %s) was cancelled by %s: %s.".formatted(
            request.getReference(), request.getBloodType().getLabel(), units(request.getUnitsRequested()),
            request.getHospital().getName(), request.getCancellationReason().getLabel());
    }

    // =============================== BATCH_EXPIRY_REMINDER and BATCH_EXPIRED ===============================

    /** What one bank's daily run listed: batches in the seven-day reminder and in the expired notice (0 = not sent). */
    public record ExpiryNotices(int remindedBatches, int expiredBatches) {}

    /**
     * One bank's daily expiry notices to its active staff (Business Rule 26). At most one of each type per UTC day:
     * the bank row lock plus a check for that day's notice means a second run sends nothing.
     */
    @Transactional
    public ExpiryNotices notifyBatchExpiry(UUID bloodBankId, Instant now) {
        BloodBank bank = bloodBankRepository.lockById(bloodBankId)
            .orElseThrow(() -> new NotFoundException("No blood bank found for this id."));
        List<User> staff = activeBankStaff(bloodBankId);
        if (staff.isEmpty()) {
            // Nothing is stamped either, so a due reminder still reaches staff assigned later.
            return new ExpiryNotices(0, 0);
        }

        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant dayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<BloodInventoryBatch> expired = notificationRepository.existsForBankBetween(
                NotificationType.BATCH_EXPIRED, bloodBankId, dayStart, dayEnd)
            ? List.of()
            : batchStockService.expiredBatchesHoldingUnits(bloodBankId, today);
        if (!expired.isEmpty()) {
            dispatch(staff, expiredMessage(bank, expired), NotificationType.BATCH_EXPIRED, bank, null, now);
        }

        List<BloodInventoryBatch> due = notificationRepository.existsForBankBetween(
                NotificationType.BATCH_EXPIRY_REMINDER, bloodBankId, dayStart, dayEnd)
            ? List.of()
            : batchStockService.batchesDueExpiryReminder(bloodBankId, today);
        if (!due.isEmpty()) {
            dispatch(staff, expiryReminderMessage(bank, due), NotificationType.BATCH_EXPIRY_REMINDER, bank, null, now);
            batchStockService.markExpiryReminderSent(due.stream().map(BloodInventoryBatch::getId).toList(), now);
        }

        return new ExpiryNotices(due.size(), expired.size());
    }

    /** "2 batches at X will expire on 31 Oct 2026: O+ (5 units), A- (1 unit)." Caught-up batches with
     *  different dates read "will expire within 7 days" and carry their own dates. */
    static String expiryReminderMessage(BloodBank bank, List<BloodInventoryBatch> batches) {
        LocalDate firstDate = batches.get(0).getExpiryDate();
        boolean oneDate = batches.stream().allMatch(batch -> batch.getExpiryDate().equals(firstDate));
        String list = batches.stream()
            .map(batch -> oneDate
                ? "%s (%s)".formatted(batch.getBloodType().getLabel(), units(batch.getUnitsRemaining()))
                : "%s (%s, %s)".formatted(batch.getBloodType().getLabel(), units(batch.getUnitsRemaining()),
                    EXPIRY_DATE.format(batch.getExpiryDate())))
            .collect(Collectors.joining(", "));
        return "%s at %s will expire %s: %s.".formatted(
            batches(batches.size()), bank.getName(),
            oneDate ? "on " + EXPIRY_DATE.format(firstDate)
                : "within " + BatchStockService.EXPIRY_REMINDER_DAYS + " days",
            list);
    }

    /** "2 batches at X have expired and still hold units. Please write them off: O+ (5 units, expired 21 Oct 2026), …" */
    static String expiredMessage(BloodBank bank, List<BloodInventoryBatch> batches) {
        boolean one = batches.size() == 1;
        String list = batches.stream()
            .map(batch -> "%s (%s, expired %s)".formatted(batch.getBloodType().getLabel(),
                units(batch.getUnitsRemaining()), EXPIRY_DATE.format(batch.getExpiryDate())))
            .collect(Collectors.joining(", "));
        return "%s at %s %s expired and still %s units. Please write %s off: %s.".formatted(
            batches(batches.size()), bank.getName(),
            one ? "has" : "have", one ? "holds" : "hold", one ? "it" : "them", list);
    }

    // ==================== Donation bookings (Business Rule 29) ====================

    /** DONATION_BOOKED to the booked bank's active staff, in the booking's transaction. */
    @Transactional
    public void notifyBookingMade(DonationBooking booking) {
        dispatch(activeBankStaff(booking.getBloodBank().getId()), bookedMessage(booking),
            NotificationType.DONATION_BOOKED, booking.getBloodBank(), null, null);
    }

    /** DONATION_BOOKING_CANCELLED to the booked bank's active staff, worded by why the booking was cancelled. */
    @Transactional
    public void notifyBookingCancelled(DonationBooking booking, BookingCancellation reason) {
        dispatch(activeBankStaff(booking.getBloodBank().getId()), cancelledMessage(booking, reason),
            NotificationType.DONATION_BOOKING_CANCELLED, booking.getBloodBank(), null, null);
    }

    /** DONATION_BOOKING_REMINDER to the donor, the day before or (if that was missed) on the day; job's instant. */
    @Transactional
    public void sendBookingReminder(DonationBooking booking, Instant now, boolean visitIsToday) {
        dispatch(List.of(booking.getDonor().getUser()), reminderMessage(booking, visitIsToday),
            NotificationType.DONATION_BOOKING_REMINDER, booking.getBloodBank(), null, now);
    }

    /** The message an account holder gets when an administrator resets their password (Business Rule 31). */
    static final String PASSWORD_RESET_MESSAGE = "Your password was reset by an administrator. If you did not ask for "
        + "this, contact your blood bank or BloodLink administrator.";

    /** PASSWORD_RESET_BY_ADMIN to the account holder, in the reset's transaction; nothing for a suspended account. */
    @Transactional
    public void notifyPasswordResetByAdmin(User account) {
        if (account.getStatus() == AccountStatus.ACTIVE) {
            dispatch(List.of(account), PASSWORD_RESET_MESSAGE, NotificationType.PASSWORD_RESET_BY_ADMIN);
        }
    }

    /** "Password reset requested for +23276900005 (Aminata Kamara). Call back the registered number." */
    static String passwordResetRequestedMessage(User requester) {
        return "Password reset requested for %s (%s). Call back the registered number."
            .formatted(requester.getPhone(), requester.getFullName());
    }

    /**
     * PASSWORD_RESET_REQUESTED to every ACTIVE administrator except the one asking, in the request's transaction
     * (Business Rule 33). Returns how many were told.
     */
    @Transactional
    public int notifyPasswordResetRequested(User requester) {
        List<User> admins = userRepository.findByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE).stream()
            .filter(admin -> !admin.getId().equals(requester.getId()))
            .toList();
        dispatch(admins, passwordResetRequestedMessage(requester), NotificationType.PASSWORD_RESET_REQUESTED);
        return admins.size();
    }

    /** "Aminata Kamara booked a donation for 31 Oct 2026, morning." */
    static String bookedMessage(DonationBooking booking) {
        return "%s booked a donation for %s, %s.".formatted(
            booking.getDonor().getUser().getFullName(), EXPIRY_DATE.format(booking.getBookingDate()),
            booking.getTimeOfDay().inWords());
    }

    /** The donor's own cancellation, the automatic one after a donation elsewhere, or one forced by a suspension. */
    static String cancelledMessage(DonationBooking booking, BookingCancellation reason) {
        String name = booking.getDonor().getUser().getFullName();
        String when = "%s, %s".formatted(EXPIRY_DATE.format(booking.getBookingDate()), booking.getTimeOfDay().inWords());
        return switch (reason) {
            case BY_DONOR -> "%s cancelled their donation booking for %s.".formatted(name, when);
            case DONATED_ELSEWHERE -> ("%s's donation booking for %s was cancelled automatically: they have since "
                + "donated at another blood bank.").formatted(name, when);
            case ACCOUNT_SUSPENDED -> "%s's donation booking for %s was cancelled because the donor's account was suspended."
                .formatted(name, when);
        };
    }

    /** "Your donation at X is tomorrow morning." the day before, or "… is today, morning." on the day. */
    static String reminderMessage(DonationBooking booking, boolean visitIsToday) {
        String bank = booking.getBloodBank().getName();
        String half = booking.getTimeOfDay().inWords();
        return visitIsToday
            ? "Your donation at %s is today, %s.".formatted(bank, half)
            : "Your donation at %s is tomorrow %s.".formatted(bank, half);
    }

    // ======================================== Shared plumbing ========================================

    /** A bank's ACTIVE staff: every notification goes to active accounts only, never to a suspended one. */
    private List<User> activeBankStaff(UUID bloodBankId) {
        return userRepository.findByRoleAndBloodBankIdAndStatus(Role.BLOOD_BANK_STAFF, bloodBankId, AccountStatus.ACTIVE);
    }

    /** A hospital's ACTIVE staff, for the same reason. */
    private List<User> activeHospitalStaff(UUID hospitalId) {
        return userRepository.findByRoleAndHospitalIdAndStatus(Role.HOSPITAL_STAFF, hospitalId, AccountStatus.ACTIVE);
    }

    /**
     * Writes one Notification row per recipient (de-duplicated by user id) and queues each for live delivery
     * after commit, so a rollback takes the notifications with it.
     */
    private void dispatch(List<User> recipients, String message, NotificationType type) {
        dispatch(recipients, message, type, null, null, null);
    }

    /** Variant recording the bank behind it (and an appeal's group); createdAt null means the row's own now(). */
    private void dispatch(
        List<User> recipients, String message, NotificationType type, BloodBank sourceBank, BloodType bloodType,
        Instant createdAt
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
            notification.setCreatedAt(createdAt);
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

    /** "1 batch" / "3 batches". */
    private static String batches(int count) {
        return count + (count == 1 ? " batch" : " batches");
    }

    /** EMERGENCY -> "Emergency", matching how the portals already render enums. */
    private static String titleCase(String enumName) {
        return enumName.charAt(0) + enumName.substring(1).toLowerCase();
    }
}
