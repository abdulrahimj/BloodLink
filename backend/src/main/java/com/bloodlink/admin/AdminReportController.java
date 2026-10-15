package com.bloodlink.admin;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.admin.dto.MonthlyDonationTotal;
import com.bloodlink.admin.dto.RequestOutcomeResponse;
import com.bloodlink.admin.dto.UserCountsResponse;
import com.bloodlink.admin.report.ReportCsv;
import com.bloodlink.admin.report.ReportRange;
import com.bloodlink.admin.report.ReportSummaryService;
import com.bloodlink.admin.report.dto.ReportDtos.DonationReport;
import com.bloodlink.admin.report.dto.ReportDtos.DonorReport;
import com.bloodlink.admin.report.dto.ReportDtos.RequestReport;
import com.bloodlink.admin.report.dto.ReportDtos.StockReport;

/**
 * "View Reports & Analytics" — the administrator use case from CLAUDE.md.
 *
 * Both routes are GET-only aggregates with no path parameter: unlike the Blood
 * Bank Dashboard's per-bank figures or the Hospital Portal's per-hospital ones,
 * an administrator's reports are citywide by definition, so there is nothing
 * here to scope to a caller's own facility.
 */
@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {

    private final AdminReportService adminReportService;
    private final ReportSummaryService reportSummaryService;

    AdminReportController(AdminReportService adminReportService, ReportSummaryService reportSummaryService) {
        this.adminReportService = adminReportService;
        this.reportSummaryService = reportSummaryService;
    }

    /** Six months of citywide donation counts, oldest first — the trend chart. */
    @GetMapping("/donations")
    public List<MonthlyDonationTotal> donationTrends() {
        return adminReportService.donationTrends();
    }

    /** Citywide request counts by outcome — the donut chart. */
    @GetMapping("/requests")
    public RequestOutcomeResponse requestOutcomes() {
        return adminReportService.requestOutcomes();
    }

    /** Account counts, total and per role — the "Total Users" and "Registered
     *  Donors" cards and the User Accounts role buttons. */
    @GetMapping("/users")
    public UserCountsResponse userCounts() {
        return adminReportService.userCounts();
    }

    // ---------- the Reports page (Business Rule 23) ----------
    // Four read-only sections, each with a CSV twin. `from` / `to` are ISO dates
    // (yyyy-MM-dd), both optional — the last six months by default; a malformed date
    // is a 400 naming the field (GlobalExceptionHandler.handleTypeMismatch), and
    // ReportRange.resolve refuses a backwards range or one longer than five years.

    /** Section 1: stock across every bank, as of today. */
    @GetMapping("/summary/stock")
    public StockReport stockSummary() {
        return reportSummaryService.stock(LocalDate.now());
    }

    /** Section 2: hospital requests raised in the range. */
    @GetMapping("/summary/requests")
    public RequestReport requestSummary(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return reportSummaryService.requests(range(from, to));
    }

    /** Section 3: donations recorded in the range. */
    @GetMapping("/summary/donations")
    public DonationReport donationSummary(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return reportSummaryService.donations(range(from, to));
    }

    /** Section 4: donors and how many can donate today. */
    @GetMapping("/summary/donors")
    public DonorReport donorSummary() {
        return reportSummaryService.donors(LocalDate.now());
    }

    @GetMapping("/summary/stock/export")
    public ResponseEntity<byte[]> exportStock() {
        LocalDate today = LocalDate.now();
        return csv(ReportCsv.stock(reportSummaryService.stock(today), today), ReportCsv.fileName("stock", today));
    }

    @GetMapping("/summary/requests/export")
    public ResponseEntity<byte[]> exportRequests(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        ReportRange range = range(from, to);
        return csv(ReportCsv.requests(reportSummaryService.requests(range)),
            ReportCsv.fileName("requests", range.from(), range.to()));
    }

    @GetMapping("/summary/donations/export")
    public ResponseEntity<byte[]> exportDonations(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        ReportRange range = range(from, to);
        return csv(ReportCsv.donations(reportSummaryService.donations(range)),
            ReportCsv.fileName("donations", range.from(), range.to()));
    }

    @GetMapping("/summary/donors/export")
    public ResponseEntity<byte[]> exportDonors() {
        LocalDate today = LocalDate.now();
        return csv(ReportCsv.donors(reportSummaryService.donors(today), today), ReportCsv.fileName("donors", today));
    }

    /** The range in UTC — Sierra Leone's own time zone. */
    private static ReportRange range(LocalDate from, LocalDate to) {
        return ReportRange.resolve(from, to, LocalDate.now(ZoneOffset.UTC));
    }

    /** A CSV download: UTF-8, served as an attachment under a descriptive file name. */
    private static ResponseEntity<byte[]> csv(byte[] body, String fileName) {
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName).build().toString())
            .body(body);
    }
}
