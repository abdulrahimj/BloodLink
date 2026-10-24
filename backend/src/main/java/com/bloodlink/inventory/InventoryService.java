package com.bloodlink.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.appeal.AppealCooldownPolicy;
import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.AppealCooldown;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.BatchHistoryResponse;
import com.bloodlink.inventory.dto.BatchResponse;
import com.bloodlink.inventory.dto.CompositionSlice;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.inventory.dto.InventorySummaryResponse;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.AppealCooldownRepository;
import com.bloodlink.repository.BatchWriteOffRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.ContributionAllocationRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.stock.BatchStockService;
import com.bloodlink.stock.BatchStockService.GroupStock;
import com.bloodlink.stock.StockPolicy;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Reads and adds to one bank's batch-held stock (Business Rule 24), counting usable units only.
 * Own-bank-only for staff is enforced here; ADMIN may read every bank's stock but change none of it.
 */
@Service
public class InventoryService {

    private final BloodBankRepository bloodBankRepository;
    private final BloodInventoryBatchRepository batchRepository;
    private final ContributionAllocationRepository allocationRepository;
    private final BatchWriteOffRepository writeOffRepository;
    private final BatchStockService batchStockService;
    private final StockPolicy stockPolicy;
    private final UserRepository userRepository;
    private final BloodRequestContributionRepository contributionRepository;
    private final NotificationService notificationService;
    // For each row's Send Appeal state (Business Rule 12): the bank's cooldown rows and the shared policy.
    private final AppealCooldownRepository appealCooldownRepository;
    private final AppealCooldownPolicy appealCooldownPolicy;
    /** Owns the "who may change stock" rule shared with Record Donation and
     *  contributions (resolveForStockChange). */
    private final ActingBankResolver actingBankResolver;

    InventoryService(
        BloodBankRepository bloodBankRepository,
        BloodInventoryBatchRepository batchRepository,
        ContributionAllocationRepository allocationRepository,
        BatchWriteOffRepository writeOffRepository,
        BatchStockService batchStockService,
        StockPolicy stockPolicy,
        UserRepository userRepository,
        BloodRequestContributionRepository contributionRepository,
        NotificationService notificationService,
        AppealCooldownRepository appealCooldownRepository,
        AppealCooldownPolicy appealCooldownPolicy,
        ActingBankResolver actingBankResolver
    ) {
        this.actingBankResolver = actingBankResolver;
        this.bloodBankRepository = bloodBankRepository;
        this.batchRepository = batchRepository;
        this.allocationRepository = allocationRepository;
        this.writeOffRepository = writeOffRepository;
        this.batchStockService = batchStockService;
        this.stockPolicy = stockPolicy;
        this.userRepository = userRepository;
        this.contributionRepository = contributionRepository;
        this.notificationService = notificationService;
        this.appealCooldownRepository = appealCooldownRepository;
        this.appealCooldownPolicy = appealCooldownPolicy;
    }

    /**
     * All eight groups' usable stock at one bank; a never-stocked group comes back as a zero row.
     */
    @Transactional(readOnly = true)
    public List<InventoryItemResponse> listInventory(UUID bloodBankId) {
        requireBank(bloodBankId);
        Map<BloodType, GroupStock> stock = batchStockService.stockForBank(bloodBankId);

        // Every cooldown this bank holds, in one query rather than one per row.
        Map<BloodType, AppealCooldown> cooldownsByType = appealCooldownRepository
            .findByBloodBankId(bloodBankId).stream()
            .collect(Collectors.toMap(AppealCooldown::getBloodType, cooldown -> cooldown));
        // When each group's latest donor-accepted appeal went out, so a row can say "Donor Responded".
        Map<BloodType, Instant> acceptedAppealTimes = notificationService.acceptedAppealTimes(bloodBankId);

        // One instant for the whole table, so every row is judged against the same clock reading.
        Instant now = Instant.now();

        return List.of(BloodType.values()).stream()
            .map(type -> toResponse(stock.get(type), cooldownsByType.get(type), now, acceptedAppealTimes.get(type)))
            .toList();
    }

    /**
     * The stat cards and charts on the Blood Inventory detail page. Every unit figure
     * is usable stock; expired units are counted on their own, in expiredUnits.
     */
    @Transactional(readOnly = true)
    public InventorySummaryResponse getSummary(UUID bloodBankId) {
        BloodBank bank = requireBank(bloodBankId);
        List<InventoryItemResponse> items = listInventory(bloodBankId);

        int totalUnits = 0;
        int expiringSoonUnits = 0;
        int expiredUnits = 0;
        int criticalGroups = 0;
        Map<StockStatus, Integer> compositionUnits = new EnumMap<>(StockStatus.class);
        for (StockStatus status : StockStatus.values()) {
            compositionUnits.put(status, 0);
        }

        for (InventoryItemResponse item : items) {
            totalUnits += item.unitsAvailable();
            // Counted batch by batch, so only the units that really expire soon are
            // included — not the whole group because its earliest batch does.
            expiringSoonUnits += item.expiringSoonUnits();
            expiredUnits += item.expiredUnits();
            compositionUnits.merge(item.status(), item.unitsAvailable(), Integer::sum);
            if (item.status() == StockStatus.CRITICAL) {
                criticalGroups++;
            }
        }

        List<CompositionSlice> composition = compositionUnits.entrySet().stream()
            .map(entry -> new CompositionSlice(entry.getKey(), entry.getValue()))
            .toList();

        YearMonth thisMonth = YearMonth.now();
        Instant monthStart = thisMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant monthEnd = thisMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        // Summed from this bank's own contributions (Business Rule 20),
        // so it counts the units that actually left its shelf.
        long unitsIssued = contributionRepository.sumUnitsForBankInRange(bloodBankId, monthStart, monthEnd);

        return new InventorySummaryResponse(
            bank.getId(), bank.getName(), totalUnits, expiringSoonUnits, criticalGroups,
            (int) unitsIssued, composition, expiredUnits, StockPolicy.EXPIRING_SOON_WINDOW_DAYS
        );
    }

    /**
     * "Add New Batch": a NEW batch with its own expiry date, never merged (Business Rule 24).
     * Expiry must be today to 365 days ahead (400 otherwise); own-bank active staff only.
     */
    @Transactional
    public InventoryItemResponse addBatch(
        UUID bloodBankId, String bloodTypeLabel, AddBatchRequest request, AuthenticatedUser principal
    ) {
        BloodBank bank = requireStockChangeAccess(bloodBankId, principal);
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        BloodType bloodType = BloodType.fromLabel(bloodTypeLabel);
        requireAcceptableExpiry(request.expiryDate());

        return receive(bank, BloodInventoryBatch.manual(bank, bloodType, request.unitsToAdd(), request.expiryDate()));
    }

    /**
     * Puts a donation's units on the shelf as one linked DONATION batch, inside DonationService's transaction
     * (which already resolved the bank); expiry is checked against the Add New Batch rule.
     */
    @Transactional
    public InventoryItemResponse receiveDonation(Donation donation, LocalDate expiryDate) {
        requireAcceptableExpiry(expiryDate);
        return receive(donation.getBloodBank(), BloodInventoryBatch.fromDonation(donation, expiryDate));
    }

    /**
     * Refuses an expiry outside today to today + 365 with a 400 on expiryDate;
     * public so Record Donation can check before writing.
     */
    public void requireAcceptableExpiry(LocalDate expiryDate) {
        stockPolicy.problemWithEnteredExpiry(expiryDate).ifPresent(problem -> {
            throw new InvalidFieldException("expiryDate", problem);
        });
    }

    /**
     * Saves a new batch and runs what follows from stock rising, for both ways units
     * arrive (Add New Batch and Record Donation).
     */
    private InventoryItemResponse receive(BloodBank bank, BloodInventoryBatch batch) {
        BloodType bloodType = batch.getBloodType();
        int usableBefore = batchStockService.usableUnits(bank.getId(), bloodType);
        batchRepository.save(batch);
        // The new batch's date was checked to be today or later, so all of its units
        // are usable now.
        int usableAfter = usableBefore + batch.getUnitsReceived();

        // Every stock change reports to the one low-stock rule; adding units cannot lower a level,
        // so in practice this raises nothing.
        notificationService.notifyStockCrossing(bank, bloodType, usableBefore, usableAfter);

        // A new batch is the only way usable stock rises, so the only place a cooldown is cleared (Business Rule 12).
        // Returns whichever cooldown survives, which is what the row reports.
        AppealCooldown cooldown = appealCooldownPolicy.clearIfRecovered(bank.getId(), bloodType, usableAfter);

        // Read back through the same query every screen uses, so the response matches the next GET.
        GroupStock groupStock = batchStockService.stockForBank(bank.getId()).get(bloodType);
        // Only a surviving cooldown can be marked accepted, so skip the lookup when there is none.
        Instant acceptedAt = cooldown == null
            ? null
            : notificationService.acceptedAppealTimes(bank.getId()).get(bloodType);
        return toResponse(groupStock, cooldown, Instant.now(), acceptedAt);
    }

    // ---------- batch-level views: bank staff of that bank, and ADMIN ----------

    /**
     * One bank's live batches (expired included), optionally for one group, in issue order;
     * staff of another bank are refused here.
     */
    @Transactional(readOnly = true)
    public List<BatchResponse> listBatches(UUID bloodBankId, String bloodTypeLabel, AuthenticatedUser principal) {
        requireBank(bloodBankId);
        assertCanView(bloodBankId, principal);
        List<BloodInventoryBatch> batches = bloodTypeLabel == null || bloodTypeLabel.isBlank()
            ? batchRepository.findLiveForBank(bloodBankId)
            // Propagates IllegalArgumentException for an unrecognised label -> 400.
            : batchRepository.findLiveForBankAndBloodType(bloodBankId, BloodType.fromLabel(bloodTypeLabel.trim()));
        LocalDate today = stockPolicy.today();
        return batches.stream().map(batch -> BatchResponse.from(batch, today)).toList();
    }

    /**
     * One batch and where its units went (issued or written off);
     * another bank's batch is a 404, like a missing one.
     */
    @Transactional(readOnly = true)
    public BatchHistoryResponse batchHistory(UUID bloodBankId, UUID batchId, AuthenticatedUser principal) {
        requireBank(bloodBankId);
        assertCanView(bloodBankId, principal);
        BloodInventoryBatch batch = batchRepository.findById(batchId)
            .filter(found -> found.getBloodBank().getId().equals(bloodBankId))
            .orElseThrow(() -> new NotFoundException("No batch found for this id at this blood bank."));

        List<BatchHistoryResponse.IssueEntry> issues = allocationRepository.findForBatch(batchId).stream()
            .map(allocation -> new BatchHistoryResponse.IssueEntry(
                allocation.getContribution().getId(),
                allocation.getContribution().getBloodRequest().getId(),
                allocation.getContribution().getBloodRequest().getHospital().getName(),
                allocation.getUnitsAllocated(),
                allocation.getContribution().getContributedAt()))
            .toList();
        List<BatchHistoryResponse.WriteOffEntry> writeOffs = writeOffRepository.findForBatch(batchId).stream()
            .map(writeOff -> new BatchHistoryResponse.WriteOffEntry(
                writeOff.getId(),
                writeOff.getUnits(),
                writeOff.getReason(),
                writeOff.getNote(),
                writeOff.getWrittenOffBy().getFullName(),
                writeOff.getWrittenOffAt()))
            .toList();

        return new BatchHistoryResponse(
            BatchResponse.from(batch, stockPolicy.today()),
            issues.stream().mapToInt(BatchHistoryResponse.IssueEntry::units).sum(),
            writeOffs.stream().mapToInt(BatchHistoryResponse.WriteOffEntry::units).sum(),
            issues,
            writeOffs
        );
    }

    // ---------- helpers ----------

    /**
     * The bank whose stock the caller is changing (Add New Batch, write-off): 404 if missing, 403 unless the caller
     * is its own active staff, never ADMIN (Stage 3b). The role check is resolveForStockChange; role first, then bank.
     */
    public BloodBank requireStockChangeAccess(UUID bloodBankId, AuthenticatedUser principal) {
        actingBankResolver.refuseUnlessBankStaff(principal, CHANGE_STOCK);
        BloodBank bank = requireBank(bloodBankId);
        BloodBank own = actingBankResolver.resolveForStockChange(principal, CHANGE_STOCK);
        if (!own.getId().equals(bloodBankId)) {
            throw new AccessDeniedException("This account is not assigned to that blood bank.");
        }
        return bank;
    }

    /** Completes "Only the blood bank's own staff can ..." for the two stock actions here. */
    private static final String CHANGE_STOCK = "change its stock";

    /**
     * Who may read batch detail: ADMIN for any bank, staff only their own; changing stock is requireStockChangeAccess.
     */
    private void assertCanView(UUID bloodBankId, AuthenticatedUser principal) {
        if (principal.getRole() == Role.ADMIN) {
            return;
        }
        requireOwnBankStaff(bloodBankId, principal);
    }

    /**
     * The caller's own User row, provided it is assigned to this bank — null (no
     * admin has assigned one yet) means access to none.
     */
    private User requireOwnBankStaff(UUID bloodBankId, AuthenticatedUser principal) {
        User user = userRepository.findById(principal.getId())
            .orElseThrow(() -> new NotFoundException("Account not found."));
        boolean ownsThisBank = user.getBloodBank() != null && user.getBloodBank().getId().equals(bloodBankId);
        if (!ownsThisBank) {
            throw new AccessDeniedException("This account is not assigned to that blood bank.");
        }
        return user;
    }

    private BloodBank requireBank(UUID bloodBankId) {
        return bloodBankRepository.findById(bloodBankId)
            .orElseThrow(() -> new NotFoundException("No blood bank found for this id."));
    }

    /**
     * One table row: usable figures, the Business Rule 3 badge, and the Send Appeal state from AppealCooldownPolicy,
     * so this screen and the send endpoint always agree. Accepted = a donor accepted an appeal sent in this cooldown.
     */
    private InventoryItemResponse toResponse(
        GroupStock stock, AppealCooldown cooldown, Instant now, Instant acceptedAppealAt
    ) {
        int usable = stock.usableUnits();
        return new InventoryItemResponse(
            stock.bloodType().getLabel(),
            usable,
            stock.earliestUsableExpiry(),
            stock.lastUpdated(),
            StockStatus.fromUnits(usable),
            appealCooldownPolicy.evaluate(usable, cooldown, now),
            stock.expiringSoonUnits(),
            stock.expiredUnits(),
            stock.batchCount(),
            cooldown != null && acceptedAppealAt != null && !acceptedAppealAt.isBefore(cooldown.getLastSentAt())
        );
    }
}
