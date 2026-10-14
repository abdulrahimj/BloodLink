package com.bloodlink.admin;

import java.util.UUID;

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
import com.bloodlink.admin.dto.UpdateUserStatusPayload;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

    /**
     * One page of accounts for the console's User Accounts table, searched, filtered
     * and sorted in the database.
     *
     * Every parameter is optional and checked against a fixed list before the service
     * sees it; a bad value is a 400 naming the field, never silently corrected:
     * <ul>
     *   <li>{@code page} 0-based (default 0); {@code size} default 13 (the most rows
     *       that fit the console on a 768px-tall screen without scrolling), max 100.</li>
     *   <li>{@code q} matches name, email or phone (any part, either phone format).</li>
     *   <li>{@code role} and {@code status} are exact filters, the enums' own names.</li>
     *   <li>{@code sort} is name, role, createdAt or status; {@code direction} asc or desc.</li>
     * </ul>
     * No @Validated on this class — see AppealController.reach for why that would route
     * these constraints through the wrong exception handler.
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
     * Creates a staff or administrator account — Business Rule 14.
     *
     * 201 with the created account plus its generated password, the same status
     * registration returns for the same reason: a new resource exists that did not
     * before. The password in that body is the only time it is ever readable; see
     * CreatedAccountResponse for why it is returned at all and why it cannot be
     * fetched again.
     *
     * DONOR is refused by the service rather than by this signature. Donors
     * self-register (Business Rule 8), and that flow also writes the linked Donor
     * row this endpoint has no fields for.
     *
     * No role check here: SecurityConfig gates the whole /api/admin/** prefix on
     * ADMIN, so a hospital or bank staff token never reaches this method — it is
     * refused in the filter chain with a 403 (RestAccessDeniedHandler).
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
     * Changes another account's phone and facility — Business Rule 17.
     *
     * A separate sub-resource rather than more fields on PATCH /{userId}: that route
     * sets status and nothing else, and the Suspend/Activate buttons rely on it
     * staying exactly that. Role is not changeable here (see EditAccountPayload),
     * and neither is the caller's own account (see AdminUserService.editAccount).
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
