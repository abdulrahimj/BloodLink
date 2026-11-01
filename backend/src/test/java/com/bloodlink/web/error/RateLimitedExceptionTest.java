package com.bloodlink.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Business Rule 34's wording: the real time left, rounded up, for the person and for the Retry-After header. */
class RateLimitedExceptionTest {

    private static final long MINUTE = 60_000;

    @Test
    void wholeMinutesAreStatedAsTheyAre() {
        assertThat(new RateLimitedException(15 * MINUTE).getMessage())
            .isEqualTo("Too many attempts. Please wait 15 minutes and try again.");
    }

    @Test
    void partMinutesRoundUp() {
        assertThat(new RateLimitedException(14 * MINUTE + 1).getMessage()).contains("wait 15 minutes");
    }

    @Test
    void oneMinuteIsSingular() {
        assertThat(new RateLimitedException(1).getMessage())
            .isEqualTo("Too many attempts. Please wait 1 minute and try again.");
    }

    @Test
    void retryAfterIsInWholeSecondsRoundedUp() {
        assertThat(new RateLimitedException(1).getRetryAfterSeconds()).isEqualTo(1);
        assertThat(new RateLimitedException(15 * MINUTE).getRetryAfterSeconds()).isEqualTo(900);
        assertThat(new RateLimitedException(1_001).getRetryAfterSeconds()).isEqualTo(2);
    }
}
