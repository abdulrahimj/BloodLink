package com.bloodlink.web.error;

import java.util.HashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into the single ApiError shape.
 *
 * Without this, a duplicate phone number or a malformed date would reach the SPA as
 * an opaque 500 with an HTML body, and the forms would have nothing useful to show.
 * Each handler below deliberately returns a message that can be displayed verbatim.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Bean-validation failures on a @Valid request body — a missing full name, a
     * password under 8 characters. Collected per field so the form can mark inputs.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            // putIfAbsent: keep the first message per field, so a field with several
            // failed constraints reports one clear reason rather than the last one.
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );

        return ResponseEntity.badRequest().body(ApiError.of(
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            "Please correct the highlighted fields.",
            fieldErrors
        ));
    }

    /**
     * Unparseable JSON, or a value the enum/date binders reject — for example a
     * gender or blood-type label the backend does not recognise.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            "The submitted details could not be read. Please check the form and try again."
        ));
    }

    /** Thrown by BloodType.fromLabel for an unrecognised blood group. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            ex.getMessage()
        ));
    }

    /**
     * A database constraint refused the write — most often a unique phone or email
     * that slipped past the service's pre-flight check because two signups raced.
     *
     * Caught here so the constraint name and SQL never reach the client, and so the
     * response is a 409 the form can act on rather than an opaque 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(
            HttpStatus.CONFLICT.value(),
            "Conflict",
            "These details conflict with an existing record. Please check and try again."
        ));
    }

    /** The authenticated caller has no such resource — see NotFoundException. */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(
            HttpStatus.NOT_FOUND.value(),
            "Not Found",
            ex.getMessage()
        ));
    }

    /** Phone or email already registered. */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(
            HttpStatus.CONFLICT.value(),
            "Conflict",
            ex.getMessage()
        ));
    }

    /** Failed login. Message is intentionally identical for every failure cause. */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of(
            HttpStatus.UNAUTHORIZED.value(),
            "Unauthorized",
            ex.getMessage()
        ));
    }

    /** A correct password on a suspended account. See AccountSuspendedException for
     *  why this is 403 (not 401) and why it is allowed to be specific. */
    @ExceptionHandler(AccountSuspendedException.class)
    public ResponseEntity<ApiError> handleAccountSuspended(AccountSuspendedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(
            HttpStatus.FORBIDDEN.value(),
            "Forbidden",
            ex.getMessage()
        ));
    }

    /**
     * Authenticated, but the role is not permitted here. 403 rather than 401:
     * re-authenticating would not help.
     *
     * Covers method-level checks only (@PreAuthorize and friends), which reach the
     * dispatcher. Denials from the URL rules in SecurityConfig are raised earlier, in
     * the security filter chain, and are handled by RestAccessDeniedHandler — the two
     * deliberately return the same status and message.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(
            HttpStatus.FORBIDDEN.value(),
            "Forbidden",
            "Your account does not have permission to perform this action."
        ));
    }
}
