package com.bloodlink.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for POST /api/auth/login.
 *
 * Phone is the credential for every role, matching the single identifier field on
 * Login.jsx; V2 made users.phone unique so it resolves to exactly one account.
 */
public record LoginRequest(

    @NotBlank(message = "Phone number is required")
    String phone,

    @NotBlank(message = "Password is required")
    String password
) {}
