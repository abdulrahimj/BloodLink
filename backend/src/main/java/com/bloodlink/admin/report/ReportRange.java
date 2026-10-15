package com.bloodlink.admin.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.bloodlink.web.error.InvalidFieldException;

/**
 * The date range the Requests and Donations sections of the Admin Reports page cover
 * (Business Rule 23): {@code from} and {@code to}, both inclusive, in UTC — Sierra
 * Leone's own time, so a day here is the day the users there see.
 *
 * When neither is given, the range is the last six calendar months including this
 * one (the 1st of the month five months back, up to today) — the same six months the
 * Dashboard's trend chart already shows.
 */
public record ReportRange(LocalDate from, LocalDate to) {

    /** The longest range accepted; anything longer is refused rather than trimmed. */
    public static final int MAX_YEARS = 5;

    /**
     * Fills in a missing end with today, and a missing start with the 1st of the month
     * five months before the end, then refuses a range that runs backwards (400 on
     * "from") or spans more than five years (400 on "to"). Never silently corrects.
     */
    public static ReportRange resolve(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : YearMonth.from(end).minusMonths(5).atDay(1);
        if (start.isAfter(end)) {
            throw new InvalidFieldException("from", "from must be on or before to.");
        }
        if (end.isAfter(start.plusYears(MAX_YEARS))) {
            throw new InvalidFieldException("to", "The date range cannot be longer than " + MAX_YEARS + " years.");
        }
        return new ReportRange(start, end);
    }

    /** Midnight UTC at the start of {@code from} — the inclusive lower bound for a
     *  timestamp column such as blood_requests.created_at. */
    public Instant startInstant() {
        return from.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Midnight UTC after {@code to} — the exclusive upper bound, so the whole of the
     *  last day is included. */
    public Instant endInstantExclusive() {
        return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Every calendar month the range touches, oldest first. */
    public List<YearMonth> months() {
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            months.add(month);
        }
        return months;
    }
}
