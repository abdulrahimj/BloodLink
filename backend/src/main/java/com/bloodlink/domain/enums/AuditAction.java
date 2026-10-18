package com.bloodlink.domain.enums;

/**
 * The actions the audit log records — Business Rules 17, 19 and 24.
 * Stored by name; V18's CHECK constraint lists the same seven values.
 *
 * Only actions that change something: someone else's account, the set of
 * facilities that exist, or stock discarded from a batch. Reading (listing users,
 * viewing reports) is not recorded:
 * an audit log of every page view would bury the handful of entries that matter
 * under thousands that do not.
 *
 * Each action has exactly one kind of subject, and V18's
 * {@code audit_log_target_matches_action} constraint holds every row to it: the
 * four ACCOUNT_ actions point at a user, the two facility actions at a blood bank
 * or a hospital, and a write-off at its batch. {@link #isAccountAction()} is the
 * Java side of that same split.
 *
 * Every action but STOCK_WRITTEN_OFF is an administrator's; a write-off may also be
 * made by blood bank staff, for their own bank's batches.
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
    HOSPITAL_CREATED,

    /** Units of one batch were discarded (Business Rule 24, V18) by blood bank staff
     *  of that bank or by an administrator. Its subject is the batch. */
    STOCK_WRITTEN_OFF;

    /**
     * True for the four actions whose subject is a user account. Named positively
     * rather than as "not a facility action", so that adding an action with some
     * other subject (as STOCK_WRITTEN_OFF was) cannot make it an account action by
     * default.
     */
    public boolean isAccountAction() {
        return this == ACCOUNT_CREATED || this == ACCOUNT_EDITED
            || this == ACCOUNT_SUSPENDED || this == ACCOUNT_REACTIVATED;
    }
}
