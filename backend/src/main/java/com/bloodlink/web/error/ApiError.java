package com.bloodlink.web.error;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single error shape every failed request returns; message is always safe to show.
 * fieldErrors (validation only) maps field to message; code (rare) names a refusal the client must act on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    String timestamp,
    int status,
    String error,
    String code,
    String message,
    Map<String, String> fieldErrors
) {

    public static ApiError of(int status, String error, String message) {
        return new ApiError(Instant.now().toString(), status, error, null, message, null);
    }

    public static ApiError of(int status, String error, String message, Map<String, String> fieldErrors) {
        return new ApiError(Instant.now().toString(), status, error, null, message, fieldErrors);
    }

    /** An error with a stable machine-readable code, such as PASSWORD_CHANGE_REQUIRED (Business Rule 27). */
    public static ApiError withCode(int status, String error, String code, String message) {
        return new ApiError(Instant.now().toString(), status, error, code, message, null);
    }
}
