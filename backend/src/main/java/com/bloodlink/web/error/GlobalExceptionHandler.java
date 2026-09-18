package com.bloodlink.web.error;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Translates exceptions into the single ApiError shape.
 *
 * Without this, a duplicate phone number or a malformed date would reach the SPA as
 * an opaque 500 with an HTML body, and the forms would have nothing useful to show.
 * Each handler below deliberately returns a message that can be displayed verbatim.
 *
 * Every handler in this class is exercised by at least one module — this file grew
 * alongside appeals, admin and donations rather than being revisited for them, and
 * a hardening pass is exactly when a class like this earns a second look. The four
 * newest handlers (missing parameter, type mismatch, method-parameter validation,
 * and the catch-all at the bottom) close gaps that existed from the start: a
 * malformed UUID in a path or query string, or a required query parameter left out
 * entirely, used to reach the client as Spring's own default error body — a
 * different shape, on an endpoint sitting right next to ones already returning
 * ApiError. "The same JSON shape across every endpoint" was not true until now.
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
     * The same kind of failure as {@link #handleValidation}, but for a constraint
     * placed directly on a {@code @RequestParam} rather than a field inside a
     * {@code @RequestBody} DTO — for example {@code GET /api/donors/lookup} with a
     * blank {@code phone}, or {@code GET /api/appeals/reach} with an unrecognised
     * {@code bloodType}. Spring MVC has validated an annotated {@code @RequestParam}
     * or {@code @PathVariable} this way natively since 6.1, with no class-level
     * marker needed — {@code spring-boot-starter-validation} already supplies the
     * {@code Validator} bean the mechanism looks for.
     *
     * Built to the same field-level shape as {@link #handleValidation} — a request
     * that fails validation should look the same in the response whether the
     * failing value lived in the JSON body or the query string, and a client
     * should never need to know which of the two triggered it.
     *
     * Deliberately not paired with {@code @Validated} on any controller: that
     * annotation activates Spring's older, general-purpose AOP-based method
     * validation instead of this one — see {@link #handleConstraintViolation},
     * added after exactly that mistake sent the same two routes' failures to the
     * catch-all as an opaque 500.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            // getParameterName() reads debug info in the compiled bytecode (javac's
            // default -g, not the separate -parameters flag), which this project's
            // build already includes — so this resolves to "phone" or "bloodType"
            // rather than nothing. A null name (a build compiled without either)
            // still degrades to a single "value" key rather than losing the message.
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
     * A defensive handler for a mistake this project already made once: a
     * controller class carrying {@code @Validated} routes its constrained
     * {@code @RequestParam}/{@code @PathVariable} values through Spring's older
     * AOP-based method validation (a proxy intercepting the whole method call)
     * rather than through Spring MVC's own native handling — the two are separate
     * mechanisms, and {@code @Validated} activates the wrong one for a REST
     * controller. That older path throws this exception, not
     * {@link HandlerMethodValidationException}, and without this handler it fell
     * through to {@link #handleUnexpected} as an opaque 500 on exactly the two
     * routes ({@code GET /api/appeals/reach}, {@code GET /api/donors/lookup})
     * this project actually has annotated {@code @RequestParam}s on.
     *
     * No controller in this codebase carries {@code @Validated} any more — see
     * AppealController.reach and DonorLookupController.lookup's own comments —
     * so this should never fire today. It stays here so that if a future change
     * reintroduces {@code @Validated} on a controller, the failure is a correct
     * 400 with a field-level message rather than a silent regression to a 500
     * that only turns up in testing, the way this one did.
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

    /** A ConstraintViolationException's property path for a method parameter reads
     *  as "methodName.paramName" (e.g. "reach.bloodType"); this takes just the
     *  last segment, so the field key matches what handleParameterValidation and
     *  handleValidation both already use for the same parameter. */
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
     * A cross-field rule the service had to check itself, because no annotation on a
     * single field could express it — {@code hospitalId} being required for one role
     * and forbidden for another (Business Rule 14).
     *
     * Deliberately built to the same field-keyed shape as {@link #handleValidation},
     * so the form treats it identically to a Bean Validation failure: the offending
     * input is highlighted and the message appears beneath it. Which field it belongs
     * to travels on the exception rather than being guessed here.
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
     * A phone number that is not in, and cannot be converted to, the format this
     * system stores — raised by {@code PhoneNumber.normalize} from registration,
     * login and the donor lookup alike.
     *
     * Given the same field-level shape as the two handlers above, keyed to "phone",
     * so the Register, Login and Record Donation forms highlight that input and show
     * the message beneath it exactly as they already do for a bean-validation
     * failure. The check runs in the service rather than as a {@code @Pattern} on the
     * DTO precisely because the same rule has to convert the value, not just judge
     * it, and one function has to do both or the two can disagree.
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

    /**
     * A {@code @RequestParam} the client left out of the query string entirely —
     * not present with an empty value (which {@link #handleParameterValidation}
     * would catch via {@code @NotBlank}), but absent altogether, e.g.
     * {@code GET /api/donors/lookup} with no {@code ?phone=} at all.
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
     * A path or query value Spring could not convert to the type the controller
     * method declared — almost always a malformed UUID, e.g.
     * {@code GET /api/inventory/not-a-real-id} or
     * {@code GET /api/requests?bloodBankId=not-a-real-id}. Without this handler
     * these reached the client as Spring Boot's own default error body rather than
     * ApiError — a real, pre-existing gap this hardening pass closes, since every
     * UUID path or query parameter in this API (bank, request, notification and
     * user ids) was equally exposed to it.
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

    /** Phone or email already registered, an admin's own account can't be
     *  suspended, or a donor is still inside their recovery window. */
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

    /**
     * A wrong current password on a password change (Business Rule 16).
     *
     * 401, as a failed proof of identity, but — unlike the login failure above —
     * specific, and keyed to the "currentPassword" field. The field is load-bearing:
     * it is what lets the frontend tell this 401 apart from an expired session and
     * keep the user signed in. See IncorrectCurrentPasswordException.
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
     * Authenticated, but the role — or, for ActingBankResolver's callers, the
     * facility assignment — is not permitted here. 403 rather than 401:
     * re-authenticating would not help.
     *
     * Covers method-level checks only (@PreAuthorize and friends, and the service-
     * layer throws in ActingBankResolver/RequestService/InventoryService), which
     * reach the dispatcher. Denials from the URL rules in SecurityConfig are raised
     * earlier, in the security filter chain, and are handled by
     * RestAccessDeniedHandler — the two deliberately return the same status and
     * message shape.
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
     * The backstop. Anything not named above — a programming error, a database
     * outage — would otherwise reach the client as Spring Boot's default
     * Whitelabel body (HTML, or a bare JSON object with no `message` field), which
     * breaks the frontend's "every error is an ApiError" assumption at exactly the
     * moment things have already gone wrong.
     *
     * The message given out is deliberately generic: unlike every handler above,
     * an unanticipated exception's own message was never written with a caller in
     * mind, and could as easily be a table name or a null-pointer trace as
     * something safe to show. The real detail goes to the server log instead,
     * keyed by nothing more than the stack trace itself — there is no request id
     * threaded through this application to correlate by, so the timestamp in the
     * logged line and in the response body is what ties the two together.
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
     * A request with an HTTP method the address does not support — DELETE on
     * {@code /api/admin/audit-log}, which only answers GET (Business Rule 17).
     *
     * A 405 with the {@code Allow} header naming what the address does accept, as
     * HTTP specifies. Before this handler the catch-all above took it: every wrong
     * method anywhere in the API came back as "Something went wrong on our end" with
     * an ERROR-level stack trace in the log — telling the caller the server was
     * broken when it was working exactly as designed, and making "this endpoint does
     * not exist" impossible to tell apart from "this endpoint crashed".
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
     * A request to an address no endpoint answers — e.g.
     * {@code DELETE /api/admin/audit-log/{id}}, for which there has never been a
     * route. A 404, for the same reason as the 405 above: until this handler it was
     * reported as a server failure.
     *
     * Spring MVC raises NoResourceFoundException for this (the request falls through
     * every controller to the static-resource handler, which has nothing either);
     * NoHandlerFoundException is covered too, for the configuration that raises that
     * one instead.
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
