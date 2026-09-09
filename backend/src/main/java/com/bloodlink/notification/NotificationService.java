package com.bloodlink.notification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.bloodlink.repository.BloodInventoryRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.NotificationRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.web.error.NotFoundException;

/**
 * Everything that produces a Notification, and the read side the bell icon uses.
 *
 * The four notification types in the agreed schema each have one trigger method
 * here, and each is called from the service that owns the event rather than the
 * other way round — InventoryService and RequestService know when stock moved or a
 * request changed hands; this class knows who should hear about it and how to say
 * it. Keeping the "who and how" in one place is what stops the wording and the
 * recipient rules drifting apart across four call sites.
 *
 * Every trigger does the same two things in the same order: write a Notification
 * row per recipient (the durable record, readable later over REST), then hand it to
 * {@link NotificationPublisher} for a live push to any of that recipient's open
 * sockets. The push is an accelerator and may reach nobody; the row is the record
 * and always exists. That ordering is why a donor who was offline during an appeal
 * still finds it waiting on their dashboard.
 *
 * Business Rule 1 is enforced structurally rather than by a check: the only method
 * that writes a DONATION_APPEAL takes a BloodBank, and there is no overload, field
 * or code path anywhere in this class that could put a hospital's name in front of
 * a donor.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final DonorRepository donorRepository;
    private final BloodInventoryRepository bloodInventoryRepository;
    private final NotificationPublisher publisher;

    NotificationService(
        NotificationRepository notificationRepository,
        UserRepository userRepository,
        DonorRepository donorRepository,
        BloodInventoryRepository bloodInventoryRepository,
        NotificationPublisher publisher
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.donorRepository = donorRepository;
        this.bloodInventoryRepository = bloodInventoryRepository;
        this.publisher = publisher;
    }

    // ================================================================
    // Read side — what the bell icon and its dropdown are built from
    // ================================================================

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

    /**
     * Marks one notification read — used when a donor or staff member opens the
     * item the notification points at.
     *
     * The lookup is by (id, recipient) together, so pointing this at another user's
     * notification id is a 404 rather than a successful write on their row.
     */
    @Transactional
    public NotificationResponse markRead(UUID notificationId, UUID userId) {
        Notification notification = notificationRepository.findByIdAndRecipientId(notificationId, userId)
            .orElseThrow(() -> new NotFoundException("No notification found for this id."));
        notification.setRead(true);
        return NotificationResponse.from(notificationRepository.save(notification));
    }

    /** "Mark all as read" — clears this user's badge, and returns how many it cleared. */
    @Transactional
    public int markAllRead(UUID userId) {
        return notificationRepository.markAllReadForRecipient(userId);
    }

    // ================================================================
    // LOW_STOCK_ALERT
    // ================================================================

    /**
     * Raises a low-stock alert when a change to one bank's stock has pushed a blood
     * group down past a Business Rule 3 threshold — into Low (below 40 units) or
     * Critical (below 15).
     *
     * Called with the unit count on either side of the change, not just the new one,
     * because the alert is about a crossing and not about a level. A group that was
     * already Critical and drops two units further is still Critical: staff have
     * been told, and repeating it on every subsequent issue would bury the crossings
     * that are actually news. {@link StockStatus#isWorseThan} is the whole test — an
     * increase in units can never satisfy it, so restocking is silent.
     *
     * Recipients are the staff of that bank alone. A shortage at Makeni Regional is
     * not Holy Spirit's alert to act on, and the two banks' queues are kept separate
     * everywhere else in the system.
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

    // ================================================================
    // DONATION_APPEAL
    // ================================================================

    /**
     * How far an appeal for one blood group reaches: the donors it goes to, and the
     * ones held back because they are still inside their recovery window.
     *
     * The field names are deliberately tense-neutral, because the same record
     * answers both "how many would this reach?" before sending and "how many did
     * it reach?" after. Those two numbers must be the same number, and sharing one
     * record is part of how that stays true.
     *
     * The second figure is not decoration. Without it a bank that appeals and
     * reaches three people cannot tell whether only three donors have that group —
     * a recruitment problem — or whether twenty do and most gave recently, which
     * needs patience instead. Those call for opposite responses.
     */
    public record AppealReach(int eligible, int inRecovery) {}

    /** The eligible recipients for one group, and how many were held back. */
    private record AppealAudience(List<User> recipients, int inRecovery) {}

    /**
     * Works out who an appeal for one blood group may go to.
     *
     * The single place that answer is computed, so the count a staff member is
     * shown before confirming and the set of donors actually written to on confirm
     * cannot disagree. Two separate implementations would be free to drift, and the
     * direction that matters — a preview that undercounts, or worse, a send that
     * reaches someone the preview excluded — is exactly the one nobody would notice.
     *
     * Eligibility is asked of {@link Donor#isEligibleToDonate()} rather than
     * recomputed from a date here, so this and the "You are eligible" banner on the
     * donor's own dashboard remain the same judgement.
     *
     * Filtered in memory rather than as a cutoff in the query, for the same reason:
     * Business Rule 2 is a moving date, and a JPQL clause would be a second copy of
     * the rule free to drift from the entity's. At this scale — one city's donor
     * register — reading the group and discarding the ineligible costs a few rows;
     * a filter that silently diverged would cost a donor's safety.
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
     * How far an appeal for this group would reach, without sending anything.
     *
     * Backs the confirmation step on the Blood Inventory screen: a staff member
     * about to appeal should know whether they are reaching twelve donors or none
     * before they commit, not afterwards. Read-only — no Notification row is
     * written and nothing is pushed.
     */
    @Transactional(readOnly = true)
    public AppealReach previewAppealReach(BloodType bloodType) {
        AppealAudience audience = appealAudienceFor(bloodType);
        return new AppealReach(audience.recipients().size(), audience.inRecovery());
    }

    /**
     * Sends a blood bank's appeal to every *eligible* donor recorded as having the
     * group it needs, and reports how far it reached.
     *
     * Business Rule 1 in one method: an appeal originates from a blood bank and the
     * message names that bank as its source. A hospital short of blood raises a
     * BloodRequest against the banks; it never reaches a donor directly, and there
     * is no argument to this method through which it could.
     *
     * Business Rule 2 is the second gate, and it is a safety rule rather than a
     * targeting preference. A donor inside their 90-day recovery window must never
     * be prompted toward another donation: the appeal is a call to act, and putting
     * one in front of someone whose body has not recovered invites exactly the harm
     * the window exists to prevent. Being able to see their group is needed is not
     * worth that risk, so they are skipped outright — not shown a softened version.
     *
     * Eligibility is asked of {@link Donor#isEligibleToDonate()} rather than
     * recomputed from a date here, so this filter and the "You are eligible" banner
     * on the donor's own dashboard are the same judgement. If they could disagree,
     * the failure would be silent and in the dangerous direction.
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

        dispatch(audience.recipients(), message, NotificationType.DONATION_APPEAL);

        return new AppealReach(audience.recipients().size(), audience.inRecovery());
    }

    // ================================================================
    // NEW_REQUEST
    // ================================================================

    /**
     * Tells blood bank staff that a hospital has raised a request they could take.
     *
     * "Could take" is read as holding enough units of that group to cover it right
     * now — those are the banks able to act on it this minute, and alerting a bank
     * that plainly cannot help is the kind of noise that trains people to ignore the
     * bell.
     *
     * When no bank holds enough, every bank's staff are told instead. That is not a
     * contradiction of the rule above but the case it exists for: an unfillable
     * request is the most urgent thing on the board, it already appears in every
     * bank's queue under Business Rule 6, and a silent one would be a request nobody
     * had been told about precisely because nobody could easily satisfy it.
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

        List<UUID> capableBankIds = bloodInventoryRepository.findBankIdsHoldingAtLeast(
            request.getBloodType(), request.getUnitsRequested()
        );

        List<User> recipients = capableBankIds.isEmpty()
            ? userRepository.findByRole(Role.BLOOD_BANK_STAFF)
            : capableBankIds.stream()
                .flatMap(bankId -> userRepository.findByRoleAndBloodBankId(Role.BLOOD_BANK_STAFF, bankId).stream())
                .toList();

        dispatch(recipients, message, NotificationType.NEW_REQUEST);
    }

    // ================================================================
    // REQUEST_STATUS_CHANGE
    // ================================================================

    /**
     * Tells the hospital that raised a request what has happened to it — approved,
     * rejected, or fulfilled.
     *
     * Addressed to the staff of that hospital only. Under Business Rule 9 a hospital
     * sees nothing of another hospital's requests, and an outcome notification is
     * part of the request, so the same boundary applies here.
     *
     * A rejection says what it costs, because the hospital's next move depends on
     * it: the request is closed for every blood bank, not only the one that turned
     * it down, so waiting for a second bank to pick it up is not an option and a new
     * request has to be raised. That is the consequence flagged for review in
     * CLAUDE.md Business Rule 11, and until that decision changes, being explicit
     * about it here is what stops a ward waiting on a request that is already dead.
     */
    @Transactional
    public void notifyRequestStatusChange(BloodRequest request) {
        BloodBank bank = request.getFulfilledByBloodBank();
        String subject = "Your request for %s of %s".formatted(
            units(request.getUnitsRequested()), request.getBloodType().getLabel()
        );

        String message = switch (request.getStatus()) {
            case APPROVED -> "%s was approved by %s. The units will be issued on fulfilment."
                .formatted(subject, bank == null ? "a blood bank" : bank.getName());
            case FULFILLED -> "%s has been fulfilled by %s. The units have been issued."
                .formatted(subject, bank == null ? "a blood bank" : bank.getName());
            case REJECTED -> ("%s was rejected. A rejection closes the request for every blood bank, "
                + "so please submit a new request if the blood is still needed.").formatted(subject);
            // PENDING is the state a request is created in, never one it changes to —
            // nothing in Business Rule 5's lifecycle leads back to it. Listed so that
            // adding a status later fails the build here rather than silently
            // notifying a hospital with an empty message.
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

    // ================================================================
    // Shared plumbing
    // ================================================================

    /**
     * Writes one Notification row per recipient and queues each for live delivery.
     *
     * Recipients are de-duplicated by user id first. Nothing in the callers above
     * should produce the same person twice, but the new-request path unions the
     * staff lists of several banks, and a duplicate there would show one hospital's
     * request twice in the same bell — cheap to prevent, tedious to notice.
     *
     * Rows are saved together, then published one by one. The publish is deferred
     * until this transaction commits (see NotificationPublisher), so a failure after
     * this point takes the notifications down with the event that caused them rather
     * than leaving alerts on screens for something that was rolled back.
     */
    private void dispatch(List<User> recipients, String message, NotificationType type) {
        Map<UUID, User> unique = new LinkedHashMap<>();
        recipients.forEach(user -> unique.putIfAbsent(user.getId(), user));

        List<Notification> rows = new ArrayList<>(unique.size());
        for (User recipient : unique.values()) {
            Notification notification = new Notification();
            notification.setRecipient(recipient);
            notification.setMessage(message);
            notification.setType(type);
            notification.setRead(false);
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
