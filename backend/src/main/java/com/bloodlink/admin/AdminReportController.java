package com.bloodlink.admin;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.admin.dto.MonthlyDonationTotal;
import com.bloodlink.admin.dto.RequestOutcomeResponse;

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

    AdminReportController(AdminReportService adminReportService) {
        this.adminReportService = adminReportService;
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
}
