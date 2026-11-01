package com.bloodlink.web.error;

/**
 * Over a Business Rule 34 limit: a 429 with Retry-After. The wording is the same for every limit and every number,
 * so it never reveals whether a number is registered.
 */
public class RateLimitedException extends RuntimeException {

    private static final long MILLIS_PER_SECOND = 1_000;
    private static final long SECONDS_PER_MINUTE = 60;

    private final long retryAfterSeconds;

    public RateLimitedException(long waitMillis) {
        super(messageFor(waitMillis));
        this.retryAfterSeconds = roundedUp(waitMillis, MILLIS_PER_SECOND);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** The real time left, rounded up to whole minutes, so nobody comes back a moment too soon. */
    private static String messageFor(long waitMillis) {
        long minutes = roundedUp(waitMillis, MILLIS_PER_SECOND * SECONDS_PER_MINUTE);
        return "Too many attempts. Please wait %d %s and try again.".formatted(minutes, minutes == 1 ? "minute" : "minutes");
    }

    private static long roundedUp(long value, long unit) {
        return Math.ceilDiv(value, unit);
    }
}
