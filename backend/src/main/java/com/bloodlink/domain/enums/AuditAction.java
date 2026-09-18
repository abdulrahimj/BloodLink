package com.bloodlink.domain.enums;

/**
 * The administrator actions the audit log records — Business Rule 17. Stored by
 * name; V12's CHECK constraint lists the same four values.
 *
 * Only actions that change someone else's account. Reading (listing users,
 * viewing reports) is not recorded: an audit log of every page view would bury
 * the handful of entries that matter under thousands that do not.
 */
public enum AuditAction {

    /** A staff or administrator account was created (Business Rule 14). */
    ACCOUNT_CREATED,

    /** An account's phone or facility was changed by an administrator. */
    ACCOUNT_EDITED,

    /** An active account was suspended. */
    ACCOUNT_SUSPENDED,

    /** A suspended account was made active again. */
    ACCOUNT_REACTIVATED
}
