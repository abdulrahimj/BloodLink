package com.bloodlink.domain.enums;

import java.time.Duration;
import java.time.Instant;

/**
 * How short of a blood group a bank was when it sent a donation appeal, and what
 * that implies about when it may send another (CLAUDE.md Business Rule 12).
 *
 * <h2>Why these thresholds are not {@link StockStatus}'s</h2>
 * They look similar and are deliberately not the same numbers. StockStatus is a
 * *display* rule (Business Rule 3): below 15 Critical, below 40 Low, otherwise
 * Sufficient — what the badge in the table says. This is an *action* rule: an
 * appeal may only be sent below 30. The gap is real and intentional — a group
 * holding 30-39 units shows a Low badge but cannot be appealed for, because
 * asking donors to come in for stock that is merely off its target spends
 * goodwill the bank will need at 12 units. Keeping the two sets of numbers in
 * separate types is what stops a later change to one silently moving the other.
 *
 * <h2>The two tiers</h2>
 * <ul>
 *   <li>{@code CRITICAL} — fewer than 15 units at send time. Short cooldown (5
 *       hours), because a bank this low may legitimately need to ask again
 *       quickly, and recovers as soon as stock reaches 15.</li>
 *   <li>{@code LOW} — 15 to 29 units at send time. Long cooldown (24 hours), and
 *       recovers at 30, where there is no longer a shortage to appeal about.</li>
 * </ul>
 *
 * Each tier's recovery threshold is the top of its own band, which is the same
 * thing as "the threshold that triggered it": a CRITICAL appeal was sent because
 * stock was under 15, so reaching 15 is the shortage ending; a LOW appeal was
 * sent because stock was under 30, so reaching 30 is.
 */
public enum AppealTier {

    CRITICAL(15, Duration.ofHours(5)),
    LOW(30, Duration.ofHours(24));

    /** At or above this many units there is no shortage worth appealing about. */
    public static final int SHORTAGE_BELOW = 30;

    /** Below this many units a shortage counts as critical rather than merely low. */
    public static final int CRITICAL_BELOW = 15;

    private final int recoveryThreshold;
    private final Duration cooldownWindow;

    AppealTier(int recoveryThreshold, Duration cooldownWindow) {
        this.recoveryThreshold = recoveryThreshold;
        this.cooldownWindow = cooldownWindow;
    }

    /**
     * The tier an appeal sent at this stock level would be recorded as, or null if
     * no appeal may be sent at all because there is no shortage.
     *
     * Null rather than a fourth "NONE" constant: a tier is something a *sent*
     * appeal has, and a stock level with no shortage produces no appeal to give a
     * tier to. A NONE constant would have to be excluded by hand everywhere a
     * stored tier is read back, which is exactly the kind of check that gets
     * forgotten once.
     */
    public static AppealTier forUnits(int unitsAvailable) {
        if (unitsAvailable < CRITICAL_BELOW) {
            return CRITICAL;
        }
        if (unitsAvailable < SHORTAGE_BELOW) {
            return LOW;
        }
        return null;
    }

    /** Whether this stock level is short enough for an appeal to be sent at all. */
    public static boolean isShortage(int unitsAvailable) {
        return unitsAvailable < SHORTAGE_BELOW;
    }

    /**
     * Whether stock has climbed back over the threshold that triggered this tier's
     * appeal — 15 for CRITICAL, 30 for LOW.
     *
     * This is what lets a cooldown end early: the cooldown exists to stop a bank
     * appealing repeatedly about the same unresolved shortage, so once that
     * shortage is over the cooldown has nothing left to protect against.
     */
    public boolean hasRecoveredAt(int unitsAvailable) {
        return unitsAvailable >= recoveryThreshold;
    }

    /**
     * Whether this tier's cooldown timer has run out for an appeal sent at
     * {@code lastSentAt}.
     *
     * The boundary is inclusive of the window — at exactly 5 hours a CRITICAL
     * cooldown is still in force, and it lifts after that. "No more than 5 hours
     * elapsed" is the rule as written, so 5 hours exactly is still "no more than".
     */
    public boolean hasExpiredBy(Instant lastSentAt, Instant now) {
        return Duration.between(lastSentAt, now).compareTo(cooldownWindow) > 0;
    }

    /** Exposed for the message shown when a send is refused, so the operator is
     *  told how long the wait is rather than only that there is one. */
    public Duration getCooldownWindow() {
        return cooldownWindow;
    }

    public int getRecoveryThreshold() {
        return recoveryThreshold;
    }
}
