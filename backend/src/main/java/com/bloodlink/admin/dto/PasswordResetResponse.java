package com.bloodlink.admin.dto;

/**
 * The response to POST /api/admin/users/{id}/password-reset (Business Rule 31), the only time the new temporary
 * password is readable; sent with Cache-Control: no-store. account lets the console refresh the row.
 */
public record PasswordResetResponse(

    /** The account as it now stands, in the shape the Admin Console's table renders; its status is unchanged. */
    AdminUserResponse account,

    /** The generated password, for this one response only: only its hash is stored, and it is never logged. */
    String temporaryPassword
) {}
