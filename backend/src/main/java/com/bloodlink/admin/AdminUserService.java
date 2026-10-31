package com.bloodlink.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.admin.dto.AdminUserPageResponse;
import com.bloodlink.admin.dto.AdminUserResponse;
import com.bloodlink.admin.dto.CreateStaffAccountPayload;
import com.bloodlink.admin.dto.CreatedAccountResponse;
import com.bloodlink.admin.dto.EditAccountPayload;
import com.bloodlink.admin.dto.PasswordResetResponse;
import com.bloodlink.audit.AuditLogService;
import com.bloodlink.booking.DonationBookingService;
import com.bloodlink.common.LikePattern;
import com.bloodlink.common.PhoneNumber;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AuditAction;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.security.PasswordChangedEvent;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Admin Console account management: search/filter/page accounts, create staff and admins, edit, reset a password, suspend.
 * Every change is audit-logged in the same transaction (Business Rule 17); suspension is enforced at login and per request.
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
    private final DonationBookingService donationBookingService;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    AdminUserService(
        UserRepository userRepository,
        BloodBankRepository bloodBankRepository,
        HospitalRepository hospitalRepository,
        PasswordEncoder passwordEncoder,
        AuditLogService auditLogService,
        DonationBookingService donationBookingService,
        NotificationService notificationService,
        ApplicationEventPublisher eventPublisher
    ) {
        this.userRepository = userRepository;
        this.bloodBankRepository = bloodBankRepository;
        this.hospitalRepository = hospitalRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.donationBookingService = donationBookingService;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The User Accounts sort choices mapped to entity properties; this map is the whole whitelist, so no request text
     * reaches ORDER BY. Role and status sort by their stored names.
     */
    static final Map<String, String> SORT_PROPERTIES = Map.of(
        "name", "fullName",
        "role", "role",
        "createdAt", "createdAt",
        "status", "status"
    );

    /**
     * One page of accounts searched in the database (UserRepository.searchAccounts); sort is re-checked against the
     * whitelist and id is always the last key so pages are stable. Search text is matched literally (% and _ escaped).
     */
    @Transactional(readOnly = true)
    public AdminUserPageResponse searchUsers(
        int page, int size, String q, String role, String status, String sort, String direction
    ) {
        String property = SORT_PROPERTIES.get(sort);
        if (property == null) {
            throw new InvalidFieldException("sort", "sort must be one of: name, role, createdAt, status.");
        }
        Sort.Direction order = switch (direction) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new InvalidFieldException("direction", "direction must be asc or desc.");
        };

        String text = q == null || q.isBlank() ? null : q.trim();
        String textPattern = text == null ? null : LikePattern.contains(text.toLowerCase());
        String phonePattern = text == null ? null : LikePattern.contains(PhoneNumber.searchFragment(text));

        PageRequest pageable = PageRequest.of(page, size,
            Sort.by(new Sort.Order(order, property), Sort.Order.asc("id")));

        Page<User> result = userRepository.searchAccounts(
            text, textPattern, phonePattern,
            role == null ? null : Role.valueOf(role),
            status == null ? null : AccountStatus.valueOf(status),
            pageable
        );

        return new AdminUserPageResponse(
            result.getContent().stream().map(AdminUserResponse::from).toList(),
            result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()
        );
    }

    /**
     * Creates a HOSPITAL_STAFF, BLOOD_BANK_STAFF or ADMIN account (Business Rule 14): refuses DONOR, checks the facility,
     * normalises the phone and generates a password, all in one transaction with its ACCOUNT_CREATED audit entry.
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

        // Normalised before the duplicate check so a number typed either way is recognised (Business Rule 13).
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

        // Only the BCrypt hash is stored; the plaintext leaves this method only inside the response below.
        String temporaryPassword = TemporaryPasswordGenerator.generate();
        user.replacePassword(passwordEncoder.encode(temporaryPassword), Instant.now());
        // Temporary: the administrator has seen it, so the holder must replace it at first sign-in (Business Rule 27).
        user.setMustChangePassword(true);

        User saved = userRepository.save(user);
        auditLogService.record(principal.getId(), AuditAction.ACCOUNT_CREATED, saved,
            "Created " + ROLE_LABEL.get(role) + " account for " + saved.getFullName()
                + ", phone " + saved.getPhone()
                + facilityPhrase(saved)
                + ".");

        return new CreatedAccountResponse(AdminUserResponse.from(saved), temporaryPassword);
    }

    /**
     * Changes another account's phone and facility (Business Rule 17): not your own, nothing but phone and facility,
     * phone normalised and unique, facility by the account's role. A save that changes nothing is not logged.
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
            // Worded for the three shapes a facility change can take; the first and last only arise for hand-inserted
            // accounts from before Business Rule 14.
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
     * Refuses anything besides phone and facility, with role getting its own explanation;
     * reports the first field alphabetically after role so the answer is always the same.
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
     * Attaches the facility a role requires and refuses a missing or unexpected one (400 naming the field), shared by
     * creation and editing (Business Rules 14, 17). A facility id matching no row is a 404.
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
            // Administrators are citywide and donors give at any bank, so a facility for either is refused.
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
     * Sets one account's status; nobody may suspend their own account or the last active admin (everyone locked out).
     * A real change is logged as ACCOUNT_SUSPENDED or ACCOUNT_REACTIVATED; setting the same status records nothing.
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
        if (newStatus == AccountStatus.SUSPENDED && previous == AccountStatus.ACTIVE && user.getRole() == Role.ADMIN) {
            refuseIfLastActiveAdmin(targetUserId);
        }
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
            // A suspended donor's open visit is cancelled with them (Business Rule 29); reactivating restores nothing.
            if (suspended && saved.getRole() == Role.DONOR) {
                donationBookingService.cancelOnSuspension(saved.getId());
            }
        }
        return AdminUserResponse.from(saved);
    }

    /**
     * Gives another account a new temporary password (Business Rule 31): hash stored, must-change set, status untouched,
     * audited and the holder notified in one transaction. The plaintext leaves only in the response, never a log.
     */
    @Transactional
    public PasswordResetResponse resetPassword(UUID targetUserId, AuthenticatedUser principal) {
        if (principal.getId().equals(targetUserId)) {
            throw new ConflictException(
                "You cannot reset your own password here. Change it in Profile Settings, using your current password."
            );
        }
        User user = userRepository.lockById(targetUserId)
            .orElseThrow(() -> new NotFoundException("No user found for this id."));

        String temporaryPassword = TemporaryPasswordGenerator.generate();
        Instant changedAt = Instant.now();
        // Every token the holder already has stops working, and their open sockets close after commit (Rule 32).
        user.replacePassword(passwordEncoder.encode(temporaryPassword), changedAt);
        user.setMustChangePassword(true);
        User saved = userRepository.save(user);
        eventPublisher.publishEvent(new PasswordChangedEvent(saved.getId(), changedAt));

        auditLogService.record(principal.getId(), AuditAction.ADMIN_PASSWORD_RESET, saved,
            "Password reset to a temporary one; they must choose a new password at their next sign-in."
                + (saved.getStatus() == AccountStatus.SUSPENDED ? " The account stays suspended." : ""));
        notificationService.notifyPasswordResetByAdmin(saved);

        return new PasswordResetResponse(AdminUserResponse.from(saved), temporaryPassword);
    }

    /**
     * Refuses (409) to suspend the only active administrator, which would leave nobody able to administer.
     * The active admins are locked first, so two admins suspending each other at the same moment cannot both win.
     */
    private void refuseIfLastActiveAdmin(UUID targetUserId) {
        boolean anotherActiveAdmin = userRepository.lockByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE).stream()
            .anyMatch(admin -> !admin.getId().equals(targetUserId));
        if (!anotherActiveAdmin) {
            throw new ConflictException(
                "This is the last active administrator account, so it cannot be suspended. "
                    + "Create or reactivate another administrator first."
            );
        }
    }
}
