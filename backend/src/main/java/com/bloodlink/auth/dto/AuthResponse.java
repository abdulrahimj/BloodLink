package com.bloodlink.auth.dto;

import java.util.UUID;

import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;

/**
 * Returned by both register and login, so the frontend can treat the two identically:
 * store the token, then route on the role.
 *
 * The user summary is included to spare the SPA an immediate follow-up "who am I?"
 * call just to render the sidebar. It deliberately carries no password hash.
 */
public record AuthResponse(
    String token,
    String tokenType,
    long expiresIn,
    UserSummary user
) {

    /** The subset of User that is safe to expose to the browser. */
    public record UserSummary(
        UUID id,
        String fullName,
        String email,
        String phone,
        Role role,
        /** Null for every role except BLOOD_BANK_STAFF, and even then only once an
         *  administrator has assigned one (see V5__add_staff_blood_bank_link.sql).
         *  Lets the Blood Bank portal jump straight to the right bank without a
         *  separate "who am I" call. */
        UUID bloodBankId
    ) {
        static UserSummary from(User user) {
            return new UserSummary(
                user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getRole(),
                user.getBloodBank() == null ? null : user.getBloodBank().getId()
            );
        }
    }

    /** Standard bearer-token envelope; expiresIn is in seconds. */
    public static AuthResponse of(String token, long expiresInSeconds, User user) {
        return new AuthResponse(token, "Bearer", expiresInSeconds, UserSummary.from(user));
    }
}
