package com.bloodlink.web.error;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

/**
 * Translates exceptions into the single ApiError shape, with messages safe to display verbatim,
 * so no failure reaches the SPA as an opaque 500 or Spring's default body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
     * A constraint on a @RequestParam or @PathVariable (validated natively by Spring MVC), reported in the same
     * field-level shape as handleValidation. Never paired with @Validated (see handleConstraintViolation).
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            // The parameter name comes from bytecode debug info, which this build includes;
            // a null name degrades to a "value" key.
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error ->
                fieldErrors.putIfAbsent(name != null ? name : "value", error.getDefaultMessage())
            );
        }

        return ResponseEntity.badRequest().body(ApiError.of(
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            "Please correct the highlighted fields.",
            fieldErrors
        ));
    }

    /**
     * Defensive: @Validated on a controller routes parameter checks through AOP method validation, which throws this
     * and used to surface as a 500. No controller has @Validated now; this keeps any regression a proper 400.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getConstraintViolations().forEach(violation ->
            fieldErrors.putIfAbsent(lastPathNode(violation.getPropertyPath()), violation.getMessage())
        );

        return ResponseEntity.badRequest().body(ApiError.of(
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            "Please correct the highlighted fields.",
            fieldErrors
        ));
    }

    /** Takes the last segment of "methodName.paramName", so the field key matches the other handlers. */
    private String lastPathNode(Path path) {
        String last = "value";
        for (Path.Node node : path) {
            if (node.getName() != null) {
                last = node.getName();
            }
        }
        return last;
    }

    /**
     * A cross-field rule the service checked itself (e.g. a facility required by role, Business Rule 14),
     * in the same field-keyed shape as Bean Validation failures.
     */
    @ExceptionHandler(InvalidFieldException.class)
    public ResponseEntity<ApiError> handleInvalidField(InvalidFieldException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            ex.getMessage(),
            Map.of(ex.getField(), ex.getMessage())
        ));
    }

    /**
     * A phone that cannot be converted to the stored format (PhoneNumber.normalize), keyed to "phone"
     * so the Register, Login and Record Donation forms highlight it.
     */
    @ExceptionHandler(InvalidPhoneNumberException.class)
    public ResponseEntity<ApiError> handleInvalidPhoneNumber(InvalidPhoneNumberException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            ex.getMessage(),
            Map.of("phone", ex.getMessage())
        ));
    }

    /** Over a Business Rule 34 limit: Retry-After in whole seconds, and a message stating the real time left. */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ApiError> handleRateLimited(RateLimitedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
            .body(ApiError.of(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", ex.getMessage()));
    }

    /**
     * A @RequestParam left out of the query string entirely (blank values are caught by handleParameterValidation).
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        String message = ex.getParameterName() + " is required.";
        return ResponseEntity.badRequest().body(ApiError.of(
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            message,
            Map.of(ex.getParameterName(), message)
        ));
    }

    /**
     * A path or query value that could not be converted to its declared type, usually a malformed UUID,
     * returned as ApiError instead of Spring Boot's default body.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "'" + ex.getName() + "' is not a valid value.";
        return ResponseEntity.badRequest().body(ApiError.of(
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            message,
            Map.of(ex.getName(), message)
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
     * A database constraint refused the write (usually a raced duplicate phone or email):
     * a 409 the form can act on, never exposing the constraint or SQL.
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

    /** Phone or email already registered, an admin's own account can't be suspended, or a donor is in recovery;
     *  carries a code when the client must act on it (BLOOD_GROUP_CHANGE_UNCONFIRMED, Business Rule 28). */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        if (ex.getCode() != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.withCode(
                HttpStatus.CONFLICT.value(), "Conflict", ex.getCode(), ex.getMessage()));
        }
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

    /**
     * A wrong current password on a password change (Business Rule 16): 401 keyed to "currentPassword",
     * which lets the frontend tell it from an expired session and keep the user signed in.
     */
    @ExceptionHandler(IncorrectCurrentPasswordException.class)
    public ResponseEntity<ApiError> handleIncorrectCurrentPassword(IncorrectCurrentPasswordException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of(
            HttpStatus.UNAUTHORIZED.value(),
            "Unauthorized",
            ex.getMessage(),
            Map.of("currentPassword", ex.getMessage())
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
     * Authenticated but not permitted (role or facility): 403. Covers method- and service-level checks;
     * URL-rule denials are handled by RestAccessDeniedHandler in the same shape.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(
            HttpStatus.FORBIDDEN.value(),
            "Forbidden",
            "Your account does not have permission to perform this action."
        ));
    }

    /**
     * The backstop for anything unanticipated: a generic ApiError message, since the exception's text may be unsafe,
     * with the detail logged on the server (matched by timestamp).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // (See handleMethodNotAllowed and handleNoSuchEndpoint below: two ordinary
        // client mistakes this backstop used to swallow and misreport as a 500.)
        log.error("Unhandled exception reached GlobalExceptionHandler", ex);
        return ResponseEntity.internalServerError().body(ApiError.of(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Internal Server Error",
            "Something went wrong on our end. Please try again, and contact support if it continues."
        ));
    }

    /**
     * An unsupported HTTP method (e.g. DELETE on /api/admin/audit-log): a 405 with the Allow header,
     * rather than the catch-all's misleading server error.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .headers(ex.getHeaders())
            .body(ApiError.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                "Method Not Allowed",
                "This action is not supported at this address."
            ));
    }

    /**
     * An address no endpoint answers: a 404 rather than a server failure.
     * Covers NoResourceFoundException and NoHandlerFoundException.
     */
    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public ResponseEntity<ApiError> handleNoSuchEndpoint(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(
            HttpStatus.NOT_FOUND.value(),
            "Not Found",
            "There is nothing at this address."
        ));
    }
}
