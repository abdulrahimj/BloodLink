package com.bloodlink.account;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.account.dto.AccountResponse;
import com.bloodlink.account.dto.ChangePasswordPayload;
import com.bloodlink.account.dto.UpdateAccountPayload;
import com.bloodlink.common.EmailAddress;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.IncorrectCurrentPasswordException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * The signed-in user's own account: reading it, editing their name and email, and
 * changing their password — Business Rule 16. The same for every role, including
 * DONOR and ADMIN; nothing here branches on who is asking.
 *
 * Every method acts on the caller's own id, taken from their token by the
 * controller. None accepts a user id from the request, so there is no way to aim
 * one of these at somebody else's account — an administrator managing other
 * accounts does that through /api/admin/users, which is a different set of powers.
 *
 * <h2>What cannot be changed here, by anyone</h2>
 * Phone (the login credential), role, facility (Business Rules 8 and 9) and status.
 * Those arrive only as administrator decisions. A request that tries to set any of
 * them is refused whole, with a 400 naming the field — never partly applied, and
 * never quietly ignored with a 200 that would tell the client it worked.
 */
@Service
public class AccountService {

    /**
     * Why each field that is recognisably someone else's decision was refused. Checked
     * in this order, so a request carrying several reports the most important first.
     * Anything not listed gets a generic refusal instead — the point is that nothing
     * unrecognised is ever accepted, not that every possible name has its own sentence.
     *
     * The phone message says only that it cannot be changed here, not "ask an
     * administrator": no endpoint lets anyone edit a phone after an account exists,
     * an administrator included, so pointing at one would be pointing at nothing.
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

    AccountService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** The caller's own account, for the Profile Settings screen. */
    @Transactional(readOnly = true)
    public AccountResponse getMyAccount(UUID userId) {
        return AccountResponse.from(load(userId));
    }

    /**
     * Changes the caller's full name and email.
     *
     * No current-password check, deliberately: neither field is a credential. Name
     * is what the sidebar shows; email is a contact address and a unique identifier,
     * but nothing signs in with it (phone is the login), so changing it cannot lock
     * the owner out or hand the account to someone else. The password is the one
     * thing that can, and it has its own method below.
     *
     * Normalised the way registration normalises them — name trimmed, email trimmed
     * and lower-cased — so "Isatu@Example.com" and "isatu@example.com" remain one
     * address and the unique constraint means what it says.
     *
     * Email follows the account's role (Business Rule 18): a donor may leave it blank,
     * which keeps or clears it to none, and a donor's address gets the same strict
     * format check as registration. Every other role must keep one, as before.
     */
    @Transactional
    public AccountResponse updateMyDetails(UUID userId, UpdateAccountPayload payload) {
        rejectUnrecognised(payload.unrecognised(), "fullName", "email");

        User user = load(userId);
        String email = emailFor(user, payload.email());

        // Only an email that actually changes can collide — re-saving your own address
        // must not be refused as "already registered" by you — and no email never
        // collides with anything.
        if (email != null && !email.equals(user.getEmail()) && userRepository.existsByEmail(email)) {
            throw new ConflictException("An account already exists for this email address.");
        }

        user.setFullName(payload.fullName().trim());
        user.setEmail(email);
        return AccountResponse.from(userRepository.save(user));
    }

    /**
     * Changes the caller's password, after they prove they know the current one.
     *
     * The order of checks is deliberate:
     *   1. Refuse anything that is not a password field, before touching the hash.
     *   2. Verify the current password against the stored BCrypt hash — with the
     *      encoder's own matches(), which is the comparison login itself relies on.
     *      Wrong: 401 (IncorrectCurrentPasswordException), and nothing is written.
     *   3. Refuse a "new" password identical to the current one. It would succeed
     *      and change nothing, and someone who believes they have just rotated a
     *      password they suspect was seen deserves to be told that they have not.
     *   4. Hash and store. The plaintext is never stored, logged or returned.
     *
     * Tokens already issued stay valid until they expire — JWTs are stateless, and
     * this does not change that. What it does guarantee is that the next sign-in
     * needs the new password, and that nobody changes it without the old one.
     */
    @Transactional
    public void changeMyPassword(UUID userId, ChangePasswordPayload payload) {
        rejectUnrecognised(payload.unrecognised(), "currentPassword", "newPassword");

        User user = load(userId);

        if (!passwordEncoder.matches(payload.currentPassword(), user.getPasswordHash())) {
            throw new IncorrectCurrentPasswordException("Your current password is incorrect.");
        }
        if (passwordEncoder.matches(payload.newPassword(), user.getPasswordHash())) {
            throw new InvalidFieldException("newPassword", "Choose a new password that is different from your current one.");
        }

        user.setPasswordHash(passwordEncoder.encode(payload.newPassword()));
        userRepository.save(user);
    }

    /**
     * Refuses a request that carried any property besides the ones this endpoint
     * accepts. The payload records capture those properties rather than letting the
     * JSON mapper drop them, precisely so this can refuse them.
     *
     * Reports one field — the most important present — as a 400 on that field, the
     * same shape Bean Validation uses, so the form can show it. A value of null
     * still counts: {@code "phone": null} is an attempt to clear the phone, not an
     * absence of one.
     *
     * @param allowed the fields this endpoint does accept, named in the message when
     *                something else was sent
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

        // A password sent to the details endpoint (or a name sent to the password
        // endpoint) is a client aiming at the wrong form, not an attack — say where
        // it belongs. Sorted so the same request always reports the same field.
        String field = new TreeSet<>(unrecognised.keySet()).first();
        throw new InvalidFieldException(
            field,
            "'" + field + "' cannot be changed here. This request accepts only: " + String.join(", ", allowed) + "."
        );
    }

    /**
     * The email to store for this account, by its role (Business Rule 18).
     *
     * A donor's goes through the same shared function registration uses, so the two
     * cannot disagree about what a valid address is or what "blank" means. Staff and
     * administrators must have one — V13's users_email_required_for_staff constraint
     * would refuse the write anyway, but a 400 on the field says it far better than a
     * database error would.
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
