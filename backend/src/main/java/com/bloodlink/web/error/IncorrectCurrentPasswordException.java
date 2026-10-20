package com.bloodlink.web.error;

/**
 * A wrong current password on a password change (Business Rule 16): 401 with the message on "currentPassword".
 * The field tells the frontend this is not an expired session, so the user stays signed in.
 */
public class IncorrectCurrentPasswordException extends RuntimeException {

    public IncorrectCurrentPasswordException(String message) {
        super(message);
    }
}
