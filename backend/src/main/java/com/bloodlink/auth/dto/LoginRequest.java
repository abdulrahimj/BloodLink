package com.bloodlink.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for POST /api/auth/login; phone is the credential for every role.
 * Both fields are bounded so an oversized value is a cheap 400 before reaching BCrypt.
 */
public record LoginRequest(

    @NotBlank(message = "Phone number is required")
    @Size(max = 32, message = "Phone number must be 32 characters or fewer")
    String phone,

    @NotBlank(message = "Password is required")
    @Size(max = 100, message = "Password must be 100 characters or fewer")
    String password
) {}
