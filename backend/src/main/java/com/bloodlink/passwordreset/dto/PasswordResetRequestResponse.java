package com.bloodlink.passwordreset.dto;

import java.time.Instant;
import java.util.UUID;

import com.bloodlink.admin.dto.AdminUserResponse;
import com.bloodlink.domain.PasswordResetRequest;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.PasswordResetRequestStatus;
import com.bloodlink.domain.enums.PasswordResetResolution;

/**
 * One request as the Password requests page shows it: the account (in the Manage box's own shape, so Reset password
 * can open it), when asked, and for a done one who handled it, when, how and any note.
 */
public record PasswordResetRequestResponse(
    UUID id,
    AdminUserResponse account,
    Instant requestedAt,
    PasswordResetRequestStatus status,
    PasswordResetResolution resolution,
    UUID handledById,
    String handledByName,
    Instant handledAt,
    String note
) {

    /** Touches the lazy account, facility and handler, so call it inside a transaction. */
    public static PasswordResetRequestResponse from(PasswordResetRequest request) {
        User handler = request.getHandledBy();
        return new PasswordResetRequestResponse(
            request.getId(),
            AdminUserResponse.from(request.getUser()),
            request.getRequestedAt(),
            request.getStatus(),
            request.getResolution(),
            handler == null ? null : handler.getId(),
            handler == null ? null : handler.getFullName(),
            request.getHandledAt(),
            request.getNote()
        );
    }
}
