package com.bloodlink.common;

/**
 * The one password strength rule (8–72 characters), shared by registration, password changes and the
 * first-administrator bootstrap (Business Rule 27). 72 is BCrypt's input limit.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;

    private PasswordPolicy() {
    }

    /** Why the password is refused, or null when it is acceptable; never repeats the password itself. */
    public static String problemWith(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "it must be at least " + MIN_LENGTH + " characters long";
        }
        if (password.length() > MAX_LENGTH) {
            return "it must be at most " + MAX_LENGTH + " characters long";
        }
        return null;
    }
}
