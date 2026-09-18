package com.bloodlink.account.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/users/me — the signed-in user editing their own name and email
 * (Business Rule 16).
 *
 * <h2>Only two fields, and everything else is caught, not dropped</h2>
 * A user may change their full name and email here, and nothing else. Phone is the
 * login credential; role, facility and status are assigned by an administrator.
 *
 * Declaring only the two editable fields would not be enough on its own: the JSON
 * mapper silently skips properties a type does not declare, so a request carrying
 * {@code "phone": "..."} would come back 200 with the phone quietly unchanged — the
 * client told "saved" about a change that never happened. {@link #unrecognised}
 * collects every such property instead, and AccountService refuses the whole
 * request with a 400 naming it. Nothing is half-applied: a request that also tried
 * to change the phone changes neither the phone nor the name.
 *
 * Both fields are required — this is the Profile Settings form's "Save changes",
 * which always submits both — rather than PATCH's usual "send only what changed".
 * A blank name or email is never a valid state to leave an account in.
 */
public record UpdateAccountPayload(

    @NotBlank(message = "Full name is required")
    @Size(max = 255)
    String fullName,

    @NotBlank(message = "Email address is required")
    @Email(message = "Enter a valid email address")
    @Size(max = 255)
    String email,

    /**
     * Every property in the request body that is not one of the fields above,
     * captured rather than discarded so it can be refused out loud. Null when there
     * were none. Never set by a legitimate client — the Profile Settings screen only
     * ever sends fullName and email.
     */
    @JsonAnySetter
    Map<String, Object> unrecognised
) {}
