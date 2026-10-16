package com.bloodlink.admin.report;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.BloodTypeCount;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.stock.StockPolicy;

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
 *   <li>usable, expired and expiring soon: StockPolicy (Business Rule 24) and its
 *       28-day window, with "today" passed in;</li>
 *   <li>eligibility: {@link Donor#eligibilityCutoff} (Business Rule 2).</li>
 * </ul>
 * Read-only throughout, and nothing in any response identifies a donor.
 */
@Service
public class ReportSummaryService {

    private final BloodInventoryBatchRepository batchRepository;
    private final BloodBankRepository bloodBankRepository;
    private final BloodRequestRepository requestRepository;
    private final DonationRepository donationRepository;
    private final DonorRepository donorRepository;
    private final StockPolicy stockPolicy;

    ReportSummaryService(
        BloodInventoryBatchRepository batchRepository,
        BloodBankRepository bloodBankRepository,
        BloodRequestRepository requestRepository,
        DonationRepository donationRepository,
        DonorRepository donorRepository,
        StockPolicy stockPolicy
    ) {
        this.batchRepository = batchRepository;
        this.bloodBankRepository = bloodBankRepository;
        this.requestRepository = requestRepository;
        this.donationRepository = donationRepository;
        this.donorRepository = donorRepository;
        this.stockPolicy = stockPolicy;
    }

    /**
     * Section 1 — usable stock across every bank, as of {@code today} (Business Rules
     * 23 and 24).
     *
     * Only usable units count: units in batches past their expiry date are NOT stock,
     * exactly as every bank's own Inventory screen now counts them, and are reported
     * separately as awaiting discard ({@code pastExpiryUnits}). "Expiring soon" is
     * usable units expiring within StockPolicy's 28-day window, so it never includes
     * already-expired units either.
     *
     * Each blood group's citywide total is classified with Business Rule 3's own
     * thresholds. Each bank's Critical/Low counts judge its eight groups one by one with
     * the same StockStatus rule its Inventory screen uses, on usable units summed per
     * (bank, group) by the database; a group with no usable stock is zero units, and so
     * Critical, as that screen shows it.
     */
    /** The date the stock section is reported as of: today in UTC, from StockPolicy's
     *  clock, the same "today" every Inventory screen judges expiry by. */
    public LocalDate stockDate() {
        return stockPolicy.today();
    }

    @Transactional(readOnly = true)
    public StockReport stock(LocalDate today) {
        Map<BloodType, Long> unitsByGroup = byBloodType(batchRepository.sumUsableUnitsByBloodType(today));
        List<StockGroupRow> groups = Arrays.stream(BloodType.values())
            .map(type -> {
                long units = unitsByGroup.getOrDefault(type, 0L);
                return new StockGroupRow(type.getLabel(), units, StockStatus.fromUnits((int) units));
            })
            .toList();
        long totalUnits = groups.stream().mapToLong(StockGroupRow::units).sum();

        // Usable units per (bank, group), summed in the database; at most 8 per bank.
        Map<UUID, Map<BloodType, Long>> unitsByBank = batchRepository.sumUsableUnitsByBankAndBloodType(today).stream()
            .collect(Collectors.groupingBy(BloodInventoryBatchRepository.BankGroupUnits::getBankId,
                Collectors.toMap(BloodInventoryBatchRepository.BankGroupUnits::getBloodType,
                    BloodInventoryBatchRepository.BankGroupUnits::getUsableUnits)));

        // Every bank is listed, a bank with no stock at all as eight Critical groups.
        List<StockBankRow> banks = bloodBankRepository.findAllByOrderByNameAsc().stream()
            .map(bank -> {
                Map<BloodType, Long> groupUnits = unitsByBank.getOrDefault(bank.getId(), Map.of());
                long bankTotal = 0;
                int critical = 0;
                int low = 0;
                for (BloodType type : BloodType.values()) {
                    long units = groupUnits.getOrDefault(type, 0L);
                    bankTotal += units;
                    StockStatus status = StockStatus.fromUnits((int) units);
                    if (status == StockStatus.CRITICAL) {
                        critical++;
                    } else if (status == StockStatus.LOW) {
                        low++;
                    }
                }
                return new StockBankRow(bank.getName(), bankTotal, critical, low);
            })
            .toList();

        return new StockReport(
            totalUnits,
            batchRepository.sumUsableUnitsExpiringOnOrBefore(today, StockPolicy.expiringSoonCutoff(today)),
            batchRepository.sumExpiredUnits(today),
            StockPolicy.EXPIRING_SOON_WINDOW_DAYS,
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
