package com.bloodlink.auth.dto;

import java.util.UUID;

import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;

/**
 * Returned by both register and login, so the frontend stores the token and routes on role the same way.
 * Includes a user summary to spare a "who am I?" call; never a password hash.
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
        /** Null except for BLOOD_BANK_STAFF with an assigned bank;
         * lets the Blood Bank portal open on the right bank straight away. */
        UUID bloodBankId,
        /** The same for HOSPITAL_STAFF, so the Hospital Portal opens on that hospital's own requests. */
        UUID hospitalId
    ) {
        static UserSummary from(User user) {
            return new UserSummary(
                user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getRole(),
                user.getBloodBank() == null ? null : user.getBloodBank().getId(),
                user.getHospital() == null ? null : user.getHospital().getId()
            );
        }
    }

    /** Standard bearer-token envelope; expiresIn is in seconds. */
    public static AuthResponse of(String token, long expiresInSeconds, User user) {
        return new AuthResponse(token, "Bearer", expiresInSeconds, UserSummary.from(user));
    }
}
