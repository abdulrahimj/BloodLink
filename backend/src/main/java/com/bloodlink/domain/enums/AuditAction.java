package com.bloodlink.domain.enums;

/**
 * The actions the audit log records (Business Rules 17, 19, 24): only changes, never reads. Each has one kind
 * of subject (V18's CHECK); every action is an administrator's except STOCK_WRITTEN_OFF (bank staff).
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
     *  of that bank — never by an administrator. Its subject is the batch. */
    STOCK_WRITTEN_OFF;

    /**
     * True for the four actions whose subject is a user account; named positively
     * so a new action is never an account action by default.
     */
    public boolean isAccountAction() {
        return this == ACCOUNT_CREATED || this == ACCOUNT_EDITED
            || this == ACCOUNT_SUSPENDED || this == ACCOUNT_REACTIVATED;
    }
}
