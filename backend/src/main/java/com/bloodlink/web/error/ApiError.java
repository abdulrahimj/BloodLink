package com.bloodlink.web.error;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single error shape every failed request returns, so the frontend has exactly
 * one thing to parse. `message` is always safe to show a user directly.
 *
 * `fieldErrors` is present only for validation failures, mapping field name to
 * message, which lets a form highlight the offending input rather than showing a
 * generic banner.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    String timestamp,
    int status,
    String error,
    String message,
    Map<String, String> fieldErrors
) {

    public static ApiError of(int status, String error, String message) {
        return new ApiError(Instant.now().toString(), status, error, message, null);
    }

    public static ApiError of(int status, String error, String message, Map<String, String> fieldErrors) {
        return new ApiError(Instant.now().toString(), status, error, message, fieldErrors);
    }
}
