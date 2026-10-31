package com.bloodlink.passwordreset;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.common.PhoneNumber;
import com.bloodlink.passwordreset.dto.PasswordResetRequestAccepted;
import com.bloodlink.passwordreset.dto.PasswordResetRequestPayload;
import com.bloodlink.passwordreset.dto.SupportContactResponse;

import jakarta.validation.Valid;

/**
 * The public half of "Forgot password?" (Business Rule 33), no sign-in needed (SecurityConfig): the request form and
 * the support phone the Sign in page shows. The form's answer never says whether the number is registered.
 */
@RestController
@RequestMapping("/api/auth")
public class PasswordResetRequestController {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetRequestController.class);

    private final PasswordResetRequestService requestService;
    private final long minResponseMillis;
    private final String supportPhone;

    PasswordResetRequestController(
        PasswordResetRequestService requestService,
        @Value("${bloodlink.password-reset-requests.min-response-ms:500}") long minResponseMillis,
        @Value("${bloodlink.support-phone:${BLOODLINK_SUPPORT_PHONE:}}") String supportPhone
    ) {
        this.requestService = requestService;
        this.minResponseMillis = minResponseMillis;
        this.supportPhone = supportPhone == null || supportPhone.isBlank() ? null : supportPhone.trim();
    }

    /**
     * Always 202 with the same message once the number is well formed (a malformed one is sign-in's 400 on phone).
     * Answered no sooner than the minimum time, so a registered number cannot be told apart by speed.
     */
    @PostMapping("/password-reset-requests")
    public ResponseEntity<PasswordResetRequestAccepted> request(@Valid @RequestBody PasswordResetRequestPayload payload) {
        String phone = PhoneNumber.normalize(payload.phone());
        long started = System.nanoTime();
        try {
            requestService.request(phone);
        } catch (RuntimeException ex) {
            // Only the type: the answer must not change, and a database error could quote the row.
            log.warn("A password reset request could not be recorded ({}).", ex.getClass().getSimpleName());
        }
        waitOutTheRest(started);
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
            .body(PasswordResetRequestAccepted.always());
    }

    /** The support phone for the Sign in page, as configured (display only), or null so the page shows nothing. */
    @GetMapping("/support-contact")
    public SupportContactResponse supportContact() {
        return new SupportContactResponse(supportPhone);
    }

    private void waitOutTheRest(long startedNanos) {
        long remaining = minResponseMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        if (remaining > 0) {
            try {
                Thread.sleep(remaining);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
