package com.bloodlink.domain.enums;

/**
 * How an administrator handled a password reset request (Business Rule 33); null while it is waiting.
 */
public enum PasswordResetResolution {
    /** An administrator reset the account's password (Business Rule 31), which closed the request. */
    RESET,
    /** Marked done without a reset, with an optional note; audited as PASSWORD_REQUEST_CLOSED. */
    CLOSED
}
