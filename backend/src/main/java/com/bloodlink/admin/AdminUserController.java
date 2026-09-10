package com.bloodlink.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.admin.dto.AdminUserResponse;
import com.bloodlink.admin.dto.UpdateUserStatusPayload;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * "Manage User Accounts" — the administrator use case from CLAUDE.md.
 *
 * SecurityConfig restricts all of /api/admin/** to ADMIN, so there is no
 * role-scoping to do here the way RequestController or InventoryController have
 * to: every caller who reaches these methods is already an administrator, and
 * every account in the system is fair game to list or act on.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /** Every account across all four roles, for the console's user table. */
    @GetMapping
    public List<AdminUserResponse> list() {
        return adminUserService.listUsers();
    }

    /**
     * Activates or suspends one account.
     *
     * PATCH rather than PUT: this changes one field (status) and never touches
     * the rest of the account. A suspension takes effect immediately rather than
     * at the next login — see JwtAuthenticationFilter, which checks status on
     * every request, and AuthService, which refuses it again at sign-in.
     */
    @PatchMapping("/{userId}")
    public AdminUserResponse updateStatus(
        @PathVariable UUID userId,
        @Valid @RequestBody UpdateUserStatusPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return adminUserService.updateStatus(userId, payload.status(), principal);
    }
}
