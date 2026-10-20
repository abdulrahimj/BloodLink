package com.bloodlink.domain.enums;

/**
 * Whether an account may sign in and act; added for the Admin module's activate/suspend use case.
 * Every account starts ACTIVE; SUSPENDED is reached only by an administrator's action.
 */
public enum AccountStatus {
    ACTIVE,
    SUSPENDED
}
