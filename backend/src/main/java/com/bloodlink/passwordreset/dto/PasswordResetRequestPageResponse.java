package com.bloodlink.passwordreset.dto;

import java.util.List;

/** One page of password reset requests, in the same envelope as the Audit Log and User Accounts. */
public record PasswordResetRequestPageResponse(
    List<PasswordResetRequestResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
