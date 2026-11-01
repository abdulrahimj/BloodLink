package com.bloodlink.domain.enums;

/**
 * The audit log's actions (Business Rules 17, 19, 24, 28, 31, 33, 34): only changes, never reads. Each has one kind of subject
 * (V31's CHECK); all are an administrator's except STOCK_WRITTEN_OFF, DONOR_BLOOD_GROUP_UPDATED and SIGN_IN_LOCKED.
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
    STOCK_WRITTEN_OFF,

    /** A donor's profile blood group was set or changed by blood bank staff (Business Rule 28, V26),
     *  from a donation's test or a manual correction. Its subject is the donor's user account. */
    DONOR_BLOOD_GROUP_UPDATED,

    /** An administrator reset another account's password to a temporary one (Business Rule 31, V28).
     *  Its subject is that account; the password itself never appears in an entry. */
    ADMIN_PASSWORD_RESET,

    /** An administrator closed a password reset request without resetting the password (Business Rule 33, V30).
     *  Its subject is the account that asked. */
    PASSWORD_REQUEST_CLOSED,

    /** Failed sign-ins locked a phone number (Business Rule 34, V31). The system did it, so it has no actor;
     *  its subject is the account holding the number, or none when the number is unregistered. */
    SIGN_IN_LOCKED;

    /**
     * True for the actions whose subject is a user account (the four ACCOUNT_ actions, DONOR_BLOOD_GROUP_UPDATED,
     * ADMIN_PASSWORD_RESET and PASSWORD_REQUEST_CLOSED); named positively so a new action is never one by default.
     */
    public boolean isAccountAction() {
        return this == ACCOUNT_CREATED || this == ACCOUNT_EDITED
            || this == ACCOUNT_SUSPENDED || this == ACCOUNT_REACTIVATED
            || this == DONOR_BLOOD_GROUP_UPDATED || this == ADMIN_PASSWORD_RESET || this == PASSWORD_REQUEST_CLOSED;
    }
}
