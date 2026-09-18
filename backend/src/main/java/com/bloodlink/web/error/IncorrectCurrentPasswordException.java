package com.bloodlink.web.error;

/**
 * A signed-in user asked to change their password and got their current one wrong
 * (Business Rule 16). Mapped to HTTP 401 by GlobalExceptionHandler, with the message
 * attached to the "currentPassword" field.
 *
 * <h2>Why 401, and why it names a field</h2>
 * 401 because this is a failed proof of identity — the same thing a wrong password
 * at login is — and the request is refused for exactly that reason. It is not a
 * malformed request (400) and not a lack of permission (403): the caller is allowed
 * to change their password, provided they prove they are the account holder.
 *
 * The field is what keeps that 401 from being mistaken for an expired session. The
 * frontend's API client discards the stored session on a 401, because a 401 on any
 * other authenticated request means the token is no longer good. This one does not:
 * the token is fine, the typed password is not. A session-level 401 (from
 * RestAuthenticationEntryPoint) never carries field errors, so "a 401 that names a
 * form field is about that field" is how the client tells the two apart — and the
 * user who mistypes their current password stays signed in and sees the message
 * under the box they typed it in.
 *
 * Distinct from InvalidCredentialsException, whose message is deliberately vague so
 * a failed login cannot reveal which phone numbers are registered. There is nothing
 * to hide here: the caller already holds a valid session for this very account.
 */
public class IncorrectCurrentPasswordException extends RuntimeException {

    public IncorrectCurrentPasswordException(String message) {
        super(message);
    }
}
