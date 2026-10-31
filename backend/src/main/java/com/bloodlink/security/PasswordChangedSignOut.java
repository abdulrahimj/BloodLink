package com.bloodlink.security;

/**
 * How a token issued before the account's last password change is refused (Business Rule 32): a 401 with this code
 * and message on any address that needs a sign-in, and a refused or closed socket.
 */
public final class PasswordChangedSignOut {

    /** The code on the 401, so the frontend can tell it from an expired session and say why. */
    public static final String ERROR_CODE = "PASSWORD_CHANGED";
    public static final String MESSAGE = "Your password was changed. Please sign in again.";

    /** Set on the request by JwtAuthenticationFilter, read by RestAuthenticationEntryPoint. */
    static final String REQUEST_ATTRIBUTE = PasswordChangedSignOut.class.getName();

    private PasswordChangedSignOut() {
    }
}
