package com.bloodlink.appeal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.bloodlink.domain.AppealCooldown;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.enums.AppealAvailability;
import com.bloodlink.domain.enums.AppealTier;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.repository.AppealCooldownRepository;

/**
 * Answers one question wherever it is asked: may this bank appeal for this blood
 * group right now? (CLAUDE.md Business Rule 12.)
 *
 * Two callers need that answer and must never disagree — the Blood Inventory read
 * path, which decides what the Send Appeal button looks like, and the send
 * endpoint, which has to refuse a click that arrives anyway. Extracted into one
 * component for the same reason {@link com.bloodlink.bloodbank.ActingBankResolver}
 * was: a rule copied into two services is a rule that will eventually be fixed in
 * one of them and not the other. Here that failure would be worse than untidy —
 * the button and the endpoint would disagree about whether an appeal is allowed,
 * so the screen would offer an action the server rejects, or grey out one it would
 * have accepted.
 *
 * <h2>The rule, in the order CLAUDE.md states it</h2>
 * <ol>
 *   <li>30 units or more — no shortage, nothing to appeal about.</li>
 *   <li>A cooldown row within its tier's window (5h CRITICAL, 24h LOW) blocks.</li>
 *   <li>Otherwise the button is active.</li>
 * </ol>
 *
 * <h2>Recovery clears a cooldown permanently, as an event</h2>
 * A cooldown also ends the moment stock climbs back over the threshold that
 * triggered it (15 for CRITICAL, 30 for LOW) — but that is handled by deleting
 * the row at the crossing, in {@link #clearIfRecovered}, not by re-checking
 * current stock on every read. {@link #evaluate} therefore never looks at stock
 * to decide whether a cooldown has ended: a row that still exists is one whose
 * shortage was never resolved.
 *
 * The difference is visible when a group recovers and then relapses inside the
 * same window. The relapse is a new shortage, and it gets its own fresh chance to
 * appeal, rather than serving out the remainder of a timer belonging to an appeal
 * that already did its job. Re-deriving on read would instead resurrect that spent
 * timer and silence a bank that genuinely needs donors again.
 *
 * Keeping the decision an event also keeps the read path genuinely read-only —
 * rendering the inventory table must never write — and matches how this codebase
 * already handles stock thresholds elsewhere (see {@code notifyStockCrossing}).
 *
 * Nothing here touches who *receives* an appeal. Business Rule 2's 90-day
 * eligibility gate is NotificationService's, is unchanged, and remains the only
 * thing that decides that.
 */
@Component
public class AppealCooldownPolicy {

    private final AppealCooldownRepository cooldownRepository;

    // Public, not package-private, so InventoryService (another package) can hold
    // one — and so a test can exercise the real rule rather than stubbing it out.
    public AppealCooldownPolicy(AppealCooldownRepository cooldownRepository) {
        this.cooldownRepository = cooldownRepository;
    }

    /**
     * The decision itself, as a pure function — no repository, no clock of its own.
     *
     * {@code now} is passed in rather than read from {@link Instant#now()} inside
     * so the caller's whole operation shares one instant, and so the timer
     * boundaries are testable without waiting five real hours.
     *
     * @param unitsAvailable this bank's current units of the group in question.
     * @param cooldown       the bank's cooldown row for that group, or null if it
     *                       has never appealed for it.
     */
    public AppealAvailability evaluate(int unitsAvailable, AppealCooldown cooldown, Instant now) {
        // 1. No shortage wins outright, before any cooldown is even considered —
        //    a group at 30+ units is not appealable whatever its history.
        if (!AppealTier.isShortage(unitsAvailable)) {
            return AppealAvailability.NO_SHORTAGE;
        }

        // Nothing to block it: either this group has never been appealed for, or a
        // recovery has since cleared the cooldown away (see clearIfRecovered).
        if (cooldown == null) {
            return AppealAvailability.AVAILABLE;
        }

        // 2. A surviving cooldown blocks for as long as its timer runs. Current
        //    stock is deliberately NOT consulted here — a cooldown that should have
        //    ended because stock recovered was deleted at the moment it recovered,
        //    so a row that still exists is one whose shortage was never resolved.
        if (!cooldown.getTriggeredTier().hasExpiredBy(cooldown.getLastSentAt(), now)) {
            return AppealAvailability.COOLING_DOWN;
        }

        // 3. Timer ran out with the shortage unresolved — appealing again is
        //    exactly what should happen now.
        return AppealAvailability.AVAILABLE;
    }

    /**
     * Ends a cooldown for good once stock has climbed back over the threshold that
     * triggered it — 15 for CRITICAL, 30 for LOW.
     *
     * Called from the one place usable stock can rise (InventoryService, where a new
     * batch arrives by Add New Batch or Record Donation), with usable units, so the
     * clearing happens at the moment of the crossing rather than being re-decided
     * on every later read. That is the whole point: the appeal this cooldown was
     * throttling has done its job, so the cooldown is finished. If the group later
     * falls back into shortage, that is a new event with its own fresh chance to
     * appeal — not something still throttled by the timer of an appeal that already
     * worked.
     *
     * The row is deleted rather than flagged resolved. This table holds current
     * state, not history (see {@link AppealCooldown}), and a resolved cooldown is
     * indistinguishable from one that never existed: both mean "nothing is stopping
     * an appeal for this group". A flag would be a second way to say the same
     * thing, which every later read would then have to remember to check.
     *
     * Detecting the crossing as an event rather than deriving it on read is also
     * how this codebase already treats stock thresholds — {@code notifyStockCrossing}
     * fires on the change, not on the reading of it. The shared consequence is that
     * stock edited directly in the database, outside the application, raises no
     * alert and clears no cooldown; the fix for that has always been to make the
     * change through the app.
     *
     * @return the cooldown still in force for this group afterwards, or null if
     *         there was none or this recovery just cleared it. Returned rather than
     *         left for the caller to look up again, so a caller that needs the
     *         resulting state gets it from the same read this method already did —
     *         no second query, and no dependence on a pending delete having been
     *         flushed before it.
     */
    public AppealCooldown clearIfRecovered(UUID bloodBankId, BloodType bloodType, int unitsAvailable) {
        AppealCooldown cooldown = cooldownRepository
            .findByBloodBankIdAndBloodType(bloodBankId, bloodType)
            .orElse(null);

        if (cooldown == null) {
            return null;
        }

        if (cooldown.getTriggeredTier().hasRecoveredAt(unitsAvailable)) {
            cooldownRepository.delete(cooldown);
            return null;
        }

        // Still short of the level that would end it: the cooldown stands.
        return cooldown;
    }

    /** The same decision, reading the bank's cooldown row for itself. */
    public AppealAvailability evaluate(UUID bloodBankId, BloodType bloodType, int unitsAvailable, Instant now) {
        AppealCooldown cooldown = cooldownRepository
            .findByBloodBankIdAndBloodType(bloodBankId, bloodType)
            .orElse(null);
        return evaluate(unitsAvailable, cooldown, now);
    }

    /**
     * Records that an appeal just went out, starting (or restarting) the cooldown.
     *
     * Updates the existing row rather than inserting a second one — the table holds
     * current state, one row per (bank, group), and the unique constraint enforces
     * that regardless.
     *
     * The tier comes from units at *send* time and is then fixed: it is what
     * decides how long this cooldown lasts and what stock level clears it, so
     * re-deriving it later from moving stock would rewrite a cooldown's terms after
     * the fact.
     *
     * @param unitsAtSend the bank's units of that group at the moment of sending —
     *                    must be a shortage, which the caller has already checked
     *                    via {@link #evaluate}.
     */
    public void recordSent(BloodBank bank, BloodType bloodType, int unitsAtSend, Instant sentAt) {
        AppealTier tier = AppealTier.forUnits(unitsAtSend);
        if (tier == null) {
            // Unreachable through the endpoint, which refuses a no-shortage send
            // before ever getting here. Guarded anyway rather than storing a null
            // tier that every later read would have to defend against.
            throw new IllegalStateException(
                "An appeal was recorded at " + unitsAtSend + " units, which is not a shortage."
            );
        }

        AppealCooldown cooldown = cooldownRepository
            .findByBloodBankIdAndBloodType(bank.getId(), bloodType)
            .orElseGet(() -> {
                AppealCooldown fresh = new AppealCooldown();
                fresh.setBloodBank(bank);
                fresh.setBloodType(bloodType);
                return fresh;
            });

        cooldown.setLastSentAt(sentAt);
        cooldown.setTriggeredTier(tier);
        cooldownRepository.save(cooldown);
    }
}
