package com.bloodlink.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.admin.dto.AdminUserResponse;
import com.bloodlink.admin.dto.CreateStaffAccountPayload;
import com.bloodlink.admin.dto.CreatedAccountResponse;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
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
 * roles, creating staff and administrator accounts, and activating or suspending one.
 *
 * Enforcement of what a suspension actually means — refused at the next login,
 * and refused immediately for any request made with a token already issued — is
 * not here. It lives in AuthService (login) and JwtAuthenticationFilter (every
 * other request), because those are the places that decide whether a caller gets
 * in at all; this class only flips the flag they both read.
 */
@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final BloodBankRepository bloodBankRepository;
    private final HospitalRepository hospitalRepository;
    private final PasswordEncoder passwordEncoder;

    AdminUserService(
        UserRepository userRepository,
        BloodBankRepository bloodBankRepository,
        HospitalRepository hospitalRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.bloodBankRepository = bloodBankRepository;
        this.hospitalRepository = hospitalRepository;
        this.passwordEncoder = passwordEncoder;
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
     * worse than a failed request.
     */
    @Transactional
    public CreatedAccountResponse createStaffAccount(CreateStaffAccountPayload payload) {
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
        assignFacility(user, payload);

        // Generated here and never held anywhere else: the User row gets the BCrypt
        // hash (salted and deliberately slow, so the stored value is not reversible),
        // and the plaintext leaves this method only inside the response below.
        String temporaryPassword = TemporaryPasswordGenerator.generate();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));

        return new CreatedAccountResponse(
            AdminUserResponse.from(userRepository.save(user)),
            temporaryPassword
        );
    }

    /**
     * Attaches the facility the chosen role requires, and refuses the combinations
     * that cannot be right.
     *
     * Each branch rejects both mistakes it can make — the missing link and the one
     * that does not belong — rather than only the missing one. Silently ignoring a
     * bloodBankId sent for an administrator would accept a request whose author
     * clearly believed something untrue about what they were creating.
     *
     * A facility id that matches no row is a 404 rather than a field error: the
     * request is well-formed and the console populated that dropdown from this same
     * system's own reference list, so the honest answer is that the thing it named
     * does not exist.
     */
    private void assignFacility(User user, CreateStaffAccountPayload payload) {
        switch (payload.role()) {
            case HOSPITAL_STAFF -> {
                if (payload.hospitalId() == null) {
                    throw new InvalidFieldException("hospitalId", "Choose the hospital this staff member works at.");
                }
                if (payload.bloodBankId() != null) {
                    throw new InvalidFieldException(
                        "bloodBankId", "A hospital staff account cannot also be linked to a blood bank."
                    );
                }
                Hospital hospital = hospitalRepository.findById(payload.hospitalId())
                    .orElseThrow(() -> new NotFoundException("No hospital found for this id."));
                user.setHospital(hospital);
            }
            case BLOOD_BANK_STAFF -> {
                if (payload.bloodBankId() == null) {
                    throw new InvalidFieldException("bloodBankId", "Choose the blood bank this staff member works at.");
                }
                if (payload.hospitalId() != null) {
                    throw new InvalidFieldException(
                        "hospitalId", "A blood bank staff account cannot also be linked to a hospital."
                    );
                }
                BloodBank bank = bloodBankRepository.findById(payload.bloodBankId())
                    .orElseThrow(() -> new NotFoundException("No blood bank found for this id."));
                user.setBloodBank(bank);
            }
            case ADMIN -> {
                // An administrator's authority is citywide (SecurityConfig gates
                // /api/admin/** on the role alone, with no facility scoping), so a
                // facility here is not a harmless extra — it is a misunderstanding of
                // what is being created, and the field would be ignored forever after.
                if (payload.bloodBankId() != null || payload.hospitalId() != null) {
                    throw new InvalidFieldException(
                        "role", "An administrator account is not attached to a hospital or blood bank."
                    );
                }
            }
            // DONOR was refused by the caller before this method was reached.
            default -> throw new InvalidFieldException("role", "This role cannot be created here.");
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
        user.setStatus(newStatus);

        return AdminUserResponse.from(userRepository.save(user));
    }
}
