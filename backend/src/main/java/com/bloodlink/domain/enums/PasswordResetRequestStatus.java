package com.bloodlink.domain.enums;

/**
 * A password reset request's state (Business Rule 33). WAITING is the only open state; at most one per account (V30).
 */
public enum PasswordResetRequestStatus {
    /** Asked for, and no administrator has handled it yet. */
    WAITING,
    /** Handled by an administrator: the password was reset, or the request was closed without one. */
    DONE
}
