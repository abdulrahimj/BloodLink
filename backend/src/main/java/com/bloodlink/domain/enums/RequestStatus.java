package com.bloodlink.domain.enums;

// Lifecycle: PENDING -> APPROVED -> FULFILLED, or PENDING -> REJECTED (CLAUDE.md Business Rule 5).
public enum RequestStatus {
    PENDING,
    APPROVED,
    FULFILLED,
    REJECTED
}
