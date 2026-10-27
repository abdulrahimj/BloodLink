package com.bloodlink.security;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

/**
 * What an account that must replace its temporary password may still reach (Business Rule 27): reading its own
 * account, changing its password, and the public endpoints, which never read a token. Signing out is client-side.
 */
public final class PasswordChangeGate {

    /** The code on the 403, so the frontend can tell it from an ordinary permission refusal. */
    public static final String ERROR_CODE = "PASSWORD_CHANGE_REQUIRED";
    public static final String MESSAGE =
        "You must set your own password before you can use BloodLink. Change your temporary password to continue.";

    private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login", "/api/auth/register", "/api/health");

    private PasswordChangeGate() {
    }

    /** True when a flagged account may make this request; everything else is refused with a 403. */
    static boolean allows(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        return ("GET".equals(method) && "/api/users/me".equals(path))
            || ("PATCH".equals(method) && "/api/users/me/password".equals(path))
            || "OPTIONS".equals(method)
            || PUBLIC_PATHS.contains(path);
    }
}
