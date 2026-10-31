package com.bloodlink.passwordreset;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.domain.enums.PasswordResetRequestStatus;
import com.bloodlink.passwordreset.dto.ClosePasswordResetRequestPayload;
import com.bloodlink.passwordreset.dto.PasswordResetRequestCountResponse;
import com.bloodlink.passwordreset.dto.PasswordResetRequestPageResponse;
import com.bloodlink.passwordreset.dto.PasswordResetRequestResponse;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * The Admin Console's Password requests page (Business Rule 33); ADMIN only through SecurityConfig's /api/admin/**.
 * Resetting the password itself is the existing POST /api/admin/users/{id}/password-reset, which closes the request.
 */
@RestController
@RequestMapping("/api/admin/password-reset-requests")
public class AdminPasswordResetRequestController {

    private final PasswordResetRequestService requestService;

    AdminPasswordResetRequestController(PasswordResetRequestService requestService) {
        this.requestService = requestService;
    }

    /** One page of WAITING (the default) or DONE requests, newest first; a bad value is a 400 naming it. */
    @GetMapping
    public PasswordResetRequestPageResponse list(
        @RequestParam(defaultValue = "WAITING")
        @Pattern(regexp = "WAITING|DONE", message = "status must be WAITING or DONE")
        String status,
        @RequestParam(defaultValue = "0")
        @Min(value = 0, message = "page cannot be negative")
        int page,
        @RequestParam(defaultValue = "20")
        @Min(value = 1, message = "size must be at least 1")
        @Max(value = 100, message = "size cannot exceed 100")
        int size
    ) {
        return requestService.list(PasswordResetRequestStatus.valueOf(status), page, size);
    }

    /** How many are waiting, for the menu badge and the User Accounts banner. */
    @GetMapping("/count")
    public PasswordResetRequestCountResponse count() {
        return new PasswordResetRequestCountResponse(requestService.countWaiting());
    }

    /** "Mark done" without a reset: DONE, CLOSED, audited; the body ({ note }) may be left out. */
    @PostMapping("/{requestId}/close")
    public PasswordResetRequestResponse close(
        @PathVariable UUID requestId,
        @Valid @RequestBody(required = false) ClosePasswordResetRequestPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return requestService.close(requestId, payload == null ? null : payload.note(), principal);
    }
}
