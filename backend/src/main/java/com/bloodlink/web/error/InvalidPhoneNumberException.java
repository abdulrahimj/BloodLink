package com.bloodlink.web.error;

/**
 * A phone number that is not in — and cannot be converted to — the format this
 * system stores. Raised by {@code PhoneNumber.normalize} and mapped to HTTP 400 by
 * GlobalExceptionHandler, with the message attached to the "phone" field so the
 * Register and Login forms can highlight that input.
 *
 * A 400 rather than the vague 401 a failed login returns, even when it is a login
 * that raised it. This says nothing about whether any account exists: it is a
 * statement about the shape of what was typed, and the accepted shape is published
 * on both forms as their placeholder. Answering "invalid phone number or password"
 * to someone who typed seven digits would hide a typo they can fix behind an error
 * about a password that was never the problem.
 *
 * Extends RuntimeException rather than IllegalArgumentException on purpose:
 * GlobalExceptionHandler already maps the latter to a plain 400 with no field
 * attached, and this exception carries a field.
 */
public class InvalidPhoneNumberException extends RuntimeException {

    public InvalidPhoneNumberException(String message) {
        super(message);
    }
}
