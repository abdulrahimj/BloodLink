package com.bloodlink.account;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.account.dto.AccountResponse;
import com.bloodlink.account.dto.ChangePasswordPayload;
import com.bloodlink.account.dto.UpdateAccountPayload;
import com.bloodlink.auth.dto.AuthResponse;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * The signed-in user's own account (Business Rule 16), behind every role's Profile Settings screen.
 * "/me", not "/{id}": the account is always the caller's, taken from the token, so nobody else's can be named.
 */
@RestController
@RequestMapping("/api/users/me")
public class AccountController {

    private final AccountService accountService;

    AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** The caller's own account: editable details plus the admin-managed facts. */
    @GetMapping
    public AccountResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return accountService.getMyAccount(principal.getId());
    }

    /** Updates the caller's full name and email. Nothing else can be changed here. */
    @PatchMapping
    public AccountResponse updateDetails(
        @Valid @RequestBody UpdateAccountPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return accountService.updateMyDetails(principal.getId(), payload);
    }

    /**
     * Changes the caller's password given their current one (wrong: a 401 on currentPassword). Answers like sign-in, with
     * a fresh token, because every older one stops working (Business Rule 32); never echoes the password.
     */
    @PatchMapping("/password")
    public ResponseEntity<AuthResponse> changePassword(
        @Valid @RequestBody ChangePasswordPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(accountService.changeMyPassword(principal.getId(), payload));
    }
}
