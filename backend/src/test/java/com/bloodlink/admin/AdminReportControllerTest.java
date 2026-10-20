package com.bloodlink.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.bloodlink.admin.report.ReportSummaryService;
import com.bloodlink.admin.report.dto.ReportDtos.DonationReport;
import com.bloodlink.web.error.GlobalExceptionHandler;

/**
 * The Reports endpoints' parameter handling (Business Rule 23): bad or backwards dates are a 400 naming the field,
 * exports are named CSV attachments. Access control is proven in ReportsIT.
 */
@ExtendWith(MockitoExtension.class)
class AdminReportControllerTest {

    @Mock private AdminReportService adminReportService;
    @Mock private ReportSummaryService reportSummaryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminReportController(adminReportService, reportSummaryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void aMalformedDateIsA400NamingTheField() throws Exception {
        mockMvc.perform(get("/api/admin/reports/summary/donations").param("from", "15/10/2026"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.from").exists());
        mockMvc.perform(get("/api/admin/reports/summary/requests").param("to", "2026-02-30"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.to").exists());
        verifyNoInteractions(reportSummaryService);
    }

    @Test
    void aBackwardsOrTooLongRangeIsA400BeforeAnyQueryRuns() throws Exception {
        mockMvc.perform(get("/api/admin/reports/summary/requests").param("from", "2026-06-02").param("to", "2026-06-01"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.from").exists());
        mockMvc.perform(get("/api/admin/reports/summary/donations/export").param("from", "2019-01-01").param("to", "2026-01-01"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.to").exists());
        verifyNoInteractions(reportSummaryService);
    }

    @Test
    void anExportIsANamedUtf8CsvAttachmentForTheRangeAsked() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        when(reportSummaryService.donations(any())).thenReturn(new DonationReport(from, to, 0, List.of(), List.of(), List.of()));

        mockMvc.perform(get("/api/admin/reports/summary/donations/export").param("from", "2026-01-01").param("to", "2026-01-31"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
            .andExpect(header().string("Content-Disposition",
                "attachment; filename=\"bloodlink-report-donations-2026-01-01-to-2026-01-31.csv\""));
    }
}
