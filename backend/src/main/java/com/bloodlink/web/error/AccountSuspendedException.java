package com.bloodlink.web.error;

/**
 * The credentials were correct, but an administrator has suspended this account.
 *
 * Deliberately distinct from InvalidCredentialsException: that one is vague on
 * purpose, so a failed login cannot be used to discover which phone numbers are
 * registered. This exception is only ever thrown after authentication has already
 * succeeded, so the caller has already proven they own the account — telling them
 * plainly why they are being refused leaks nothing an attacker could not already
 * see, and is far more useful to a suspended staff member than a repeat of
 * "invalid phone number or password".
 *
 * Mapped to HTTP 403 by GlobalExceptionHandler — the account is known and the
 * password was right, so re-authenticating would not help, which is exactly what
 * 403 (as opposed to 401) means here.
 */
public class AccountSuspendedException extends RuntimeException {

    public AccountSuspendedException(String message) {
        super(message);
    }
}
