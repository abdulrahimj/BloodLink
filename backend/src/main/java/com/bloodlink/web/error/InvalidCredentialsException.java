package com.bloodlink.web.error;

/**
 * Login failed. Carries a single message for both "unknown phone" and "wrong
 * password" so the response cannot be used to enumerate registered accounts.
 * Mapped to HTTP 401 by GlobalExceptionHandler.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
