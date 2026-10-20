package com.bloodlink.common;

import java.util.regex.Pattern;

import com.bloodlink.web.error.InvalidFieldException;

/**
 * Format check for a donor's optional email (Business Rule 18): name@domain.tld, no network lookup, to catch typos.
 * Stricter than @Email (which accepts name@gmail); non-Latin addresses are not supported.
 */
public final class EmailAddress {

    /**
     * Applied after trimming and lower-casing: local part of letters, digits and ._%+- with no edge or double dot;
     * domain labels without edge hyphens, each followed by a dot; a 2–24 letter top-level domain.
     */
    private static final Pattern FORMAT = Pattern.compile(
        "^(?!\\.)(?!.*\\.\\.)[a-z0-9._%+-]+(?<!\\.)"
            + "@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
            + "[a-z]{2,24}$"
    );

    private static final int MAX_LOCAL_PART = 64;
    private static final int MAX_LENGTH = 254;

    private static final String MESSAGE =
        "Enter a valid email address, like name@example.com — or leave it blank.";

    private EmailAddress() {
        // Utility class.
    }

    /**
     * Normalises an optional email: blank becomes null (never "", which V13 forbids), else trimmed, lower-cased
     * and format-checked; throws InvalidFieldException on "email" (a 400) if malformed.
     */
    public static String normalizeOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String email = raw.trim().toLowerCase();
        int at = email.indexOf('@');
        boolean withinLimits = email.length() <= MAX_LENGTH && at > 0 && at <= MAX_LOCAL_PART;

        if (!withinLimits || !FORMAT.matcher(email).matches()) {
            throw new InvalidFieldException("email", MESSAGE);
        }
        return email;
    }
}
