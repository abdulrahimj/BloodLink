package com.bloodlink.passwordreset.dto;

import jakarta.validation.constraints.Size;

/**
 * "Mark done" (Business Rule 33): an optional note of at most 200 characters; blank is stored as none.
 */
public record ClosePasswordResetRequestPayload(

    @Size(max = 200, message = "Keep the note to 200 characters or fewer")
    String note
) {}
