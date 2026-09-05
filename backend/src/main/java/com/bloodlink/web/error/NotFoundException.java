package com.bloodlink.web.error;

/**
 * The caller is authenticated and authorised for this URL pattern, but the specific
 * resource does not exist for them — e.g. an ADMIN token hitting /api/donors/me,
 * where no Donor row exists because administrators are not donors.
 * Mapped to HTTP 404 by GlobalExceptionHandler.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
