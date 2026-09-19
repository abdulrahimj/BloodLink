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
 * Both fields are always sent — this is the Profile Settings form's "Save changes",
 * which submits both — rather than PATCH's usual "send only what changed". A blank
 * name is never valid. A blank email is valid only for a DONOR, who may have none
 * (Business Rule 18); for every other role AccountService refuses it, since staff
 * and administrator accounts must keep one (Business Rule 14).
 */
public record UpdateAccountPayload(

    @NotBlank(message = "Full name is required")
    @Size(max = 255)
    String fullName,

    /** Required for staff and administrators, optional for donors — which of the
     *  two depends on the account's role, so AccountService decides, not an
     *  annotation here. @Email accepts blank, so it only ever judges a real value. */
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
