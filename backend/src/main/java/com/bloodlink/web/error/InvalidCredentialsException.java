package com.bloodlink.web.error;

/**
 * Login failed: one message for unknown phone and wrong password, so accounts cannot be enumerated (401).
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
