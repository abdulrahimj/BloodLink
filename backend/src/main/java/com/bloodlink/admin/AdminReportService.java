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
import com.bloodlink.admin.dto.UserCountsResponse;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonationRepository.MonthlyDonationCount;
import com.bloodlink.repository.UserRepository;

/**
 * The two citywide aggregates behind the Admin Console's charts: donation volume over time and request outcomes.
 * Its own service because only an administrator needs totals across every facility.
 */
@Service
public class AdminReportService {

    /** Matches the mockup's own "Last 6 months" framing for the trend chart. */
    private static final int TREND_MONTHS = 6;

    private final DonationRepository donationRepository;
    private final BloodRequestRepository bloodRequestRepository;
    private final UserRepository userRepository;

    AdminReportService(
        DonationRepository donationRepository,
        BloodRequestRepository bloodRequestRepository,
        UserRepository userRepository
    ) {
        this.donationRepository = donationRepository;
        this.bloodRequestRepository = bloodRequestRepository;
        this.userRepository = userRepository;
    }

    /**
     * Donation counts for the six most recent calendar months, oldest first, current month included.
     * Every month appears, zero when empty, so a quiet month shows as zero rather than vanishing from the chart.
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
     * How every request citywide has been resolved, with every status zero-filled
     * so the donut always has all five slices and accounts for 100%.
     */
    @Transactional(readOnly = true)
    public RequestOutcomeResponse requestOutcomes() {
        Map<RequestStatus, Long> counts = bloodRequestRepository.countGroupedByStatus().stream()
            .collect(Collectors.toMap(
                BloodRequestRepository.StatusCount::getStatus,
                BloodRequestRepository.StatusCount::getTotal
            ));

        long pending = counts.getOrDefault(RequestStatus.PENDING, 0L);
        // The slice APPROVED used to occupy: "part way there" now several banks can fill a request (Business Rule 20).
        long partiallyFulfilled = counts.getOrDefault(RequestStatus.PARTIALLY_FULFILLED, 0L);
        long fulfilled = counts.getOrDefault(RequestStatus.FULFILLED, 0L);
        long rejected = counts.getOrDefault(RequestStatus.REJECTED, 0L);
        long cancelled = counts.getOrDefault(RequestStatus.CANCELLED, 0L);

        return new RequestOutcomeResponse(pending, partiallyFulfilled, fulfilled, rejected, cancelled,
            pending + partiallyFulfilled + fulfilled + rejected + cancelled);
    }

    /**
     * Account counts in total and per role from one grouped query, zero-filled
     * so the console's cards and role buttons always have a number.
     */
    @Transactional(readOnly = true)
    public UserCountsResponse userCounts() {
        Map<Role, Long> counts = userRepository.countGroupedByRole().stream()
            .collect(Collectors.toMap(UserRepository.RoleCount::getRole, UserRepository.RoleCount::getTotal));

        long donors = counts.getOrDefault(Role.DONOR, 0L);
        long hospitalStaff = counts.getOrDefault(Role.HOSPITAL_STAFF, 0L);
        long bloodBankStaff = counts.getOrDefault(Role.BLOOD_BANK_STAFF, 0L);
        long admins = counts.getOrDefault(Role.ADMIN, 0L);

        return new UserCountsResponse(
            donors + hospitalStaff + bloodBankStaff + admins, donors, hospitalStaff, bloodBankStaff, admins);
    }
}
