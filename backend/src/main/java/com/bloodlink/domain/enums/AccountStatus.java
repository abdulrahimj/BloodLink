package com.bloodlink.domain.enums;

/**
 * Whether an account may currently sign in and act.
 *
 * Not part of the original CLAUDE.md schema table — added for the Admin module's
 * "activate/suspend" use case, the same way blood_bank_id and hospital_id were added
 * to User in V5 and V7: a real requirement the fixed schema had no column for yet.
 *
 * Every account is created ACTIVE (registration, and any future admin-created staff
 * account); SUSPENDED is reached only through an administrator's own action.
 */
public enum AccountStatus {
    ACTIVE,
    SUSPENDED
}
