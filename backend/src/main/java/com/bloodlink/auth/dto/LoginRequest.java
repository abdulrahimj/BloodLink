package com.bloodlink.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for POST /api/auth/login.
 *
 * Phone is the credential for every role, matching the single identifier field on
 * Login.jsx; V2 made users.phone unique so it resolves to exactly one account.
 *
 * Both fields are bounded even though a login can never succeed against an
 * over-long value — phone is capped at the column width (V1), and password at a
 * generous but finite length. Without a bound, an oversized payload would reach
 * BCryptPasswordEncoder, whose comparison cost grows with input size, before
 * failing; rejecting it here with a plain 400 is cheaper and no less correct.
 */
public record LoginRequest(

    @NotBlank(message = "Phone number is required")
    @Size(max = 32, message = "Phone number must be 32 characters or fewer")
    String phone,

    @NotBlank(message = "Password is required")
    @Size(max = 100, message = "Password must be 100 characters or fewer")
    String password
) {}
