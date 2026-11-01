package com.bloodlink.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

/** The counting behind Business Rule 34, with a clock the tests move instead of sleeping. */
class SlidingWindowLimitTest {

    private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);

    /** A clock these tests move by hand. */
    static final class MovableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-01T08:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();

    private SlidingWindowLimit lockingLimit() {
        return new SlidingWindowLimit(3, FIFTEEN_MINUTES, FIFTEEN_MINUTES, 1_000, clock);
    }

    private SlidingWindowLimit slidingLimit() {
        return new SlidingWindowLimit(2, Duration.ofHours(1), Duration.ZERO, 1_000, clock);
    }

    private boolean countOne(SlidingWindowLimit limit, String key) {
        assertThat(limit.reserve(key)).isZero();
        return limit.commit(key);
    }

    @Test
    void reachingTheLimitBlocksOnceForTheWholeBlockTime() {
        SlidingWindowLimit limit = lockingLimit();

        assertThat(countOne(limit, "k")).isFalse();
        assertThat(countOne(limit, "k")).isFalse();
        assertThat(countOne(limit, "k")).as("only the call that crossed the limit reports it").isTrue();

        assertThat(limit.reserve("k")).isEqualTo(FIFTEEN_MINUTES.toMillis());
    }

    @Test
    void aRefusedAttemptDoesNotExtendTheBlock() {
        SlidingWindowLimit limit = lockingLimit();
        for (int i = 0; i < 3; i++) countOne(limit, "k");

        clock.advance(Duration.ofMinutes(14));
        assertThat(limit.reserve("k")).isEqualTo(Duration.ofMinutes(1).toMillis());

        clock.advance(Duration.ofMinutes(1));
        assertThat(limit.reserve("k")).as("free again at exactly the block's end").isZero();
    }

    @Test
    void aFreshStartFollowsABlockEvenWhenTheWindowIsLongerThanIt() {
        SlidingWindowLimit limit = new SlidingWindowLimit(3, Duration.ofHours(1), FIFTEEN_MINUTES, 1_000, clock);
        for (int i = 0; i < 3; i++) countOne(limit, "k");

        clock.advance(FIFTEEN_MINUTES);

        assertThat(countOne(limit, "k")).as("one failure after a block is one, not four").isFalse();
    }

    @Test
    void eventsOutsideTheWindowNoLongerCount() {
        SlidingWindowLimit limit = lockingLimit();
        countOne(limit, "k");
        clock.advance(Duration.ofMinutes(10));
        countOne(limit, "k");
        clock.advance(Duration.ofMinutes(5));

        // The first has slid out, so this is the second inside the window.
        assertThat(countOne(limit, "k")).isFalse();
        assertThat(countOne(limit, "k")).isTrue();
    }

    @Test
    void withoutABlockTheWaitEndsWhenTheOldestEventLeavesTheWindow() {
        SlidingWindowLimit limit = slidingLimit();
        countOne(limit, "k");
        clock.advance(Duration.ofMinutes(20));
        countOne(limit, "k");

        assertThat(limit.reserve("k")).isEqualTo(Duration.ofMinutes(40).toMillis());

        clock.advance(Duration.ofMinutes(40));
        assertThat(limit.reserve("k")).isZero();
    }

    @Test
    void clearForgetsTheCountAndTheBlock() {
        SlidingWindowLimit limit = lockingLimit();
        for (int i = 0; i < 3; i++) countOne(limit, "k");

        limit.clear("k");

        assertThat(limit.reserve("k")).isZero();
    }

    @Test
    void cancelCountsNothingAndKeepsNothing() {
        SlidingWindowLimit limit = lockingLimit();
        for (int i = 0; i < 10; i++) {
            assertThat(limit.reserve("k")).isZero();
            limit.cancel("k");
        }

        assertThat(limit.size()).as("a key with nothing to remember takes no memory").isZero();
    }

    @Test
    void keysAreCountedSeparately() {
        SlidingWindowLimit limit = lockingLimit();
        for (int i = 0; i < 3; i++) countOne(limit, "locked");

        assertThat(limit.reserve("other")).isZero();
    }

    @Test
    void attemptsStillBeingCheckedHoldTheirPlaces() {
        SlidingWindowLimit limit = lockingLimit();
        for (int i = 0; i < 3; i++) assertThat(limit.reserve("k")).isZero();

        assertThat(limit.reserve("k")).as("a fourth at the same moment must wait").isEqualTo(Duration.ofMinutes(1).toMillis());
    }

    @Test
    void parallelAttemptsCannotGetMoreGuessesThanTheLimit() throws Exception {
        SlidingWindowLimit limit = lockingLimit();
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> granted = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            granted.add(pool.submit(() -> {
                start.await();
                return limit.reserve("k") == 0;
            }));
        }
        start.countDown();

        int admitted = 0;
        for (Future<Boolean> one : granted) {
            if (one.get()) admitted++;
        }
        pool.shutdown();

        assertThat(admitted).isEqualTo(3);
    }

    @Test
    void theNumberOfEntriesNeverPassesTheBound() {
        int bound = 500;
        SlidingWindowLimit limit = new SlidingWindowLimit(3, FIFTEEN_MINUTES, FIFTEEN_MINUTES, bound, clock);

        for (int i = 0; i < 100_000; i++) {
            limit.reserve("+232" + i);
            limit.commit("+232" + i);
        }

        assertThat(limit.size()).isLessThanOrEqualTo(bound);
    }

    @Test
    void entriesExpireOnTheirOwn() {
        SlidingWindowLimit limit = lockingLimit();
        for (int i = 0; i < 100; i++) countOne(limit, "key-" + i);
        assertThat(limit.size()).isEqualTo(100);

        clock.advance(Duration.ofMinutes(31));

        assertThat(limit.size()).isZero();
    }

    @Test
    void aLimitThatWouldRefuseEveryoneIsRefusedAtStartup() {
        assertThatThrownBy(() -> new SlidingWindowLimit(0, FIFTEEN_MINUTES, FIFTEEN_MINUTES, 1_000, clock))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowLimit(3, Duration.ZERO, FIFTEEN_MINUTES, 1_000, clock))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowLimit(3, FIFTEEN_MINUTES, FIFTEEN_MINUTES, 0, clock))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
