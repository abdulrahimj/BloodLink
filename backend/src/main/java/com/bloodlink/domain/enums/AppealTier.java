package com.bloodlink.domain.enums;

import java.time.Duration;
import java.time.Instant;

/**
 * How short a bank was when it appealed, and what that means for the next appeal (Business Rule 12): CRITICAL under 15
 * (5h, clears at 15), LOW 15–29 (24h, clears at 30). Deliberately separate from StockStatus's badge thresholds.
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
     * The tier an appeal sent at this stock level would get, or null when there is no shortage
     * (no NONE constant, since only a sent appeal has a tier).
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
     * Whether stock is back over this tier's trigger threshold (15 CRITICAL, 30 LOW), which clears the cooldown.
     * Asked when stock rises, so a later relapse counts as a new shortage.
     */
    public boolean hasRecoveredAt(int unitsAvailable) {
        return unitsAvailable >= recoveryThreshold;
    }

    /**
     * Whether this tier's cooldown has run out for an appeal sent at lastSentAt;
     * exactly 5 (or 24) hours is still within the window.
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
