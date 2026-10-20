package com.bloodlink.web.error;

/**
 * Authorised for the URL but the resource doesn't exist for this caller
 * (e.g. an ADMIN on /api/donors/me); mapped to 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
