package com.bloodlink.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.admin.dto.AdminUserResponse;
import com.bloodlink.admin.dto.CreateStaffAccountPayload;
import com.bloodlink.admin.dto.CreatedAccountResponse;
import com.bloodlink.admin.dto.EditAccountPayload;
import com.bloodlink.audit.AuditLogService;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Account management for the Admin Console: listing every user across all four
 * roles, creating staff and administrator accounts, editing another account's phone
 * and facility, and activating or suspending one.
 *
 * Every one of those changes is recorded in the audit log (Business Rule 17), inside
 * the same transaction as the change itself — so the log can never claim something
 * happened that was rolled back, or miss something that was not. Reads are not
 * recorded.
 *
 * Enforcement of what a suspension actually means — refused at the next login,
 * and refused immediately for any request made with a token already issued — is
 * not here. It lives in AuthService (login) and JwtAuthenticationFilter (every
 * other request), because those are the places that decide whether a caller gets
 * in at all; this class only flips the flag they both read.
 */
@Service
public class AdminUserService {

    private static final Map<Role, String> ROLE_LABEL = Map.of(
        Role.DONOR, "Donor",
        Role.HOSPITAL_STAFF, "Hospital Staff",
        Role.BLOOD_BANK_STAFF, "Blood Bank Staff",
        Role.ADMIN, "Administrator"
    );

    private final UserRepository userRepository;
    private final BloodBankRepository bloodBankRepository;
    private final HospitalRepository hospitalRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    AdminUserService(
        UserRepository userRepository,
        BloodBankRepository bloodBankRepository,
        HospitalRepository hospitalRepository,
        PasswordEncoder passwordEncoder,
        AuditLogService auditLogService
    ) {
        this.userRepository = userRepository;
        this.bloodBankRepository = bloodBankRepository;
        this.hospitalRepository = hospitalRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /** Every account, newest-facility-first ordering aside — see the repository
     *  query for why it is sorted by name rather than role or creation date. */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers() {
        return userRepository.findAllWithFacilitiesOrderByFullNameAsc().stream()
            .map(AdminUserResponse::from)
            .toList();
    }

    /**
     * Creates a HOSPITAL_STAFF, BLOOD_BANK_STAFF or ADMIN account — Business Rule 14.
     *
     * This is the path CLAUDE.md always described as "created by an administrator":
     * until now those rows had to be inserted into the database by hand, which meant
     * hashing a password by hand too (and getting it wrong once, silently, in a way
     * that only showed up as a login that would not work).
     *
     * Four things happen here that the caller cannot be trusted to have done, in this
     * order, so that the cheapest and most specific failures come first:
     *   1. DONOR is refused. Donors self-register (Business Rule 8), and that path also
     *      creates the linked Donor row — blood group, date of birth, address — which
     *      this endpoint has no fields for. A DONOR User with no Donor row would break
     *      every donor screen, so it is refused rather than half-created.
     *   2. The facility link is checked against the role: required for the two staff
     *      roles, forbidden for an administrator. An administrator belongs to no
     *      facility, and a staff account with no facility can see nothing (see the
     *      comments on User.bloodBank and User.hospital), so both mistakes create an
     *      account that does not work.
     *   3. The phone is normalised through Business Rule 13's shared function, so an
     *      account created here is stored in exactly the format login compares
     *      against — the one thing hand-written inserts kept getting wrong.
     *   4. A password is generated and hashed. The administrator never chooses it and
     *      the plaintext is returned exactly once, in this method's response.
     *
     * Wholly transactional: an account that exists but has no usable password, or a
     * generated password returned for a row that was never written, would both be
     * worse than a failed request. The ACCOUNT_CREATED audit entry is written in the
     * same transaction, naming {@code principal} as the administrator who did it —
     * and never mentioning the generated password.
     */
    @Transactional
    public CreatedAccountResponse createStaffAccount(CreateStaffAccountPayload payload, AuthenticatedUser principal) {
        Role role = payload.role();
        if (role == Role.DONOR) {
            throw new InvalidFieldException(
                "role",
                "Donor accounts are created by donors themselves through registration, not here."
            );
        }

        // Normalised before the duplicate check, so a number typed the local way is
        // still recognised as one already registered internationally — the same order
        // AuthService.register uses, for the same reason (Business Rule 13).
        String phone = PhoneNumber.normalize(payload.phone());
        String email = payload.email().trim().toLowerCase();

        // Checked up front for an actionable 409 naming the field; the unique
        // constraints on both columns remain the real guarantee under concurrency.
        if (userRepository.existsByPhone(phone)) {
            throw new ConflictException("An account already exists for this phone number.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account already exists for this email address.");
        }

        User user = new User();
        user.setFullName(payload.fullName().trim());
        user.setPhone(phone);
        user.setEmail(email);
        user.setRole(role);
        // Explicit rather than relying on the entity default, because "the account
        // works the moment the password is handed over" is part of this rule.
        user.setStatus(AccountStatus.ACTIVE);
        assignFacility(user, role, payload.bloodBankId(), payload.hospitalId());

        // Generated here and never held anywhere else: the User row gets the BCrypt
        // hash (salted and deliberately slow, so the stored value is not reversible),
        // and the plaintext leaves this method only inside the response below.
        String temporaryPassword = TemporaryPasswordGenerator.generate();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));

        User saved = userRepository.save(user);
        auditLogService.record(principal.getId(), AuditAction.ACCOUNT_CREATED, saved,
            "Created " + ROLE_LABEL.get(role) + " account for " + saved.getFullName()
                + ", phone " + saved.getPhone()
                + facilityPhrase(saved)
                + ".");

        return new CreatedAccountResponse(AdminUserResponse.from(saved), temporaryPassword);
    }

    /**
     * Changes another account's phone and facility — Business Rule 17.
     *
     * The rules, in the order they are checked:
     *   1. Not your own account. Business Rule 16 already keeps every account holder,
     *      administrators included, from changing their own phone; letting an
     *      administrator do it here instead would be the same thing through a side
     *      door. Another administrator can make the change, and the log shows who.
     *   2. Nothing but phone and facility. Role in particular is refused with its
     *      own message — changing it is deliberately out of scope (see
     *      EditAccountPayload) — and anything else (name, email, status) generically.
     *   3. The phone is normalised (Business Rule 13) and must not belong to anyone
     *      else. Keeping the account's own number is not a clash with itself.
     *   4. The facility follows the same required/forbidden rule as account creation
     *      (Business Rule 14), judged against the role the account already has:
     *      staff need theirs, donors and administrators have none.
     *
     * If none of that changes anything — the form was saved as it was loaded — the
     * account is returned untouched and nothing is recorded: the log is a record of
     * changes, and a save that changed nothing is not one.
     *
     * A moved staff member's server-side access follows the new facility on their
     * very next request (ActingBankResolver and RequestService read it from the
     * database each time). What their browser shows may lag until they sign in again,
     * since the portal remembers the facility it was given at login.
     */
    @Transactional
    public AdminUserResponse editAccount(UUID targetUserId, EditAccountPayload payload, AuthenticatedUser principal) {
        if (principal.getId().equals(targetUserId)) {
            throw new ConflictException(
                "You cannot change your own phone number or facility — another administrator has to do this."
            );
        }
        rejectUnrecognised(payload.unrecognised());

        User user = userRepository.findById(targetUserId)
            .orElseThrow(() -> new NotFoundException("No user found for this id."));

        String oldPhone = user.getPhone();
        String oldFacility = facilityName(user);

        String phone = PhoneNumber.normalize(payload.phone());
        if (!phone.equals(oldPhone) && userRepository.existsByPhone(phone)) {
            throw new ConflictException("An account already exists for this phone number.");
        }

        user.setPhone(phone);
        assignFacility(user, user.getRole(), payload.bloodBankId(), payload.hospitalId());

        List<String> changes = new ArrayList<>();
        if (!phone.equals(oldPhone)) {
            changes.add("Phone changed from " + oldPhone + " to " + phone + ".");
        }
        String newFacility = facilityName(user);
        if (!Objects.equals(oldFacility, newFacility)) {
            // Worded for the three shapes a facility change can take. The first and
            // last only arise for accounts inserted by hand before Business Rule 14,
            // which could be left with no facility, or one their role cannot have.
            String kind = user.getRole() == Role.HOSPITAL_STAFF ? "Hospital" : "Blood bank";
            if (oldFacility == null) {
                changes.add(kind + " set to " + newFacility + ".");
            } else if (newFacility == null) {
                changes.add("Facility " + oldFacility + " removed.");
            } else {
                changes.add(kind + " changed from " + oldFacility + " to " + newFacility + ".");
            }
        }

        User saved = userRepository.save(user);
        if (!changes.isEmpty()) {
            auditLogService.record(principal.getId(), AuditAction.ACCOUNT_EDITED, saved, String.join(" ", changes));
        }
        return AdminUserResponse.from(saved);
    }

    /**
     * Refuses anything in an edit request besides phone and facility — role with its
     * own explanation, since it is the field an administrator is most likely to
     * expect to be here. Reports one field, the first alphabetically after role, so
     * the same request always gets the same answer.
     */
    private static void rejectUnrecognised(Map<String, Object> unrecognised) {
        if (unrecognised == null || unrecognised.isEmpty()) {
            return;
        }
        if (unrecognised.containsKey("role")) {
            throw new InvalidFieldException("role",
                "An account's role cannot be changed here. That needs a separate decision, because a "
                    + "donor account carries a linked donor record a role change would affect.");
        }
        String field = new TreeSet<>(unrecognised.keySet()).first();
        throw new InvalidFieldException(field,
            "'" + field + "' cannot be changed here. This request accepts only: phone, bloodBankId, hospitalId.");
    }

    /** The account's facility name, or null for a role that has none. */
    private static String facilityName(User user) {
        if (user.getHospital() != null) return user.getHospital().getName();
        if (user.getBloodBank() != null) return user.getBloodBank().getName();
        return null;
    }

    /** ", at Holy Spirit Hospital" — or nothing, for an account with no facility. */
    private static String facilityPhrase(User user) {
        String name = facilityName(user);
        return name == null ? "" : ", at " + name;
    }

    /**
     * Attaches the facility a role requires, and refuses the combinations that
     * cannot be right — Business Rule 14's rule, shared by account creation and
     * editing (Business Rule 17) so the two cannot drift apart. On an edit, the role
     * is the one the account already has.
     *
     * Each branch rejects both mistakes it can make — the missing link and the one
     * that does not belong — rather than only the missing one. Silently ignoring a
     * bloodBankId sent for an administrator would accept a request whose author
     * clearly believed something untrue about the account.
     *
     * The error names the offending field (bloodBankId or hospitalId), so a form can
     * mark it. A facility id that matches no row is a 404 rather than a field error:
     * the request is well-formed and the console populated that dropdown from this
     * same system's own reference list, so the honest answer is that the thing it
     * named does not exist.
     */
    private void assignFacility(User user, Role role, UUID bloodBankId, UUID hospitalId) {
        switch (role) {
            case HOSPITAL_STAFF -> {
                if (hospitalId == null) {
                    throw new InvalidFieldException("hospitalId", "Choose the hospital this staff member works at.");
                }
                if (bloodBankId != null) {
                    throw new InvalidFieldException(
                        "bloodBankId", "A hospital staff account cannot also be linked to a blood bank."
                    );
                }
                Hospital hospital = hospitalRepository.findById(hospitalId)
                    .orElseThrow(() -> new NotFoundException("No hospital found for this id."));
                user.setHospital(hospital);
                // Cleared as well as set, so an edit always leaves exactly one
                // facility — even on an account inserted by hand with the wrong one.
                user.setBloodBank(null);
            }
            case BLOOD_BANK_STAFF -> {
                if (bloodBankId == null) {
                    throw new InvalidFieldException("bloodBankId", "Choose the blood bank this staff member works at.");
                }
                if (hospitalId != null) {
                    throw new InvalidFieldException(
                        "hospitalId", "A blood bank staff account cannot also be linked to a hospital."
                    );
                }
                BloodBank bank = bloodBankRepository.findById(bloodBankId)
                    .orElseThrow(() -> new NotFoundException("No blood bank found for this id."));
                user.setBloodBank(bank);
                user.setHospital(null);
            }
            // An administrator's authority is citywide (SecurityConfig gates
            // /api/admin/** on the role alone, with no facility scoping), and a donor
            // gives blood at any bank — so for either, a facility is not a harmless
            // extra but a misunderstanding of the account, and would mean nothing.
            case ADMIN, DONOR -> {
                String account = role == Role.ADMIN ? "An administrator account" : "A donor account";
                if (bloodBankId != null) {
                    throw new InvalidFieldException("bloodBankId", account + " is not attached to a blood bank.");
                }
                if (hospitalId != null) {
                    throw new InvalidFieldException("hospitalId", account + " is not attached to a hospital.");
                }
                user.setBloodBank(null);
                user.setHospital(null);
            }
        }
    }

    /**
     * Sets one account's status.
     *
     * An administrator may not suspend their own account. A self-suspend would sign
     * that operator out immediately and, if theirs was the only active admin account,
     * leave nobody able to sign in and undo it through this API at all — recovering
     * from that means editing the database by hand, which is exactly what
     * {@link #createStaffAccount} now exists to stop being necessary (creating a
     * second administrator is a way out of this, but only for someone who thought to
     * do it first). Suspending a *different* administrator carries no such risk — the
     * caller acting on it remains active and can reverse it — so only the
     * self-suspend case is refused, not "the last active admin" in general.
     *
     * An actual change of status is recorded in the audit log as ACCOUNT_SUSPENDED
     * or ACCOUNT_REACTIVATED (Business Rule 17). Setting an account to the status it
     * already has stays the harmless no-op it always was, and records nothing: the
     * log is of things that happened, and nothing did.
     */
    @Transactional
    public AdminUserResponse updateStatus(UUID targetUserId, AccountStatus newStatus, AuthenticatedUser principal) {
        if (newStatus == AccountStatus.SUSPENDED && principal.getId().equals(targetUserId)) {
            throw new ConflictException(
                "You cannot suspend your own account — sign in as a different administrator to do this."
            );
        }

        User user = userRepository.findById(targetUserId)
            .orElseThrow(() -> new NotFoundException("No user found for this id."));
        AccountStatus previous = user.getStatus();
        user.setStatus(newStatus);
        User saved = userRepository.save(user);

        if (previous != newStatus) {
            boolean suspended = newStatus == AccountStatus.SUSPENDED;
            auditLogService.record(
                principal.getId(),
                suspended ? AuditAction.ACCOUNT_SUSPENDED : AuditAction.ACCOUNT_REACTIVATED,
                saved,
                suspended ? "Account suspended." : "Account reactivated."
            );
        }
        return AdminUserResponse.from(saved);
    }
}
