package com.bloodlink.account;

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
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * The signed-in user's own account — Business Rule 16. Behind every role's Profile
 * Settings screen.
 *
 * "/me" rather than "/{id}": the account acted on is always the caller's, read from
 * their token, never from the URL. There is nothing to scope or check because there
 * is no way to name anybody else's account here at all. Open to every signed-in
 * role (SecurityConfig), since every role has a name, an email and a password.
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
     * Changes the caller's password, given their current one.
     *
     * 204 No Content on success: there is nothing new to return, and in particular
     * never the password in any form. A wrong current password is a 401 keyed to the
     * currentPassword field — see IncorrectCurrentPasswordException for why the field
     * matters as much as the status.
     */
    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(
        @Valid @RequestBody ChangePasswordPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        accountService.changeMyPassword(principal.getId(), payload);
        return ResponseEntity.noContent().build();
    }
}
