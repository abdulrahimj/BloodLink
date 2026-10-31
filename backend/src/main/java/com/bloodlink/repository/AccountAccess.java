package com.bloodlink.repository;

import java.time.Instant;
import java.util.Date;

import com.bloodlink.domain.enums.AccountStatus;

/**
 * What every authenticated request and socket connection is judged on, in one lookup by primary key: ACTIVE or not,
 * must still replace a temporary password (Business Rule 27), and when the password last changed (Business Rule 32).
 */
public record AccountAccess(AccountStatus status, boolean mustChangePassword, Instant passwordChangedAt) {

    /** For callers that do not care about the password-change time (null: no restriction). */
    public AccountAccess(AccountStatus status, boolean mustChangePassword) {
        this(status, mustChangePassword, null);
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    /**
     * True when a token issued at this time predates the last password change. Whole seconds, as a token's issued-at
     * is: one issued in the same second as the change still works. No issued-at at all counts as older.
     */
    public boolean tokenIssuedBeforePasswordChange(Date issuedAt) {
        return issuedBefore(issuedAt, passwordChangedAt);
    }

    /** The same comparison for a known change time; shared with the socket registry so the two cannot disagree. */
    public static boolean issuedBefore(Date issuedAt, Instant passwordChangedAt) {
        if (passwordChangedAt == null) {
            return false;
        }
        return issuedAt == null || issuedAt.toInstant().getEpochSecond() < passwordChangedAt.getEpochSecond();
    }
}
