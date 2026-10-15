package com.bloodlink.admin.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

import com.bloodlink.web.error.InvalidFieldException;

/**
 * The Reports date range (Business Rule 23): its six-month default, the refusals —
 * backwards and over five years, each naming its field — and its UTC day bounds.
 */
class ReportRangeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    @Test
    void noDatesMeansTheFirstOfTheMonthFiveMonthsBackUntilToday() {
        ReportRange range = ReportRange.resolve(null, null, TODAY);

        assertThat(range.from()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(range.to()).isEqualTo(TODAY);
        assertThat(range.months()).containsExactly(
            YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7),
            YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
    }

    @Test
    void onlyAnEndDateCountsSixMonthsBackFromThatEnd() {
        assertThat(ReportRange.resolve(null, LocalDate.of(2025, 3, 20), TODAY).from()).isEqualTo(LocalDate.of(2024, 10, 1));
    }

    @Test
    void aSingleDayIsAllowed() {
        ReportRange range = ReportRange.resolve(TODAY, TODAY, TODAY);
        assertThat(range.months()).containsExactly(YearMonth.of(2026, 10));
    }

    @Test
    void aBackwardsRangeIsRefusedOnFrom() {
        assertThatThrownBy(() -> ReportRange.resolve(LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 1), TODAY))
            .isInstanceOfSatisfying(InvalidFieldException.class, e -> assertThat(e.getField()).isEqualTo("from"));
    }

    @Test
    void exactlyFiveYearsIsAllowedAndOneDayMoreIsRefusedOnTo() {
        assertThat(ReportRange.resolve(LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 1), TODAY)).isNotNull();
        assertThatThrownBy(() -> ReportRange.resolve(LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 2), TODAY))
            .isInstanceOfSatisfying(InvalidFieldException.class, e -> assertThat(e.getField()).isEqualTo("to"));
    }

    @Test
    void theRangeCoversWholeUtcDays() {
        ReportRange range = new ReportRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertThat(range.startInstant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(range.endInstantExclusive()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }
}
