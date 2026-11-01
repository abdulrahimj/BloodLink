package com.bloodlink.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.bloodlink.ratelimit.AuthRateLimits.RegistrationAttempt;
import com.bloodlink.ratelimit.AuthRateLimits.SignInAttempt;
import com.bloodlink.web.error.RateLimitedException;

/** How the four limits of Business Rule 34 fit together, with small numbers and no Spring context. */
class AuthRateLimitsTest {

    private static final String PHONE = "+23276900005";
    private static final String ADDRESS = "41.223.0.7";

    private final SlidingWindowLimitTest.MovableClock clock = new SlidingWindowLimitTest.MovableClock();
    private final SignInLockAudit audit = mock(SignInLockAudit.class);
    private final AuthRateLimits limits = new AuthRateLimits(
        new RateLimitSettings(3, 15, 15, 5, 15, 15, 2, 60, 2, 60, 1_000), clock, audit);

    private void fail(String phone, String address) {
        try (SignInAttempt attempt = limits.beginSignIn(phone, address)) {
            attempt.failed();
        }
    }

    private void succeed(String phone, String address) {
        try (SignInAttempt attempt = limits.beginSignIn(phone, address)) {
            attempt.succeeded();
        }
    }

    private void assertRefused(String phone, String address) {
        assertThatThrownBy(() -> limits.beginSignIn(phone, address)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void theThirdFailureLocksTheNumberAndWritesOneAuditEntry() {
        fail(PHONE, ADDRESS);
        fail(PHONE, ADDRESS);
        verify(audit, never()).record(anyString(), anyInt());

        fail(PHONE, ADDRESS);

        assertRefused(PHONE, ADDRESS);
        assertRefused(PHONE, "197.215.0.1");
        verify(audit, times(1)).record(PHONE, 3);
    }

    @Test
    void anAttemptThatEndedAnotherWayCountsNothing() {
        // A suspended account or a server fault: the password was never judged wrong.
        for (int i = 0; i < 10; i++) {
            limits.beginSignIn(PHONE, ADDRESS).close();
        }

        succeed(PHONE, ADDRESS);
    }

    @Test
    void aSuccessClearsTheNumberButNotTheAddress() {
        for (int i = 0; i < 4; i++) fail("+2327690000" + i, ADDRESS);

        succeed(PHONE, ADDRESS);
        fail("+23276900099", ADDRESS);

        assertRefused(PHONE, ADDRESS);
    }

    @Test
    void aRefusalFromAnAddressAddsNothingForTheNumber() {
        for (int i = 0; i < 5; i++) fail("+2327690000" + i, ADDRESS);

        assertRefused(PHONE, ADDRESS);
        assertRefused(PHONE, ADDRESS);
        assertRefused(PHONE, ADDRESS);

        // The number is unaffected from anywhere else.
        succeed(PHONE, "197.215.0.1");
    }

    @Test
    void aLockedNumberCostsTheAddressNothing() {
        for (int i = 0; i < 3; i++) fail(PHONE, "197.215.0.1");

        for (int i = 0; i < 10; i++) assertRefused(PHONE, ADDRESS);

        succeed("+23276900001", ADDRESS);
    }

    @Test
    void theAddressWaitLiftsOnTime() {
        for (int i = 0; i < 5; i++) fail("+2327690000" + i, ADDRESS);
        assertRefused(PHONE, ADDRESS);

        clock.advance(Duration.ofMinutes(15));

        succeed(PHONE, ADDRESS);
    }

    @Test
    void anAuditFailureDoesNotUndoTheLockOrChangeTheAnswer() {
        doThrow(new IllegalStateException("database unavailable")).when(audit).record(anyString(), anyInt());

        for (int i = 0; i < 3; i++) fail(PHONE, ADDRESS);

        assertRefused(PHONE, ADDRESS);
    }

    @Test
    void clearingANumberLiftsItsLock() {
        for (int i = 0; i < 3; i++) fail(PHONE, ADDRESS);

        limits.clearSignIn(PHONE);

        succeed(PHONE, ADDRESS);
    }

    @Test
    void onlyAnAccountActuallyCreatedCountsTowardsRegistration() {
        for (int i = 0; i < 5; i++) {
            limits.beginRegistration(ADDRESS).close();
        }

        for (int i = 0; i < 2; i++) {
            try (RegistrationAttempt attempt = limits.beginRegistration(ADDRESS)) {
                attempt.succeeded();
            }
        }

        assertThatThrownBy(() -> limits.beginRegistration(ADDRESS)).isInstanceOf(RateLimitedException.class);
        limits.beginRegistration("197.215.0.1").close();
    }

    @Test
    void everyPasswordResetRequestCountsAndTheWaitSlides() {
        limits.countPasswordResetRequest(ADDRESS);
        limits.countPasswordResetRequest(ADDRESS);

        assertThatThrownBy(() -> limits.countPasswordResetRequest(ADDRESS))
            .isInstanceOf(RateLimitedException.class)
            .satisfies(ex -> assertThat(((RateLimitedException) ex).getRetryAfterSeconds()).isEqualTo(3_600));

        clock.advance(Duration.ofHours(1));
        limits.countPasswordResetRequest(ADDRESS);
    }
}
