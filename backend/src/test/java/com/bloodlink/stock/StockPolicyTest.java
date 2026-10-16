package com.bloodlink.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.bloodlink.config.TimeConfig;
import com.bloodlink.stock.StockPolicy.ExpiryState;

/**
 * The definitions every stock figure will be built on, tested against a fixed
 * clock: expired, usable, expiring soon, and the exact moment a batch changes from
 * one to the other (00:00:00 UTC the day after its expiry date).
 */
class StockPolicyTest {

    /** Friday 16 October 2026, the reference "today" for every date rule below. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private static StockPolicy policyAt(String instant) {
        return new StockPolicy(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    // ---------- the expiry-day boundary ----------

    @Test
    void aBatchIsStillUsableAtTheLastSecondOfItsExpiryDate() {
        StockPolicy policy = policyAt("2026-10-16T23:59:59Z");

        assertThat(policy.today()).isEqualTo(TODAY);
        assertThat(policy.isExpired(TODAY)).isFalse();
        assertThat(policy.isUsable(TODAY)).isTrue();
        assertThat(policy.stateOf(TODAY)).isEqualTo(ExpiryState.EXPIRING_SOON);
        assertThat(policy.daysUntilExpiry(TODAY)).isZero();
    }

    @Test
    void theSameBatchIsExpiredFromMidnightUtcTheNextDay() {
        StockPolicy policy = policyAt("2026-10-17T00:00:00Z");

        assertThat(policy.isExpired(TODAY)).isTrue();
        assertThat(policy.isUsable(TODAY)).isFalse();
        assertThat(policy.isExpiringSoon(TODAY)).isFalse();
        assertThat(policy.stateOf(TODAY)).isEqualTo(ExpiryState.EXPIRED);
        assertThat(policy.daysUntilExpiry(TODAY)).isEqualTo(-1);
    }

    @Test
    void theFirstSecondOfTheExpiryDateIsUsableToo() {
        assertThat(policyAt("2026-10-16T00:00:00Z").isUsable(TODAY)).isTrue();
        // And one second before it, the batch is a day away.
        assertThat(policyAt("2026-10-15T23:59:59Z").daysUntilExpiry(TODAY)).isEqualTo(1);
    }

    // ---------- the rules, day by day ----------

    @Test
    void yesterdayIsExpired() {
        assertThat(StockPolicy.isExpired(TODAY.minusDays(1), TODAY)).isTrue();
        assertThat(StockPolicy.stateOf(TODAY.minusDays(1), TODAY)).isEqualTo(ExpiryState.EXPIRED);
        assertThat(StockPolicy.daysUntilExpiry(TODAY.minusDays(1), TODAY)).isEqualTo(-1);
    }

    @Test
    void todayIsUsableAndExpiringSoonWithZeroDaysLeft() {
        assertThat(StockPolicy.isUsable(TODAY, TODAY)).isTrue();
        assertThat(StockPolicy.isExpiringSoon(TODAY, TODAY)).isTrue();
        assertThat(StockPolicy.daysUntilExpiry(TODAY, TODAY)).isZero();
    }

    @Test
    void theExpiringSoonWindowIncludesItsLastDayAndNotTheDayAfter() {
        LocalDate lastDayInWindow = TODAY.plusDays(28);
        LocalDate firstDayOutside = TODAY.plusDays(29);

        assertThat(StockPolicy.expiringSoonCutoff(TODAY)).isEqualTo(lastDayInWindow);
        assertThat(StockPolicy.stateOf(lastDayInWindow, TODAY)).isEqualTo(ExpiryState.EXPIRING_SOON);
        assertThat(StockPolicy.stateOf(firstDayOutside, TODAY)).isEqualTo(ExpiryState.USABLE);
    }

    @Test
    void anExpiredBatchIsNeverExpiringSoon() {
        // The old Inventory screen counted already-expired stock as "expiring soon".
        assertThat(StockPolicy.isExpiringSoon(TODAY.minusDays(3), TODAY)).isFalse();
    }

    @Test
    void onlyExpiredIsNotUsable() {
        assertThat(ExpiryState.USABLE.isUsable()).isTrue();
        assertThat(ExpiryState.EXPIRING_SOON.isUsable()).isTrue();
        assertThat(ExpiryState.EXPIRED.isUsable()).isFalse();
    }

    // ---------- the constants ----------

    @Test
    void theNamedConstantsHoldTheAgreedValues() {
        // Changing either is a decision for the project owner, not a refactor.
        assertThat(StockPolicy.EXPIRING_SOON_WINDOW_DAYS).isEqualTo(28);
        assertThat(StockPolicy.DONATION_SHELF_LIFE_DAYS).isEqualTo(42);
        assertThat(StockPolicy.defaultDonationExpiry(TODAY)).isEqualTo(LocalDate.of(2026, 11, 27));
    }

    // ---------- an expiry date entered by staff ----------

    @Test
    void anEnteredExpiryMayBeTodayUpToExactly365DaysAwayAndNothingElse() {
        assertThat(StockPolicy.problemWithEnteredExpiry(TODAY, TODAY)).isEmpty();
        assertThat(StockPolicy.problemWithEnteredExpiry(TODAY.plusDays(365), TODAY)).isEmpty();

        assertThat(StockPolicy.problemWithEnteredExpiry(TODAY.minusDays(1), TODAY))
            .hasValueSatisfying(message -> assertThat(message).contains("cannot be in the past"));
        assertThat(StockPolicy.problemWithEnteredExpiry(TODAY.plusDays(366), TODAY))
            .hasValueSatisfying(message -> assertThat(message)
                .contains("within 365 days").contains(TODAY.plusDays(365).toString()));
        assertThat(StockPolicy.MAX_ENTERED_EXPIRY_DAYS).isEqualTo(365);
    }

    // ---------- the clock bean ----------

    @Test
    void theApplicationClockIsUtc() {
        // Every "today" in the stock rules comes from this clock, so it must be UTC
        // whatever time zone the server itself is set to.
        Clock clock = new TimeConfig().clock();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
