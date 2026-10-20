package com.bloodlink.web.error;

/**
 * Fields individually valid but wrong in combination (e.g. a facility required by role, Business Rule 14):
 * a 400 keyed to getField(), so the form highlights it. Distinct from ConflictException (409).
 */
public class InvalidFieldException extends RuntimeException {

    private final String field;

    public InvalidFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The request field the message is about, e.g. "role" or "hospitalId". */
    public String getField() {
        return field;
    }
}
