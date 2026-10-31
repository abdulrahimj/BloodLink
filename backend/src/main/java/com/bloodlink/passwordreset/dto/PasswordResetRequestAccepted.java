package com.bloodlink.passwordreset.dto;

/**
 * The one answer the "Forgot password?" form ever gets (Business Rule 33), whether or not the number is registered.
 */
public record PasswordResetRequestAccepted(String message) {

    public static final String MESSAGE = "If this number is registered, an administrator will contact you.";

    public static PasswordResetRequestAccepted always() {
        return new PasswordResetRequestAccepted(MESSAGE);
    }
}
