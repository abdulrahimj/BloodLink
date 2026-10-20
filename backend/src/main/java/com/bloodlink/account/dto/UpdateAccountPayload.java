package com.bloodlink.account.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/users/me (Business Rule 16): only name and email; any other property is captured and refused whole.
 * Both fields are always sent; a blank email is valid only for a DONOR (Business Rule 18).
 */
public record UpdateAccountPayload(

    @NotBlank(message = "Full name is required")
    @Size(max = 255)
    String fullName,

    /** Required for staff, optional for donors, so AccountService decides by role rather than an annotation.
     * @Email accepts blank, so it only ever judges a real value. */
    @Email(message = "Enter a valid email address")
    @Size(max = 255)
    String email,

    /**
     * Every property besides the fields above, captured so it can be refused out loud; null when there were none.
     * A legitimate client never sets it: Profile Settings only sends fullName and email.
     */
    @JsonAnySetter
    Map<String, Object> unrecognised
) {}
