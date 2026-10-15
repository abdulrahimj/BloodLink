package com.bloodlink.admin.report;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.admin.report.dto.ReportDtos.CountRow;
import com.bloodlink.admin.report.dto.ReportDtos.DonationReport;
import com.bloodlink.admin.report.dto.ReportDtos.DonorReport;
import com.bloodlink.admin.report.dto.ReportDtos.MonthRow;
import com.bloodlink.admin.report.dto.ReportDtos.RequestReport;
import com.bloodlink.admin.report.dto.ReportDtos.StockBankRow;
import com.bloodlink.admin.report.dto.ReportDtos.StockGroupRow;
import com.bloodlink.admin.report.dto.ReportDtos.StockReport;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.inventory.InventoryService;
import com.bloodlink.repository.BloodInventoryRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.BloodTypeCount;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;

/**
 * The four sections of the Admin Reports page (Business Rule 23): stock, requests,
 * donations and donors, citywide.
 *
 * Every count and sum comes from an aggregate query (COUNT / SUM / GROUP BY); this
 * class never loads rows to count them. What it does in Java is fill in the gaps an
 * aggregate leaves — a blood group, status, urgency or month with nothing in it comes
 * back absent and is reported as zero, so every table always shows every row — and
 * apply the existing rules to those few aggregate numbers:
 * <ul>
 *   <li>stock status: {@link StockStatus#fromUnits} and its thresholds (Business Rule 3);</li>
 *   <li>expiring soon: InventoryService's 28-day window, its "on or before" test;</li>
 *   <li>eligibility: {@link Donor#eligibilityCutoff} (Business Rule 2).</li>
 * </ul>
 * Read-only throughout, and nothing in any response identifies a donor.
 */
@Service
public class ReportSummaryService {

    private static final int BLOOD_GROUPS = BloodType.values().length;

    private final BloodInventoryRepository inventoryRepository;
    private final BloodRequestRepository requestRepository;
    private final DonationRepository donationRepository;
    private final DonorRepository donorRepository;

    ReportSummaryService(
        BloodInventoryRepository inventoryRepository,
        BloodRequestRepository requestRepository,
        DonationRepository donationRepository,
        DonorRepository donorRepository
    ) {
        this.inventoryRepository = inventoryRepository;
        this.requestRepository = requestRepository;
        this.donationRepository = donationRepository;
        this.donorRepository = donorRepository;
    }

    /**
     * Section 1 — stock across every bank, as of {@code today}.
     *
     * Each blood group's citywide total is classified with Business Rule 3's own
     * thresholds; each bank's Critical/Low counts use them too, in the database, and a
     * group a bank has no stock row for counts as Critical (zero units), exactly as
     * that bank's inventory page shows it. Units past their expiry date are counted as
     * stock, as InventoryService.getSummary counts them; how many is reported separately.
     */
    @Transactional(readOnly = true)
    public StockReport stock(LocalDate today) {
        Map<BloodType, Long> unitsByGroup = byBloodType(inventoryRepository.sumUnitsByBloodType());
        List<StockGroupRow> groups = Arrays.stream(BloodType.values())
            .map(type -> {
                long units = unitsByGroup.getOrDefault(type, 0L);
                return new StockGroupRow(type.getLabel(), units, StockStatus.fromUnits((int) units));
            })
            .toList();
        long totalUnits = groups.stream().mapToLong(StockGroupRow::units).sum();

        List<StockBankRow> banks = inventoryRepository
            .summariseStockByBank(StockStatus.CRITICAL_BELOW, StockStatus.LOW_BELOW).stream()
            .map(row -> new StockBankRow(
                row.getBankName(),
                row.getTotalUnits(),
                (int) (row.getCriticalRows() + (BLOOD_GROUPS - row.getStockedGroups())),
                (int) row.getLowRows()))
            .toList();

        return new StockReport(
            totalUnits,
            inventoryRepository.sumUnitsExpiringOnOrBefore(today.plusDays(InventoryService.EXPIRING_SOON_WINDOW_DAYS)),
            inventoryRepository.sumUnitsPastExpiry(today),
            InventoryService.EXPIRING_SOON_WINDOW_DAYS,
            groups,
            banks
        );
    }

    /**
     * Section 2 — hospital requests raised within the range.
     *
     * "Decided" means FULFILLED or REJECTED: a PENDING or PARTIALLY_FULFILLED request
     * is still open (Business Rule 20) and says nothing yet about success. The
     * fulfilment rate is fulfilled / decided, and null when nothing was decided — the
     * page shows "No data" rather than a misleading 0% or a division by zero.
     */
    @Transactional(readOnly = true)
    public RequestReport requests(ReportRange range) {
        Map<RequestStatus, Long> byStatus = requestRepository
            .countByStatusCreatedBetween(range.startInstant(), range.endInstantExclusive()).stream()
            .collect(Collectors.toMap(BloodRequestRepository.StatusCount::getStatus,
                BloodRequestRepository.StatusCount::getTotal));
        Map<UrgencyLevel, Long> byUrgency = requestRepository
            .countByUrgencyCreatedBetween(range.startInstant(), range.endInstantExclusive()).stream()
            .collect(Collectors.toMap(BloodRequestRepository.UrgencyCount::getUrgency,
                BloodRequestRepository.UrgencyCount::getTotal));

        long fulfilled = byStatus.getOrDefault(RequestStatus.FULFILLED, 0L);
        long decided = fulfilled + byStatus.getOrDefault(RequestStatus.REJECTED, 0L);

        return new RequestReport(
            range.from(),
            range.to(),
            byStatus.values().stream().mapToLong(Long::longValue).sum(),
            zeroFilled(RequestStatus.values(), RequestStatus::name, byStatus),
            decided,
            fulfilmentRate(fulfilled, decided),
            requestRepository.countFilledByMoreThanOneBankCreatedBetween(range.startInstant(), range.endInstantExclusive()),
            bloodTypeRows(requestRepository.countByBloodTypeCreatedBetween(range.startInstant(), range.endInstantExclusive()), false),
            zeroFilled(UrgencyLevel.values(), UrgencyLevel::name, byUrgency)
        );
    }

    /** Fulfilled as a share of decided, or null when nothing was decided. */
    static Double fulfilmentRate(long fulfilled, long decided) {
        return decided == 0 ? null : (double) fulfilled / decided;
    }

    /**
     * Section 3 — donations recorded within the range: per month (every month in the
     * range, zero-filled), per blood bank (every bank) and per tested blood group.
     */
    @Transactional(readOnly = true)
    public DonationReport donations(ReportRange range) {
        Map<YearMonth, Long> perMonth = donationRepository.countMonthlyBetween(range.from(), range.to()).stream()
            .collect(Collectors.toMap(row -> YearMonth.from(row.getBucket()), DonationRepository.MonthlyDonationCount::getTotal));

        List<MonthRow> months = range.months().stream()
            .map(month -> new MonthRow(
                month.toString(),
                month.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + month.getYear(),
                perMonth.getOrDefault(month, 0L)))
            .toList();

        List<CountRow> perBank = donationRepository.countPerBankBetween(range.from(), range.to()).stream()
            .map(row -> new CountRow(row.getLabel(), row.getTotal()))
            .toList();

        return new DonationReport(
            range.from(),
            range.to(),
            months.stream().mapToLong(MonthRow::count).sum(),
            months,
            perBank,
            bloodTypeRows(donationRepository.countPerBloodTypeBetween(range.from(), range.to()), false)
        );
    }

    /**
     * Section 4 — registered donors (every Donor row) and how many can donate today.
     * {@code eligible} is counted in the database against {@link Donor#eligibilityCutoff}
     * for today; everyone else is inside the 90-day recovery period.
     */
    @Transactional(readOnly = true)
    public DonorReport donors(LocalDate today) {
        long total = donorRepository.count();
        long eligible = donorRepository.countEligibleBefore(Donor.eligibilityCutoff(today));
        return new DonorReport(total, eligible, total - eligible, bloodTypeRows(donorRepository.countByBloodType(), true));
    }

    // ---------- filling the gaps an aggregate leaves ----------

    private static Map<BloodType, Long> byBloodType(List<BloodTypeCount> rows) {
        return rows.stream()
            .filter(row -> row.getBloodType() != null)
            .collect(Collectors.toMap(BloodTypeCount::getBloodType, BloodTypeCount::getTotal));
    }

    /** All eight groups in their usual order, zero where absent; plus "Unknown" for
     *  donors who have not been typed yet, when {@code withUnknown}. */
    static List<CountRow> bloodTypeRows(List<BloodTypeCount> rows, boolean withUnknown) {
        Map<BloodType, Long> counts = byBloodType(rows);
        Stream<CountRow> groups = Arrays.stream(BloodType.values())
            .map(type -> new CountRow(type.getLabel(), counts.getOrDefault(type, 0L)));
        if (!withUnknown) {
            return groups.toList();
        }
        long unknown = rows.stream().filter(row -> row.getBloodType() == null).mapToLong(BloodTypeCount::getTotal).sum();
        return Stream.concat(groups, Stream.of(new CountRow("Unknown", unknown))).toList();
    }

    private static <E> List<CountRow> zeroFilled(E[] values, Function<E, String> label, Map<E, Long> counts) {
        return Arrays.stream(values).map(value -> new CountRow(label.apply(value), counts.getOrDefault(value, 0L))).toList();
    }
}
