package com.bloodlink.domain.enums;

/**
 * The administrator actions the audit log records — Business Rules 17 and 19.
 * Stored by name; V14's CHECK constraint lists the same six values.
 *
 * Only actions that change something: someone else's account, or the set of
 * facilities that exist. Reading (listing users, viewing reports) is not recorded:
 * an audit log of every page view would bury the handful of entries that matter
 * under thousands that do not.
 *
 * Each action has exactly one kind of subject, and V14's
 * {@code audit_log_target_matches_action} constraint holds every row to it: the
 * four ACCOUNT_ actions point at a user, the two facility actions at a blood bank
 * or a hospital. {@link #isAccountAction()} is the Java side of that same split.
 */
public enum AuditAction {

    /** A staff or administrator account was created (Business Rule 14). */
    ACCOUNT_CREATED,

    /** An account's phone or facility was changed by an administrator. */
    ACCOUNT_EDITED,

    /** An active account was suspended. */
    ACCOUNT_SUSPENDED,

    /** A suspended account was made active again. */
    ACCOUNT_REACTIVATED,

    /** A new blood bank was added (Business Rule 19). */
    BLOOD_BANK_CREATED,

    /** A new hospital was added (Business Rule 19). */
    HOSPITAL_CREATED;

    /** True for the four actions whose subject is a user account. */
    public boolean isAccountAction() {
        return this != BLOOD_BANK_CREATED && this != HOSPITAL_CREATED;
    }
}