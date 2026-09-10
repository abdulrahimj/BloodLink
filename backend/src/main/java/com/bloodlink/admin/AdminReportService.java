package com.bloodlink.admin;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.admin.dto.MonthlyDonationTotal;
import com.bloodlink.admin.dto.RequestOutcomeResponse;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonationRepository.MonthlyDonationCount;

/**
 * The two aggregate reports behind the Admin Console's charts: donation volume
 * over time, and how blood requests have been resolved.
 *
 * Both are read-only, citywide, and answer a question no single dashboard
 * elsewhere in the system asks — the Blood Bank Dashboard cares about one bank's
 * donations, the Hospital Portal about one hospital's requests; only an
 * administrator needs the total across every facility, which is what makes this
 * its own service rather than a method bolted onto BloodBankService.
 */
@Service
public class AdminReportService {

    /** Matches the mockup's own "Last 6 months" framing for the trend chart. */
    private static final int TREND_MONTHS = 6;

    private final DonationRepository donationRepository;
    private final BloodRequestRepository bloodRequestRepository;

    AdminReportService(DonationRepository donationRepository, BloodRequestRepository bloodRequestRepository) {
        this.donationRepository = donationRepository;
        this.bloodRequestRepository = bloodRequestRepository;
    }

    /**
     * Donation counts for the six most recent calendar months, current month
     * included, oldest first.
     *
     * Every month in the window appears exactly once even if no donation was
     * recorded in it — the same "always present, zero when there is nothing"
     * rule InventoryService applies to a blood group no bank has ever stocked.
     * Without it, a quiet month would not shrink the bar to zero; it would
     * delete the month from the chart entirely, which reads as missing data
     * rather than as the true, uneventful answer.
     */
    @Transactional(readOnly = true)
    public List<MonthlyDonationTotal> donationTrends() {
        YearMonth currentMonth = YearMonth.now();
        YearMonth startMonth = currentMonth.minusMonths(TREND_MONTHS - 1L);

        Map<YearMonth, Long> countsByMonth = donationRepository.countMonthlyFrom(startMonth.atDay(1)).stream()
            .collect(Collectors.toMap(
                (MonthlyDonationCount row) -> YearMonth.from(row.getBucket()),
                MonthlyDonationCount::getTotal
            ));

        List<MonthlyDonationTotal> trend = new ArrayList<>(TREND_MONTHS);
        for (int i = 0; i < TREND_MONTHS; i++) {
            YearMonth month = startMonth.plusMonths(i);
            trend.add(new MonthlyDonationTotal(
                month.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                countsByMonth.getOrDefault(month, 0L)
            ));
        }
        return trend;
    }

    /**
     * How every blood request raised, citywide, across every hospital and
     * blood bank, has been resolved — or has not yet.
     *
     * Zero-filled the same way: a status nobody has reached yet (REJECTED, on a
     * deployment where nothing has ever been turned down) must still appear as
     * zero rather than be absent, or the donut and its legend would have fewer
     * than four slices and the percentages would not visibly account for 100%.
     */
    @Transactional(readOnly = true)
    public RequestOutcomeResponse requestOutcomes() {
        Map<RequestStatus, Long> counts = bloodRequestRepository.countGroupedByStatus().stream()
            .collect(Collectors.toMap(
                BloodRequestRepository.StatusCount::getStatus,
                BloodRequestRepository.StatusCount::getTotal
            ));

        long pending = counts.getOrDefault(RequestStatus.PENDING, 0L);
        long approved = counts.getOrDefault(RequestStatus.APPROVED, 0L);
        long fulfilled = counts.getOrDefault(RequestStatus.FULFILLED, 0L);
        long rejected = counts.getOrDefault(RequestStatus.REJECTED, 0L);

        return new RequestOutcomeResponse(pending, approved, fulfilled, rejected,
            pending + approved + fulfilled + rejected);
    }
}
