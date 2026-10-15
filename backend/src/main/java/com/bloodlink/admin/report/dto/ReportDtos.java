package com.bloodlink.admin.report.dto;

import java.time.LocalDate;
import java.util.List;

import com.bloodlink.domain.enums.StockStatus;

/**
 * The response shapes of the Admin Reports page (Business Rule 23), kept together
 * because each is small and none is used anywhere else.
 *
 * Aggregates only, by design: counts and sums keyed by blood group, status, urgency,
 * month or blood bank name. Nothing here can carry a donor's name, phone, email,
 * address or date of birth — ReportSummaryServiceTest checks every record's fields.
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /** One labelled count — a blood group, status, urgency level or bank name. */
    public record CountRow(String label, long count) {}

    /** One blood group's units across every bank, and its status by Business Rule 3. */
    public record StockGroupRow(String bloodType, long units, StockStatus status) {}

    /** One bank's total units and how many of its eight groups are Critical or Low —
     *  the same per-group judgement its own inventory page makes. */
    public record StockBankRow(String bankName, long totalUnits, int criticalGroups, int lowGroups) {}

    /** Section 1. Units past their expiry date are counted as stock, as the Inventory
     *  screen's totals count them; {@code pastExpiryUnits} says how many of the total that is. */
    public record StockReport(
        long totalUnits,
        long expiringSoonUnits,
        long pastExpiryUnits,
        int expiringSoonWindowDays,
        List<StockGroupRow> groups,
        List<StockBankRow> banks
    ) {}

    /** Section 2. {@code fulfilmentRate} is fulfilled / (fulfilled + rejected), as a
     *  fraction, and null — "No data" — when nothing in the range has been decided. */
    public record RequestReport(
        LocalDate from,
        LocalDate to,
        long total,
        List<CountRow> byStatus,
        long decided,
        Double fulfilmentRate,
        long filledByMoreThanOneBank,
        List<CountRow> byBloodType,
        List<CountRow> byUrgency
    ) {}

    /** One calendar month: "2026-10", "Oct 2026", and its donation count. */
    public record MonthRow(String month, String label, long count) {}

    /** Section 3. Every month in the range appears, at zero if it had no donations. */
    public record DonationReport(
        LocalDate from,
        LocalDate to,
        long total,
        List<MonthRow> byMonth,
        List<CountRow> byBank,
        List<CountRow> byBloodType
    ) {}

    /** Section 4. {@code eligible + inRecovery == total}. */
    public record DonorReport(
        long total,
        long eligible,
        long inRecovery,
        List<CountRow> byBloodType
    ) {}
}
