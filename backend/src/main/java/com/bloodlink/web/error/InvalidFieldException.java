package com.bloodlink.web.error;

/**
 * A request whose fields are each individually well-formed but wrong in
 * combination — the kind of rule Bean Validation cannot express, because judging it
 * means looking at two fields at once.
 *
 * The case it was written for: creating a staff account, where whether
 * {@code hospitalId} is required, forbidden, or ignored depends entirely on the
 * {@code role} chosen alongside it (Business Rule 14). No annotation on either
 * field can say that; the service has to, and this is how it says it in the same
 * shape the annotations would have.
 *
 * Mapped to HTTP 400 by GlobalExceptionHandler with the message attached to
 * {@link #getField()}, so a form highlights the input that is actually wrong rather
 * than showing a page-level banner the user has to match up to a field themselves.
 *
 * Distinct from {@link ConflictException} (409), which is for a request that is
 * correct in itself but clashes with data that already exists — a phone number
 * already registered. The difference matters to the caller: a 400 here means
 * "change what you typed", a 409 means "this belongs to someone else already".
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
