package com.bloodlink.web.error;

/**
 * A request that is well-formed but clashes with existing data — a phone number or
 * email already registered. Mapped to HTTP 409 by GlobalExceptionHandler.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
