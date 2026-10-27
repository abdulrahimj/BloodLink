package com.bloodlink.repository;

import com.bloodlink.domain.enums.AccountStatus;

/**
 * The two facts every authenticated request and socket connection is judged on, read in one lookup by primary key:
 * whether the account is ACTIVE, and whether it must still replace a password someone else set (Business Rule 27).
 */
public record AccountAccess(AccountStatus status, boolean mustChangePassword) {

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
