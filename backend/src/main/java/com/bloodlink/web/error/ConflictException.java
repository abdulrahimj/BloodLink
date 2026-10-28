package com.bloodlink.web.error;

/**
 * A request that is well-formed but clashes with existing data — a phone number or
 * email already registered. Mapped to HTTP 409 by GlobalExceptionHandler.
 */
public class ConflictException extends RuntimeException {

    /** A stable machine-readable code the client acts on (ApiError.code), or null for most conflicts. */
    private final String code;

    public ConflictException(String message) {
        this(null, message);
    }

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
