package com.bloodlink.account;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.account.dto.AccountResponse;
import com.bloodlink.account.dto.ChangePasswordPayload;
import com.bloodlink.account.dto.UpdateAccountPayload;
import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.common.EmailAddress;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.JwtService;
import com.bloodlink.security.PasswordChangedEvent;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.IncorrectCurrentPasswordException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * The caller's own account for every role (Business Rule 16): read it, edit name and email, change password.
 * Acts only on the id from the token; phone, role, facility and status are refused whole with a 400 naming the field.
 */
@Service
public class AccountService {

    /**
     * Why each administrator-managed field is refused, checked in this order so the most important is reported first.
     * Anything unlisted gets a generic refusal; the phone message points at no admin edit because none exists.
     */
    private static final Map<String, String> ADMIN_MANAGED_FIELDS = Map.of(
        "phone", "Your phone number is how you sign in, so it cannot be changed here.",
        "role", "Your role is assigned by an administrator and cannot be changed here.",
        "bloodBankId", "Your blood bank is assigned by an administrator and cannot be changed here.",
        "hospitalId", "Your hospital is assigned by an administrator and cannot be changed here.",
        "status", "Account status is managed by an administrator and cannot be changed here."
    );
    private static final List<String> ADMIN_MANAGED_ORDER =
        List.of("phone", "role", "bloodBankId", "hospitalId", "status");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ApplicationEventPublisher eventPublisher;

    AccountService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        ApplicationEventPublisher eventPublisher
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.eventPublisher = eventPublisher;
    }

    /** The caller's own account, for the Profile Settings screen. */
    @Transactional(readOnly = true)
    public AccountResponse getMyAccount(UUID userId) {
        return AccountResponse.from(load(userId));
    }

    /**
     * Changes the caller's name and email, normalised as registration does; no current password (not credentials).
     * Email follows the role (Business Rule 18): a donor may leave it blank, every other role must keep one.
     */
    @Transactional
    public AccountResponse updateMyDetails(UUID userId, UpdateAccountPayload payload) {
        rejectUnrecognised(payload.unrecognised(), "fullName", "email");

        User user = load(userId);
        String email = emailFor(user, payload.email());

        // Only an email that actually changes can collide: re-saving your own is fine, and no email never collides.
        if (email != null && !email.equals(user.getEmail()) && userRepository.existsByEmail(email)) {
            throw new ConflictException("An account already exists for this email address.");
        }

        user.setFullName(payload.fullName().trim());
        user.setEmail(email);
        return AccountResponse.from(userRepository.save(user));
    }

    /**
     * Changes the password after verifying the current one (401 if wrong, nothing written); clears must-change (Rule 27).
     * Every older token stops working (Business Rule 32), so the answer carries a fresh one for this device.
     */
    @Transactional
    public AuthResponse changeMyPassword(UUID userId, ChangePasswordPayload payload) {
        rejectUnrecognised(payload.unrecognised(), "currentPassword", "newPassword");

        User user = load(userId);

        if (!passwordEncoder.matches(payload.currentPassword(), user.getPasswordHash())) {
            throw new IncorrectCurrentPasswordException("Your current password is incorrect.");
        }
        if (passwordEncoder.matches(payload.newPassword(), user.getPasswordHash())) {
            throw new InvalidFieldException("newPassword", "Choose a new password that is different from your current one.");
        }

        Instant changedAt = Instant.now();
        user.replacePassword(passwordEncoder.encode(payload.newPassword()), changedAt);
        user.setMustChangePassword(false);
        User saved = userRepository.save(user);
        eventPublisher.publishEvent(new PasswordChangedEvent(saved.getId(), changedAt));

        // Issued at the change's own time, so it is never judged older than it.
        return AuthResponse.of(jwtService.issueToken(saved, changedAt), jwtService.getExpirySeconds(), saved);
    }

    /**
     * Refuses (400 on that field) any property this endpoint does not accept; the payloads capture them so this can.
     * A null value still counts: "phone": null is an attempt to clear the phone.
     */
    private static void rejectUnrecognised(Map<String, Object> unrecognised, String... allowed) {
        if (unrecognised == null || unrecognised.isEmpty()) {
            return;
        }

        for (String field : ADMIN_MANAGED_ORDER) {
            if (unrecognised.containsKey(field)) {
                throw new InvalidFieldException(field, ADMIN_MANAGED_FIELDS.get(field));
            }
        }

        // A field sent to the wrong endpoint is a client using the wrong form, so say where it belongs.
        // Sorted so the same request always reports the same field.
        String field = new TreeSet<>(unrecognised.keySet()).first();
        throw new InvalidFieldException(
            field,
            "'" + field + "' cannot be changed here. This request accepts only: " + String.join(", ", allowed) + "."
        );
    }

    /**
     * The email to store by role (Business Rule 18): a donor's goes through registration's shared function.
     * Staff and administrators must have one; a 400 on the field explains it better than V13's constraint error.
     */
    private static String emailFor(User user, String raw) {
        if (user.getRole() == Role.DONOR) {
            return EmailAddress.normalizeOptional(raw);
        }
        if (raw == null || raw.isBlank()) {
            throw new InvalidFieldException("email", "Email address is required");
        }
        return raw.trim().toLowerCase();
    }

    /** The caller's row. A missing one is a 404, though a valid token for a
     *  since-deleted account would already have been refused by the filter. */
    private User load(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new NotFoundException("No account found for this user."));
    }
}
