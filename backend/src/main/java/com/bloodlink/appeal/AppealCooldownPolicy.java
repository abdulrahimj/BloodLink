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
 *   <li>...unless stock has climbed back over the threshold that triggered it
 *       (15 for CRITICAL, 30 for LOW), which ends the cooldown early.</li>
 *   <li>Otherwise the button is active.</li>
 * </ol>
 *
 * <h2>Why this is a pure function of current stock</h2>
 * "The cooldown clears early the moment stock crosses back over its threshold" is
 * evaluated against stock *as it is now*, not recorded as a one-way event that
 * deletes the row. That matters when stock recovers and then falls again inside
 * the same window: a CRITICAL appeal sent at 10 units, with stock now back down to
 * 12 an hour later, is still in cooldown — which is right, because the shortage
 * being appealed about never actually ended. Treating the first crossing as
 * permanent would let a bank re-appeal to the same donors within the hour on a
 * shortage that merely wobbled. It also keeps the read path genuinely read-only:
 * rendering the inventory table must not write to the database.
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

        // Never appealed for: nothing to block it.
        if (cooldown == null) {
            return AppealAvailability.AVAILABLE;
        }

        AppealTier tier = cooldown.getTriggeredTier();

        // 3. Early release, checked before the timer: the shortage this cooldown
        //    was protecting is over, so the cooldown has nothing left to protect.
        //    Reached only while stock is under 30 (rule 1 above), so in practice
        //    this is the CRITICAL case — a bank back over 15 but still under 30 may
        //    appeal again at the lower tier without serving out the full 5 hours.
        if (tier.hasRecoveredAt(unitsAvailable)) {
            return AppealAvailability.AVAILABLE;
        }

        // 2. Still inside the window, and stock has not recovered.
        if (!tier.hasExpiredBy(cooldown.getLastSentAt(), now)) {
            return AppealAvailability.COOLING_DOWN;
        }

        // 4. Timer ran out with the shortage unresolved — appealing again is
        //    exactly what should happen now.
        return AppealAvailability.AVAILABLE;
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
     * decides how long this cooldown lasts and what stock level ends it early, so
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
