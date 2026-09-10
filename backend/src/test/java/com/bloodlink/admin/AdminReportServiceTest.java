package com.bloodlink.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bloodlink.admin.dto.MonthlyDonationTotal;
import com.bloodlink.admin.dto.RequestOutcomeResponse;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.BloodRequestRepository.StatusCount;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonationRepository.MonthlyDonationCount;

/**
 * Both reports share one rule this class exists to pin down: a month or a status
 * with nothing recorded against it must still appear, at zero, rather than be
 * missing from the response — the same "always present, zero when empty" contract
 * InventoryService already gives every blood group. Missing rows and empty rows
 * mean different things to a chart, and only one of them is true here.
 */
@ExtendWith(MockitoExtension.class)
class AdminReportServiceTest {

    @Mock private DonationRepository donationRepository;
    @Mock private BloodRequestRepository bloodRequestRepository;

    private AdminReportService service;

    @BeforeEach
    void setUp() {
        service = new AdminReportService(donationRepository, bloodRequestRepository);
    }

    /** A plain implementation rather than a Mockito mock: both projections are
     *  pure data with no behaviour to stub, so a real, tiny object is simpler
     *  and more honest here than mocking an interface that has nothing to fake. */
    private record FakeMonthlyDonationCount(LocalDate bucket, long total) implements MonthlyDonationCount {
        @Override public LocalDate getBucket() { return bucket; }
        @Override public long getTotal() { return total; }
    }

    private record FakeStatusCount(RequestStatus status, long total) implements StatusCount {
        @Override public RequestStatus getStatus() { return status; }
        @Override public long getTotal() { return total; }
    }

    private static MonthlyDonationCount row(LocalDate bucket, long total) {
        return new FakeMonthlyDonationCount(bucket, total);
    }

    private static StatusCount statusRow(RequestStatus status, long total) {
        return new FakeStatusCount(status, total);
    }

    // ---------- donation trends ----------

    @Test
    void sixConsecutiveMonthsAppearEvenWithNoDonationsAtAll() {
        when(donationRepository.countMonthlyFrom(any())).thenReturn(List.of());

        List<MonthlyDonationTotal> trend = service.donationTrends();

        assertThat(trend).hasSize(6);
        assertThat(trend).allSatisfy(month -> assertThat(month.totalDonations()).isZero());
    }

    @Test
    void aMonthsCountLandsAgainstTheRightMonthAndOthersStayZero() {
        YearMonth currentMonth = YearMonth.now();
        YearMonth startMonth = currentMonth.minusMonths(5);
        YearMonth middleMonth = startMonth.plusMonths(2);

        when(donationRepository.countMonthlyFrom(any()))
            .thenReturn(List.of(row(middleMonth.atDay(1), 17)));

        List<MonthlyDonationTotal> trend = service.donationTrends();

        assertThat(trend).hasSize(6);
        String expectedLabel = middleMonth.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        // Exactly one row carries the count; every other month is still present, at
        // zero, in oldest-first order.
        assertThat(trend.get(2).month()).isEqualTo(expectedLabel);
        assertThat(trend.get(2).totalDonations()).isEqualTo(17);
        assertThat(trend.stream().mapToLong(MonthlyDonationTotal::totalDonations).sum()).isEqualTo(17);
        assertThat(trend.get(0).month())
            .isEqualTo(startMonth.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
        assertThat(trend.get(5).month())
            .isEqualTo(currentMonth.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
    }

    // ---------- request outcomes ----------

    @Test
    void everyStatusIsZeroWhenNoRequestHasEverBeenRaised() {
        when(bloodRequestRepository.countGroupedByStatus()).thenReturn(List.of());

        RequestOutcomeResponse outcomes = service.requestOutcomes();

        assertThat(outcomes).isEqualTo(new RequestOutcomeResponse(0, 0, 0, 0, 0));
    }

    @Test
    void aStatusNoRequestHasReachedYetIsZeroNotAbsent() {
        // Nothing has ever been rejected on this deployment — REJECTED must still
        // read as zero, not disappear from the response.
        when(bloodRequestRepository.countGroupedByStatus()).thenReturn(List.of(
            statusRow(RequestStatus.PENDING, 3),
            statusRow(RequestStatus.APPROVED, 2),
            statusRow(RequestStatus.FULFILLED, 9)
        ));

        RequestOutcomeResponse outcomes = service.requestOutcomes();

        assertThat(outcomes.pending()).isEqualTo(3);
        assertThat(outcomes.approved()).isEqualTo(2);
        assertThat(outcomes.fulfilled()).isEqualTo(9);
        assertThat(outcomes.rejected()).isZero();
        assertThat(outcomes.total()).isEqualTo(14);
    }

    @Test
    void theTotalIsTheSumOfAllFourStatuses() {
        when(bloodRequestRepository.countGroupedByStatus()).thenReturn(List.of(
            statusRow(RequestStatus.PENDING, 1),
            statusRow(RequestStatus.APPROVED, 2),
            statusRow(RequestStatus.FULFILLED, 3),
            statusRow(RequestStatus.REJECTED, 4)
        ));

        assertThat(service.requestOutcomes().total()).isEqualTo(10);
    }
}
