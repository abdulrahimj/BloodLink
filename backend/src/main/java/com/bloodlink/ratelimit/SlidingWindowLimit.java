package com.bloodlink.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * One limit (Business Rule 34): at most maxEvents per key within a sliding window, then refused for the block time,
 * or with no block until the oldest event leaves the window. Thread-safe; memory bounded by maxEntries and expiry.
 */
final class SlidingWindowLimit {

    /** How long a caller waits when only attempts still being checked hold the last places. */
    private static final long IN_FLIGHT_WAIT_MILLIS = Duration.ofMinutes(1).toMillis();

    /** What one key holds. Replaced whole inside compute, so a reader never sees half a change. */
    private record State(long[] events, int inFlight, long blockedUntil) {

        private static final State EMPTY = new State(new long[0], 0, 0);

        private boolean isEmpty() {
            return events.length == 0 && inFlight == 0 && blockedUntil == 0;
        }
    }

    private final int maxEvents;
    private final long windowMillis;
    private final long blockMillis;
    private final Clock clock;
    private final Cache<String, State> states;

    /** A zero block means no block: the limit simply slides. A limit of nothing would refuse everyone, so it is refused. */
    SlidingWindowLimit(int maxEvents, Duration window, Duration block, int maxEntries, Clock clock) {
        if (maxEvents < 1 || maxEntries < 1 || !window.isPositive() || block.isNegative()) {
            throw new IllegalArgumentException("Rate limit settings must be positive.");
        }
        this.maxEvents = maxEvents;
        this.windowMillis = window.toMillis();
        this.blockMillis = block.toMillis();
        this.clock = clock;
        this.states = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            // Nothing a key holds outlives its last change by more than this.
            .expireAfterWrite(window.plus(block))
            .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
            // Evict on the calling thread, so the bound holds at once rather than when a pool gets round to it.
            .executor(Runnable::run)
            .build();
    }

    /**
     * Claims a place for an attempt, or returns how long to wait (0 means it may go ahead). Claiming before the work
     * stops parallel attempts all passing the check; finish with commit or cancel.
     */
    long reserve(String key) {
        long now = clock.millis();
        long[] wait = new long[1];
        states.asMap().compute(key, (ignored, current) -> {
            State state = current(current, now);
            wait[0] = waitFor(state, now);
            return wait[0] > 0 ? stored(state) : new State(state.events(), state.inFlight() + 1, state.blockedUntil());
        });
        return wait[0];
    }

    /** Turns a claimed place into a counted event. True for the one call that started a block. */
    boolean commit(String key) {
        long now = clock.millis();
        boolean[] blocked = new boolean[1];
        states.asMap().compute(key, (ignored, current) -> {
            State state = current(current, now);
            int inFlight = Math.max(0, state.inFlight() - 1);
            long[] events = Arrays.copyOf(state.events(), state.events().length + 1);
            events[events.length - 1] = now;

            if (blockMillis > 0 && events.length >= maxEvents) {
                blocked[0] = true;
                return new State(new long[0], inFlight, now + blockMillis);
            }
            return new State(events, inFlight, state.blockedUntil());
        });
        return blocked[0];
    }

    /** Gives a claimed place back without counting anything. */
    void cancel(String key) {
        long now = clock.millis();
        states.asMap().compute(key, (ignored, current) -> {
            State state = current(current, now);
            return stored(new State(state.events(), Math.max(0, state.inFlight() - 1), state.blockedUntil()));
        });
    }

    /** Forgets a key entirely: its count and any block. */
    void clear(String key) {
        states.invalidate(key);
    }

    /** Entries held right now, after any pending eviction. */
    long size() {
        states.cleanUp();
        return states.estimatedSize();
    }

    /** Drops events that have left the window and a block that has ended. */
    private State current(State state, long now) {
        if (state == null) {
            return State.EMPTY;
        }
        long[] events = Arrays.stream(state.events()).filter(event -> event + windowMillis > now).toArray();
        return new State(events, state.inFlight(), state.blockedUntil() > now ? state.blockedUntil() : 0);
    }

    private long waitFor(State state, long now) {
        if (state.blockedUntil() > now) {
            return state.blockedUntil() - now;
        }
        if (state.events().length >= maxEvents) {
            return state.events()[0] + windowMillis - now;
        }
        return state.events().length + state.inFlight() >= maxEvents ? IN_FLIGHT_WAIT_MILLIS : 0;
    }

    /** Null removes the entry, so a key with nothing to remember takes no memory. */
    private State stored(State state) {
        return state.isEmpty() ? null : state;
    }
}
