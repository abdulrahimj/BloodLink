package com.bloodlink.account.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/users/me/password (Business Rule 16): the current password is required so an open session can't lock out
 * the owner. newPassword follows registration's 8–72 rule; currentPassword is capped at 100 to refuse oversized input.
 */
public record ChangePasswordPayload(

    @NotBlank(message = "Enter your current password")
    @Size(max = 100, message = "Current password must be 100 characters or fewer")
    String currentPassword,

    @NotBlank(message = "Enter a new password")
    @Size(min = 8, max = 72, message = "New password must be between 8 and 72 characters")
    String newPassword,

    /** As on UpdateAccountPayload: any other property is captured so it can be refused rather than quietly ignored. */
    @JsonAnySetter
    Map<String, Object> unrecognised
) {}
