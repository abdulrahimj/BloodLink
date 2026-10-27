package com.bloodlink.admin.bootstrap;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.audit.AuditLogService;
import com.bloodlink.common.EmailAddress;
import com.bloodlink.common.PasswordPolicy;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.InvalidPhoneNumberException;

/**
 * Creates the first administrator from startup settings when no ACTIVE ADMIN exists (Business Rule 27).
 * Never changes an existing account; every refusal is logged and creates nothing; the password is never logged.
 */
@Service
public class BootstrapAdminService {

    /** A fixed key for PostgreSQL's advisory lock: every server starting at once queues on the same one. */
    static final long LOCK_KEY = 0x426C_6F6F_644C_6E6BL; // "BloodLnk" in ASCII

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminService.class);

    /** What one bootstrap attempt did, for the caller and the tests; the log says it in words. */
    public enum Outcome { CREATED, ADMIN_EXISTS, SETTINGS_MISSING, INVALID_SETTING, PHONE_IN_USE, EMAIL_IN_USE }

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    BootstrapAdminService(UserRepository userRepository, PasswordEncoder passwordEncoder, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /**
     * One attempt, in one transaction holding the advisory lock, so a second server starting at the same moment waits
     * and then sees the first one's administrator. Checks run in order and the first failure stops it.
     */
    @Transactional
    public Outcome createFirstAdminIfNeeded(BootstrapAdminSettings settings) {
        userRepository.waitForAdvisoryLock(LOCK_KEY);

        if (userRepository.existsByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)) {
            if (settings.anySet()) {
                log.info("An active administrator already exists, so the BLOODLINK_BOOTSTRAP_ADMIN_* settings were "
                    + "ignored and no account was changed. They can be removed.");
            }
            return Outcome.ADMIN_EXISTS;
        }

        List<String> missing = settings.missing();
        if (!missing.isEmpty()) {
            log.warn("No active administrator account exists, so nobody can create staff accounts or manage users. "
                + "To create the first administrator, set {}, {}, {} and {} in the environment and restart "
                + "(see the README, \"Creating the first administrator\"). Not set: {}.",
                BootstrapAdminSettings.PHONE_VARIABLE, BootstrapAdminSettings.NAME_VARIABLE,
                BootstrapAdminSettings.EMAIL_VARIABLE, BootstrapAdminSettings.PASSWORD_VARIABLE,
                String.join(", ", missing));
            return Outcome.SETTINGS_MISSING;
        }

        String phone;
        String email;
        try {
            // The same shared rules every other account goes through (Business Rules 13 and 18).
            phone = PhoneNumber.normalize(settings.phone());
            email = EmailAddress.normalizeOptional(settings.email());
        } catch (InvalidPhoneNumberException ex) {
            return refuse(BootstrapAdminSettings.PHONE_VARIABLE, ex.getMessage());
        } catch (InvalidFieldException ex) {
            return refuse(BootstrapAdminSettings.EMAIL_VARIABLE, ex.getMessage());
        }
        String fullName = settings.fullName().trim();
        if (fullName.length() > 255) {
            return refuse(BootstrapAdminSettings.NAME_VARIABLE, "A name must be 255 characters or fewer.");
        }
        String passwordProblem = PasswordPolicy.problemWith(settings.password());
        if (passwordProblem != null) {
            return refuse(BootstrapAdminSettings.PASSWORD_VARIABLE, "The password is too weak: " + passwordProblem + ".");
        }

        if (userRepository.existsByPhone(phone)) {
            log.error("The phone number in {} ({}) already belongs to another account, so no administrator was created. "
                + "Existing accounts are never changed by these settings; use a number no account has.",
                BootstrapAdminSettings.PHONE_VARIABLE, phone);
            return Outcome.PHONE_IN_USE;
        }
        if (userRepository.existsByEmail(email)) {
            log.error("The email address in {} ({}) already belongs to another account, so no administrator was created. "
                + "Existing accounts are never changed by these settings; use an address no account has.",
                BootstrapAdminSettings.EMAIL_VARIABLE, email);
            return Outcome.EMAIL_IN_USE;
        }

        User admin = new User();
        admin.setFullName(fullName);
        admin.setPhone(phone);
        admin.setEmail(email);
        admin.setRole(Role.ADMIN);
        admin.setStatus(AccountStatus.ACTIVE);
        admin.setPasswordHash(passwordEncoder.encode(settings.password()));
        // Whoever set the variable knows this password, so the administrator replaces it at first sign-in.
        admin.setMustChangePassword(true);
        User saved = userRepository.save(admin);

        // No administrator was signed in to act, so the new account is recorded as both actor and subject.
        auditLogService.record(saved.getId(), AuditAction.ACCOUNT_CREATED, saved,
            "Created Administrator account for " + saved.getFullName() + ", phone " + saved.getPhone()
                + ", from the startup settings, because no active administrator existed.");

        log.info("Created the first administrator account for {} (phone {}) from the BLOODLINK_BOOTSTRAP_ADMIN_* "
            + "settings. They must choose a new password when they first sign in. The settings can now be removed.",
            saved.getFullName(), saved.getPhone());
        return Outcome.CREATED;
    }

    /** Logs why one setting was refused and creates nothing; the reason never contains the password. */
    private static Outcome refuse(String variable, String reason) {
        log.error("{} was refused, so no administrator was created: {}", variable, reason);
        return Outcome.INVALID_SETTING;
    }
}
