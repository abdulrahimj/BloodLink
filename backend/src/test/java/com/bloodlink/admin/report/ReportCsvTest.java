package com.bloodlink.admin.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bloodlink.admin.report.dto.ReportDtos.CountRow;
import com.bloodlink.admin.report.dto.ReportDtos.DonationReport;
import com.bloodlink.admin.report.dto.ReportDtos.MonthRow;
import com.bloodlink.admin.report.dto.ReportDtos.RequestReport;
import com.bloodlink.admin.report.dto.ReportDtos.StockBankRow;
import com.bloodlink.admin.report.dto.ReportDtos.StockGroupRow;
import com.bloodlink.admin.report.dto.ReportDtos.StockReport;
import com.bloodlink.domain.enums.StockStatus;

/**
 * The Reports CSV files (Business Rule 23): quoting, formula-injection defusing, the
 * UTF-8 byte-order mark, CRLF rows, "No data" for a missing rate, and file names.
 */
class ReportCsvTest {

    @Test
    void textCellsThatASpreadsheetWouldRunAsAFormulaArePrefixedWithAnApostrophe() {
        assertThat(ReportCsv.text("=SUM(A1:A9)")).isEqualTo("\"'=SUM(A1:A9)\"");
        assertThat(ReportCsv.text("+232 76")).isEqualTo("\"'+232 76\"");
        assertThat(ReportCsv.text("-1+1")).isEqualTo("\"'-1+1\"");
        assertThat(ReportCsv.text("@cmd")).isEqualTo("\"'@cmd\"");
        assertThat(ReportCsv.text("\tTabbed")).isEqualTo("\"'\tTabbed\"");
        assertThat(ReportCsv.text("\rReturn")).isEqualTo("\"'\rReturn\"");
    }

    @Test
    void ordinaryTextIsQuotedAndInnerQuotesAreDoubled() {
        assertThat(ReportCsv.text("Makeni Regional Blood Bank")).isEqualTo("\"Makeni Regional Blood Bank\"");
        assertThat(ReportCsv.text("Bank, \"North\"")).isEqualTo("\"Bank, \"\"North\"\"\"");
        assertThat(ReportCsv.text("Two\nlines")).isEqualTo("\"Two\nlines\"");
        assertThat(ReportCsv.text("A+")).isEqualTo("\"A+\"");          // only the first character matters
        assertThat(ReportCsv.text("")).isEqualTo("\"\"");
        assertThat(ReportCsv.text(null)).isEmpty();
    }

    @Test
    void aFormulaWithQuotesIsBothDefusedAndEscaped() {
        assertThat(ReportCsv.text("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
    }

    @Test
    void theFileStartsWithAUtf8BomAndRowsEndInCrlf() {
        StockReport report = new StockReport(12, 3, 1, 28,
            List.of(new StockGroupRow("A+", 12, StockStatus.CRITICAL)),
            List.of(new StockBankRow("=Evil Bank", 12, 8, 0)));

        byte[] bytes = ReportCsv.stock(report, LocalDate.of(2026, 10, 15));
        String text = new String(bytes, StandardCharsets.UTF_8);

        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        assertThat(text).contains("\"A+\",12,\"CRITICAL\"\r\n");
        assertThat(text).contains("\"'=Evil Bank\",12,8,0\r\n");
        assertThat(text).contains("\"As of\",\"2026-10-15\"\r\n");
        assertThat(text.replace("\r\n", "")).doesNotContain("\n");   // every line break is CRLF
    }

    @Test
    void theRequestsFileStatesItsRangeAndSaysNoDataRatherThanAPercentageWhenNothingWasDecided() {
        RequestReport report = new RequestReport(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 2,
            List.of(new CountRow("PENDING", 2)), 0, null, 0, List.of(), List.of());

        String text = new String(ReportCsv.requests(report), StandardCharsets.UTF_8);

        assertThat(text).contains("\"From\",\"2026-01-01\"").contains("\"To\",\"2026-03-31\"");
        assertThat(text).contains("\"Fulfilment rate (fulfilled / decided)\",\"No data\"");
        assertThat(text).doesNotContain("NaN");
    }

    @Test
    void percentagesAreOneDecimalPlace() {
        assertThat(ReportCsv.percent(0.75)).isEqualTo("75.0%");
        assertThat(ReportCsv.percent(2.0 / 3.0)).isEqualTo("66.7%");
        assertThat(ReportCsv.percent(null)).isEqualTo("No data");
    }

    @Test
    void theDonationsFileListsEveryMonth() {
        DonationReport report = new DonationReport(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), 3,
            List.of(new MonthRow("2026-01", "Jan 2026", 3), new MonthRow("2026-02", "Feb 2026", 0)),
            List.of(new CountRow("Holy Spirit Blood Bank", 3)), List.of(new CountRow("O+", 3)));

        String text = new String(ReportCsv.donations(report), StandardCharsets.UTF_8);

        assertThat(text).contains("\"Jan 2026\",3\r\n").contains("\"Feb 2026\",0\r\n").contains("\"Holy Spirit Blood Bank\",3\r\n");
    }

    @Test
    void fileNamesNameTheSectionAndTheDates() {
        assertThat(ReportCsv.fileName("requests", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 10, 15)))
            .isEqualTo("bloodlink-report-requests-2026-05-01-to-2026-10-15.csv");
        assertThat(ReportCsv.fileName("stock", LocalDate.of(2026, 10, 15)))
            .isEqualTo("bloodlink-report-stock-2026-10-15.csv");
    }
}
