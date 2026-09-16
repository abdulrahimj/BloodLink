package com.bloodlink.admin.dto;

/**
 * The response to POST /api/admin/users, and the only time the new account's
 * password is ever readable (Business Rule 14).
 *
 * <h2>Why the plaintext password is in a response body at all</h2>
 * Someone has to be able to tell the new staff member how to sign in the first time.
 * The alternatives available to this deployment are worse: there is no email or SMS
 * delivery in BloodLink, and storing the plaintext for later retrieval would mean
 * keeping a readable password in the database permanently — exactly what hashing
 * exists to prevent. So it is shown once, to the administrator who just created the
 * account, and then it is gone: only the BCrypt hash is stored, and no endpoint can
 * return this value again. If it is lost, the account needs a new password, not a
 * lookup.
 *
 * This is why the Admin Console's confirmation screen says plainly that the password
 * cannot be shown again — that is a true statement about the system, not a
 * precaution the interface invented.
 *
 * <h2>Why the account comes back too</h2>
 * {@code account} is the same {@link AdminUserResponse} the user table is built
 * from, so the console can add the new row to the list it already has rather than
 * re-fetching every account to see one new one.
 */
public record CreatedAccountResponse(

    /** The created account, in the shape the Admin Console's table already renders. */
    AdminUserResponse account,

    /**
     * The generated password, in plaintext, for this one response only. Never
     * stored in this form, never logged, and not retrievable afterwards.
     */
    String temporaryPassword
) {}
