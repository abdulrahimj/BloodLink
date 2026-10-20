package com.bloodlink.web.error;

/**
 * Correct credentials, but the account is suspended; thrown only after authentication, so saying so leaks nothing.
 * Mapped to 403, since re-authenticating would not help.
 */
public class AccountSuspendedException extends RuntimeException {

    public AccountSuspendedException(String message) {
        super(message);
    }
}
