package com.bloodlink.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an existing account's password changes (its own change or an administrator's reset), so its older
 * notification sockets are closed once the change commits (Business Rule 32).
 */
public record PasswordChangedEvent(UUID userId, Instant changedAt) {
}
