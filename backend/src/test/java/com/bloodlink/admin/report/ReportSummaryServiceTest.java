package com.bloodlink.admin.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bloodlink.admin.report.dto.ReportDtos;
import com.bloodlink.admin.report.dto.ReportDtos.CountRow;
import com.bloodlink.admin.report.dto.ReportDtos.DonationReport;
import com.bloodlink.admin.report.dto.ReportDtos.DonorReport;
import com.bloodlink.admin.report.dto.ReportDtos.RequestReport;
import com.bloodlink.admin.report.dto.ReportDtos.StockBankRow;
import com.bloodlink.admin.report.dto.ReportDtos.StockGroupRow;
import com.bloodlink.admin.report.dto.ReportDtos.StockReport;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository.BankGroupUnits;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.BloodTypeCount;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;

/**
 * ReportSummaryService's own arithmetic (Business Rule 23): zero-filling, threshold edges, "No data" rate,
 * eligibility cutoff and no personal-data fields. The queries run for real in ReportsIT.
 */
@ExtendWith(MockitoExtension.class)
class ReportSummaryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    @Mock private BloodInventoryBatchRepository batchRepository;
    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private BloodRequestRepository requestRepository;
    @Mock private DonationRepository donationRepository;
    @Mock private DonorRepository donorRepository;

    @InjectMocks private ReportSummaryService service;

    // ---------- stock ----------

    @Test
    void citywideGroupStatusUsesBusinessRule3AtBothEdges() {
        when(batchRepository.sumUsableUnitsByBloodType(TODAY)).thenReturn(List.of(
            typeCount(BloodType.A_POSITIVE, 14), typeCount(BloodType.A_NEGATIVE, 15),
            typeCount(BloodType.B_POSITIVE, 39), typeCount(BloodType.B_NEGATIVE, 40)));

        StockReport report = service.stock(TODAY);

        assertThat(status(report, "A+")).isEqualTo(StockStatus.CRITICAL);
        assertThat(status(report, "A-")).isEqualTo(StockStatus.LOW);
        assertThat(status(report, "B+")).isEqualTo(StockStatus.LOW);
        assertThat(status(report, "B-")).isEqualTo(StockStatus.SUFFICIENT);
        assertThat(status(report, "O+")).isEqualTo(StockStatus.CRITICAL);   // absent = 0 units
        assertThat(report.groups()).extracting(StockGroupRow::bloodType)
            .containsExactly("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-");
        assertThat(report.totalUnits()).isEqualTo(108);
    }

    /**
     * Each bank's groups are judged with StockStatus's own thresholds on the usable units summed per (bank, group).
     */
    @Test
    void eachBanksGroupsAreJudgedWithStockStatusAndMissingGroupsAreCritical() {
        BloodBank partly = bank("Partly Stocked");
        BloodBank empty = bank("No Stock Yet");
        when(bloodBankRepository.findAllByOrderByNameAsc()).thenReturn(List.of(partly, empty));
        when(batchRepository.sumUsableUnitsByBankAndBloodType(TODAY)).thenReturn(List.of(
            bankGroup(partly, BloodType.A_POSITIVE, StockStatus.LOW_BELOW + 10),  // 50: Sufficient
            bankGroup(partly, BloodType.A_NEGATIVE, StockStatus.LOW_BELOW - 20),  // 20: Low
            bankGroup(partly, BloodType.B_POSITIVE, StockStatus.CRITICAL_BELOW + 15), // 30: Low
            bankGroup(partly, BloodType.B_NEGATIVE, StockStatus.CRITICAL_BELOW - 7))); // 8: Critical

        StockReport report = service.stock(TODAY);

        // Groups with no usable stock are zero units, so Critical — as the bank's own
        // page shows: 1 judged Critical + 4 never stocked = 5.
        assertThat(report.banks()).containsExactly(
            new StockBankRow("Partly Stocked", 108, 5, 2),
            new StockBankRow("No Stock Yet", 0, 8, 0));
    }

    /**
     * Expired units are reported separately, never in the total or "expiring soon"
     * (usable units within 28 days) (Business Rules 23, 24).
     */
    @Test
    void expiredUnitsAreReportedSeparatelyAndExpiringSoonIsUsableStockWithinTwentyEightDays() {
        when(batchRepository.sumUsableUnitsByBloodType(TODAY)).thenReturn(List.of(typeCount(BloodType.O_POSITIVE, 60)));
        when(batchRepository.sumUsableUnitsExpiringOnOrBefore(TODAY, TODAY.plusDays(28))).thenReturn(19L);
        when(batchRepository.sumExpiredUnits(TODAY)).thenReturn(5L);

        StockReport report = service.stock(TODAY);

        assertThat(report.expiringSoonUnits()).isEqualTo(19);
        assertThat(report.pastExpiryUnits()).isEqualTo(5);
        assertThat(report.expiringSoonWindowDays()).isEqualTo(28);
        // The total is usable stock only: the 5 expired units are not added to it.
        assertThat(report.totalUnits()).isEqualTo(60);
    }

    @Test
    void anEmptyDatabaseGivesEightZeroGroupsAndNoBanks() {
        when(batchRepository.sumUsableUnitsByBloodType(TODAY)).thenReturn(List.of());

        StockReport report = service.stock(TODAY);

        assertThat(report.totalUnits()).isZero();
        assertThat(report.groups()).hasSize(8).allMatch(g -> g.units() == 0 && g.status() == StockStatus.CRITICAL);
        assertThat(report.banks()).isEmpty();
    }

    // ---------- requests ----------

    @Test
    void fulfilmentRateIsFulfilledOverDecidedAndOpenRequestsDoNotCount() {
        ReportRange range = new ReportRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        when(requestRepository.countByStatusCreatedBetween(range.startInstant(), range.endInstantExclusive())).thenReturn(List.of(
            statusCount(RequestStatus.FULFILLED, 3), statusCount(RequestStatus.REJECTED, 1),
            statusCount(RequestStatus.PENDING, 4), statusCount(RequestStatus.PARTIALLY_FULFILLED, 2)));
        when(requestRepository.countByUrgencyCreatedBetween(any(), any())).thenReturn(List.of());
        when(requestRepository.countByBloodTypeCreatedBetween(any(), any())).thenReturn(List.of());

        RequestReport report = service.requests(range);

        assertThat(report.total()).isEqualTo(10);
        assertThat(report.decided()).isEqualTo(4);
        assertThat(report.fulfilmentRate()).isEqualTo(0.75);
        assertThat(report.byStatus()).extracting(CountRow::label)
            .containsExactly("PENDING", "PARTIALLY_FULFILLED", "FULFILLED", "REJECTED");
        assertThat(report.byUrgency()).containsExactly(
            new CountRow("ROUTINE", 0), new CountRow("URGENT", 0), new CountRow("EMERGENCY", 0));
    }

    @Test
    void nothingDecidedMeansNoRateRatherThanZeroOrNaN() {
        assertThat(ReportSummaryService.fulfilmentRate(0, 0)).isNull();
        assertThat(ReportSummaryService.fulfilmentRate(0, 2)).isEqualTo(0.0);
        assertThat(ReportSummaryService.fulfilmentRate(2, 2)).isEqualTo(1.0);
    }

    @Test
    void anEmptyRangeIsAllZerosWithNoRate() {
        ReportRange range = new ReportRange(LocalDate.of(1990, 1, 1), LocalDate.of(1990, 1, 31));
        when(requestRepository.countByStatusCreatedBetween(any(), any())).thenReturn(List.of());
        when(requestRepository.countByUrgencyCreatedBetween(any(), any())).thenReturn(List.of());
        when(requestRepository.countByBloodTypeCreatedBetween(any(), any())).thenReturn(List.of());

        RequestReport report = service.requests(range);

        assertThat(report.total()).isZero();
        assertThat(report.fulfilmentRate()).isNull();
        assertThat(report.byBloodType()).hasSize(8).allMatch(r -> r.count() == 0);
    }

    // ---------- donations ----------

    @Test
    void everyMonthInTheRangeAppearsAndEmptyOnesAreZero() {
        ReportRange range = new ReportRange(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 2));
        when(donationRepository.countMonthlyBetween(range.from(), range.to())).thenReturn(List.of(
            monthCount(LocalDate.of(2026, 1, 1), 4), monthCount(LocalDate.of(2026, 3, 1), 2)));
        when(donationRepository.countPerBankBetween(range.from(), range.to())).thenReturn(List.of());
        when(donationRepository.countPerBloodTypeBetween(range.from(), range.to())).thenReturn(List.of());

        DonationReport report = service.donations(range);

        assertThat(report.byMonth()).extracting(m -> m.month() + " " + m.label() + " " + m.count())
            .containsExactly("2026-01 Jan 2026 4", "2026-02 Feb 2026 0", "2026-03 Mar 2026 2", "2026-04 Apr 2026 0");
        assertThat(report.total()).isEqualTo(6);
    }

    // ---------- donors ----------

    @Test
    void eligibleIsCountedInTheDatabaseAgainstTheNinetyDayCutoff() {
        when(donorRepository.count()).thenReturn(10L);
        when(donorRepository.countEligibleBefore(LocalDate.of(2026, 7, 17))).thenReturn(7L);
        when(donorRepository.countByBloodType()).thenReturn(List.of(typeCount(BloodType.O_POSITIVE, 9), typeCount(null, 1)));

        DonorReport report = service.donors(TODAY);

        verify(donorRepository).countEligibleBefore(TODAY.minusDays(90));
        assertThat(report.eligible()).isEqualTo(7);
        assertThat(report.inRecovery()).isEqualTo(3);
        assertThat(report.byBloodType()).hasSize(9).last().isEqualTo(new CountRow("Unknown", 1));
    }

    // ---------- no personal data ----------

    @Test
    void noReportShapeHasAFieldForADonorsPersonalDetails() {
        List<String> forbidden = List.of("name", "fullname", "phone", "email", "address", "dateofbirth", "birth", "gender");
        for (Class<?> type : ReportDtos.class.getDeclaredClasses()) {
            for (RecordComponent component : type.getRecordComponents()) {
                String field = component.getName().toLowerCase(Locale.ROOT);
                // bankName is a facility's name, not a person's.
                if (field.equals("bankname")) continue;
                assertThat(forbidden).as(type.getSimpleName() + "." + component.getName())
                    .noneMatch(field::contains);
            }
        }
        // CountRow, StockGroupRow, StockBankRow, StockReport, RequestReport, MonthRow,
        // DonationReport, DonorReport — all scanned above; a new one must be too.
        assertThat(Arrays.stream(ReportDtos.class.getDeclaredClasses()).filter(Class::isRecord)).hasSize(8);
    }

    // ---------- projection stand-ins ----------

    private static StockStatus status(StockReport report, String group) {
        return report.groups().stream().filter(g -> g.bloodType().equals(group)).findFirst().orElseThrow().status();
    }

    private static BloodTypeCount typeCount(BloodType type, long total) {
        return new BloodTypeCount() {
            public BloodType getBloodType() { return type; }
            public long getTotal() { return total; }
        };
    }

    private static BloodRequestRepository.StatusCount statusCount(RequestStatus status, long total) {
        return new BloodRequestRepository.StatusCount() {
            public RequestStatus getStatus() { return status; }
            public long getTotal() { return total; }
        };
    }

    private static DonationRepository.MonthlyDonationCount monthCount(LocalDate bucket, long total) {
        return new DonationRepository.MonthlyDonationCount() {
            public LocalDate getBucket() { return bucket; }
            public long getTotal() { return total; }
        };
    }

    private static BloodBank bank(String name) {
        BloodBank bank = new BloodBank();
        bank.setId(UUID.randomUUID());
        bank.setName(name);
        return bank;
    }

    private static BankGroupUnits bankGroup(BloodBank bank, BloodType type, long units) {
        return new BankGroupUnits() {
            public UUID getBankId() { return bank.getId(); }
            public BloodType getBloodType() { return type; }
            public long getUsableUnits() { return units; }
        };
    }
}
