package com.bloodlink.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.admin.dto.AdminUserResponse;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Account management for the Admin Console: listing every user across all four
 * roles, and activating or suspending one.
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

    AdminUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
     * Sets one account's status.
     *
     * An administrator may not suspend their own account. There is still no
     * admin-managed account-creation flow (CLAUDE.md), so every administrator on
     * this deployment was created by hand against the database; a self-suspend
     * would sign that operator out immediately and, if theirs was the only active
     * admin account, leave nobody able to sign in and undo it through this API at
     * all. Suspending a *different* administrator carries no such risk — the
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
