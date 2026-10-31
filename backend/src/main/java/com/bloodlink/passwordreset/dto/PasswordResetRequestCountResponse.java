package com.bloodlink.passwordreset.dto;

/** How many password reset requests are waiting, for the Admin Console's badge and banner. */
public record PasswordResetRequestCountResponse(long waiting) {
}
