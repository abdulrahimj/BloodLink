package com.bloodlink.ratelimit;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.audit.AuditLogService;
import com.bloodlink.domain.User;
import com.bloodlink.repository.UserRepository;

/**
 * Writes the audit entry for a locked phone number (Business Rule 34). Runs after the failed sign-in's transaction
 * has ended, so it opens its own. An unregistered number has no account to point at, and its entry says so.
 */
@Service
public class SignInLockAudit {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    SignInLockAudit(UserRepository userRepository, AuditLogService auditLogService, Clock clock) {
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public void record(String normalisedPhone, int failures) {
        User account = userRepository.findByPhone(normalisedPhone).orElse(null);
        auditLogService.recordSignInLocked(account,
            "Sign-in locked for %s after %d failed attempts".formatted(normalisedPhone, failures), clock.instant());
    }
}
