package com.bloodlink.admin.dto;

/**
 * The response to POST /api/admin/users and the only time the generated password is readable (Business Rule 14).
 * Only its hash is stored, so if it is lost the account needs a new one; account lets the console add the row directly.
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
