package com.bloodlink.account.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/users/me/password — the signed-in user changing their own password
 * (Business Rule 16).
 *
 * <h2>Why the current password is required</h2>
 * Being signed in proves someone had the password once, not that the person at the
 * keyboard now is the account holder. A session left open on a shared machine at a
 * facility — the case session.js's "Remember me" choice already worries about —
 * would otherwise let anyone who walked up to it set a new password and lock the
 * real owner out of their own account, permanently. Asking for the current password
 * again is what turns "has this browser tab" back into "knows the password".
 *
 * <h2>Bounds</h2>
 * {@code newPassword} follows registration's rule exactly (8 to 72 characters), so
 * an account cannot be moved to a password registration would have refused. 72 is
 * BCrypt's input limit: anything past it would be silently ignored when hashing.
 * {@code currentPassword} is only capped, at LoginRequest's generous 100, so an
 * oversized value is refused cheaply before it ever reaches BCrypt's comparison.
 */
public record ChangePasswordPayload(

    @NotBlank(message = "Enter your current password")
    @Size(max = 100, message = "Current password must be 100 characters or fewer")
    String currentPassword,

    @NotBlank(message = "Enter a new password")
    @Size(min = 8, max = 72, message = "New password must be between 8 and 72 characters")
    String newPassword,

    /** As on UpdateAccountPayload: anything else in the body, captured so it can be
     *  refused rather than quietly ignored. A password request carrying a phone
     *  number changes neither. */
    @JsonAnySetter
    Map<String, Object> unrecognised
) {}
