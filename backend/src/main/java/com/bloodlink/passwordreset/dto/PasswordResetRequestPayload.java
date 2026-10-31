package com.bloodlink.passwordreset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of the public "Forgot password?" form (Business Rule 33): the phone number only, checked as sign-in checks it.
 */
public record PasswordResetRequestPayload(

    @NotBlank(message = "Phone number is required")
    @Size(max = 32, message = "Phone number must be 32 characters or fewer")
    String phone
) {}
