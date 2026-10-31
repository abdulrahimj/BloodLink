package com.bloodlink.admin;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.admin.dto.AdminUserPageResponse;
import com.bloodlink.admin.dto.AdminUserResponse;
import com.bloodlink.admin.dto.CreateStaffAccountPayload;
import com.bloodlink.admin.dto.CreatedAccountResponse;
import com.bloodlink.admin.dto.EditAccountPayload;
import com.bloodlink.admin.dto.PasswordResetResponse;
import com.bloodlink.admin.dto.UpdateUserStatusPayload;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * "Manage User Accounts": SecurityConfig restricts /api/admin/** to ADMIN,
 * so every caller is an administrator and every account may be listed or acted on.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /**
     * One page of accounts, searched, filtered and sorted in the database; a bad parameter value is a 400.
     * page (0-based), size (default 13, max 100), q, role, status, sort, direction; no @Validated (see AppealController).
     */
    @GetMapping
    public AdminUserPageResponse list(
        @RequestParam(defaultValue = "0")
        @Min(value = 0, message = "page cannot be negative")
        int page,
        @RequestParam(defaultValue = "13")
        @Min(value = 1, message = "size must be at least 1")
        @Max(value = 100, message = "size cannot exceed 100")
        int size,
        @RequestParam(required = false)
        @Size(max = 64, message = "Search text must be 64 characters or fewer")
        String q,
        @RequestParam(required = false)
        @Pattern(regexp = "DONOR|HOSPITAL_STAFF|BLOOD_BANK_STAFF|ADMIN",
                 message = "role must be DONOR, HOSPITAL_STAFF, BLOOD_BANK_STAFF or ADMIN")
        String role,
        @RequestParam(required = false)
        @Pattern(regexp = "ACTIVE|SUSPENDED", message = "status must be ACTIVE or SUSPENDED")
        String status,
        @RequestParam(defaultValue = "name")
        @Pattern(regexp = "name|role|createdAt|status", message = "sort must be name, role, createdAt or status")
        String sort,
        @RequestParam(defaultValue = "asc")
        @Pattern(regexp = "asc|desc", message = "direction must be asc or desc")
        String direction
    ) {
        return adminUserService.searchUsers(page, size, q, role, status, sort, direction);
    }

    /**
     * Creates a staff or administrator account (Business Rule 14): 201 with the generated password, shown only once.
     * DONOR is refused by the service; non-admin tokens never get here (SecurityConfig, 403).
     */
    @PostMapping
    public ResponseEntity<CreatedAccountResponse> create(
        @Valid @RequestBody CreateStaffAccountPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        // The principal is the administrator the audit log records as having
        // created the account (Business Rule 17).
        return ResponseEntity.status(HttpStatus.CREATED).body(adminUserService.createStaffAccount(payload, principal));
    }

    /**
     * Changes another account's phone and facility (Business Rule 17); its own route so the status PATCH stays as is.
     * Role is not changeable here, and neither is the caller's own account.
     */
    @PatchMapping("/{userId}/details")
    public AdminUserResponse editDetails(
        @PathVariable UUID userId,
        @Valid @RequestBody EditAccountPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return adminUserService.editAccount(userId, payload, principal);
    }

    /**
     * Resets another account's password to a new temporary one (Business Rule 31); 200 with it, shown only once.
     * no-store so no browser or proxy keeps the password; the caller's own account is refused (409).
     */
    @PostMapping("/{userId}/password-reset")
    public ResponseEntity<PasswordResetResponse> resetPassword(
        @PathVariable UUID userId,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(adminUserService.resetPassword(userId, principal));
    }

    /**
     * Activates or suspends one account (status only).
     * A suspension takes effect immediately: JwtAuthenticationFilter checks status on every request.
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
