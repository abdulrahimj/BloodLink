package com.bloodlink.admin.report;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import com.bloodlink.admin.report.dto.ReportDtos.CountRow;
import com.bloodlink.admin.report.dto.ReportDtos.DonationReport;
import com.bloodlink.admin.report.dto.ReportDtos.DonorReport;
import com.bloodlink.admin.report.dto.ReportDtos.MonthRow;
import com.bloodlink.admin.report.dto.ReportDtos.RequestReport;
import com.bloodlink.admin.report.dto.ReportDtos.StockBankRow;
import com.bloodlink.admin.report.dto.ReportDtos.StockGroupRow;
import com.bloodlink.admin.report.dto.ReportDtos.StockReport;

/**
 * The "Download CSV" files of the Admin Reports page (Business Rule 23), built on the
 * server from the same report objects the page shows.
 *
 * <h2>Made safe to open in a spreadsheet</h2>
 * <ul>
 *   <li><b>Formula injection.</b> A spreadsheet runs a cell starting with {@code =},
 *       {@code +}, {@code -} or {@code @} (or a tab or carriage return) as a formula.
 *       A text cell that starts with one is prefixed with an apostrophe, so it shows
 *       as text. Bank names are typed by administrators, so this is not theoretical.</li>
 *   <li><b>Quoting.</b> Every text cell is wrapped in double quotes and any double
 *       quote inside it doubled, so commas, quotes and line breaks stay in their cell.
 *       Numbers are written bare, so they stay numbers.</li>
 *   <li><b>Encoding.</b> UTF-8 with a byte-order mark, which is what makes Excel read
 *       it as UTF-8 rather than guessing; rows end in CRLF (RFC 4180).</li>
 * </ul>
 * Each file is one or more small titled tables separated by a blank row.
 */
public final class ReportCsv {

    /** The UTF-8 byte-order mark (U+FEFF), built from its code point so it is visible here. */
    private static final String BOM = String.valueOf((char) 0xFEFF);
    private static final String NEWLINE = "\r\n";

    private final StringBuilder out = new StringBuilder(BOM);

    private ReportCsv() {
    }

    /**
     * One text cell, safe for a spreadsheet: apostrophe-prefixed if it would otherwise
     * be read as a formula, then quoted. Null is an empty cell.
     */
    static String text(String value) {
        if (value == null) {
            return "";
        }
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    /** Writes one row: numbers bare, everything else through {@link #text}. */
    private ReportCsv row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            Object cell = cells[i];
            out.append(cell instanceof Number number ? number.toString() : text(cell == null ? null : cell.toString()));
        }
        out.append(NEWLINE);
        return this;
    }

    private ReportCsv blank() {
        out.append(NEWLINE);
        return this;
    }

    private ReportCsv countTable(String heading, String countHeading, List<CountRow> rows) {
        blank().row(heading, countHeading);
        rows.forEach(r -> row(r.label(), r.count()));
        return this;
    }

    private byte[] bytes() {
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** "75.0%", or "No data" when nothing was decided — the same wording as the page. */
    static String percent(Double rate) {
        return rate == null ? "No data" : String.format(Locale.ENGLISH, "%.1f%%", rate * 100);
    }

    // ---------- one file per section ----------

    public static byte[] stock(StockReport report, LocalDate today) {
        ReportCsv csv = new ReportCsv()
            .row("BloodLink report", "Stock overview (all blood banks)")
            .row("As of", today.toString())
            .blank()
            .row("Measure", "Units")
            .row("Total units", report.totalUnits())
            .row("Expiring within " + report.expiringSoonWindowDays() + " days (including already expired)", report.expiringSoonUnits())
            .row("Already past expiry date", report.pastExpiryUnits())
            .blank()
            .row("Blood group", "Units", "Status");
        for (StockGroupRow group : report.groups()) {
            csv.row(group.bloodType(), group.units(), group.status().name());
        }
        csv.blank().row("Blood bank", "Total units", "Critical groups", "Low groups");
        for (StockBankRow bank : report.banks()) {
            csv.row(bank.bankName(), bank.totalUnits(), bank.criticalGroups(), bank.lowGroups());
        }
        return csv.bytes();
    }

    public static byte[] requests(RequestReport report) {
        return new ReportCsv()
            .row("BloodLink report", "Hospital requests")
            .row("From", report.from().toString())
            .row("To", report.to().toString())
            .blank()
            .row("Measure", "Value")
            .row("Requests raised", report.total())
            .row("Decided (fulfilled + rejected)", report.decided())
            .row("Fulfilment rate (fulfilled / decided)", percent(report.fulfilmentRate()))
            .row("Filled by more than one blood bank", report.filledByMoreThanOneBank())
            .countTable("Status", "Requests", report.byStatus())
            .countTable("Blood group", "Requests", report.byBloodType())
            .countTable("Urgency", "Requests", report.byUrgency())
            .bytes();
    }

    public static byte[] donations(DonationReport report) {
        ReportCsv csv = new ReportCsv()
            .row("BloodLink report", "Donations")
            .row("From", report.from().toString())
            .row("To", report.to().toString())
            .row("Total donations", report.total())
            .blank()
            .row("Month", "Donations");
        for (MonthRow month : report.byMonth()) {
            csv.row(month.label(), month.count());
        }
        return csv.countTable("Blood bank", "Donations", report.byBank())
            .countTable("Blood group (as tested)", "Donations", report.byBloodType())
            .bytes();
    }

    public static byte[] donors(DonorReport report, LocalDate today) {
        return new ReportCsv()
            .row("BloodLink report", "Donors")
            .row("As of", today.toString())
            .blank()
            .row("Measure", "Donors")
            .row("Registered donors", report.total())
            .row("Eligible to donate now", report.eligible())
            .row("In the 90-day recovery period", report.inRecovery())
            .countTable("Blood group", "Donors", report.byBloodType())
            .bytes();
    }

    /** "bloodlink-report-requests-2026-05-01-to-2026-10-15.csv". */
    public static String fileName(String section, LocalDate from, LocalDate to) {
        return "bloodlink-report-" + section + "-" + from + "-to-" + to + ".csv";
    }

    /** "bloodlink-report-stock-2026-10-15.csv", for the sections with no date range. */
    public static String fileName(String section, LocalDate asOf) {
        return "bloodlink-report-" + section + "-" + asOf + ".csv";
    }
}
