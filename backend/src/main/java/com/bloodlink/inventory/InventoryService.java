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
import com.bloodlink.domain.AppealCooldown;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
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
 * Reads and adds to one blood bank's stock, which is held batch by batch with each
 * batch's own expiry date (Business Rule 24).
 *
 * Every figure this class reports counts USABLE units only, read through
 * {@link BatchStockService}; units in expired batches are reported separately and
 * never counted as available.
 *
 * "Own bank only" for BLOOD_BANK_STAFF is enforced here, not in SecurityConfig —
 * a URL rule can match a role, but only a lookup against the caller's own User
 * row can tell whether {bloodBankId} in the path is *their* bank.
 *
 * ADMIN may read every bank's stock and batches but change none of it: adding and
 * writing off stock are for the owning bank's own staff only.
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
    // Both only for the Send Appeal button's state on each row (Business Rule 12):
    // the repository supplies this bank's cooldown rows, the policy turns them and
    // current usable stock into the one answer the send endpoint would also give.
    private final AppealCooldownRepository appealCooldownRepository;
    private final AppealCooldownPolicy appealCooldownPolicy;

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
        AppealCooldownPolicy appealCooldownPolicy
    ) {
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
     * All eight blood groups' usable stock at one bank.
     * Always eight rows: a group this bank has never stocked comes back as a zero
     * row rather than being omitted, since both frontend screens render a fixed
     * eight-row table/chart.
     */
    @Transactional(readOnly = true)
    public List<InventoryItemResponse> listInventory(UUID bloodBankId) {
        requireBank(bloodBankId);
        Map<BloodType, GroupStock> stock = batchStockService.stockForBank(bloodBankId);

        // Every cooldown this bank holds, in one query rather than one per row —
        // the Send Appeal button's state (Business Rule 12) is derived per group
        // below, and eight lookups to draw one table would be seven too many.
        Map<BloodType, AppealCooldown> cooldownsByType = appealCooldownRepository
            .findByBloodBankId(bloodBankId).stream()
            .collect(Collectors.toMap(AppealCooldown::getBloodType, cooldown -> cooldown));

        // One instant for the whole table, so two rows can never be judged against
        // clock readings milliseconds apart — a cosmetic difference here, but the
        // kind that makes a failing test irreproducible.
        Instant now = Instant.now();

        return List.of(BloodType.values()).stream()
            .map(type -> toResponse(stock.get(type), cooldownsByType.get(type), now))
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
        // Summed from this bank's own contributions (Business Rule 20), not from the
        // size of requests it fulfilled: a request may now be filled by two banks,
        // and each bank's figure has to be the units that actually left its shelf.
        long unitsIssued = contributionRepository.sumUnitsForBankInRange(bloodBankId, monthStart, monthEnd);

        return new InventorySummaryResponse(
            bank.getId(), bank.getName(), totalUnits, expiringSoonUnits, criticalGroups,
            (int) unitsIssued, composition, expiredUnits, StockPolicy.EXPIRING_SOON_WINDOW_DAYS
        );
    }

    /**
     * "Add New Batch": records freshly received or tested units as a NEW batch with
     * its own expiry date. Business Rule 7's merge into one row, keeping the earlier
     * of two dates, no longer exists (Business Rule 24): each batch keeps the date it
     * arrived with, so a fresh batch can never inherit an older, possibly passed date.
     *
     * The expiry date must be today or later and at most 365 days away (StockPolicy);
     * anything else is a 400 on the expiryDate field, with nothing written.
     *
     * Active staff of this bank only; ADMIN is refused (requireStockChangeAccess).
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
     * Puts a recorded donation's units on the shelf as one batch linked to that
     * donation (DONATION source, donation_id unique: one donation, one batch). Called
     * by DonationService inside its own transaction, after it has decided which bank
     * the caller acts for (ActingBankResolver), so no second permission check is
     * needed here.
     *
     * The expiry date is checked against the same rule as Add New Batch; the caller
     * has normally checked it already, before writing the donation, so that a bad date
     * refuses the whole action before anything is written.
     */
    @Transactional
    public InventoryItemResponse receiveDonation(Donation donation, LocalDate expiryDate) {
        requireAcceptableExpiry(expiryDate);
        return receive(donation.getBloodBank(), BloodInventoryBatch.fromDonation(donation, expiryDate));
    }

    /**
     * Refuses an expiry date outside today .. today + 365 days with a 400 on the
     * expiryDate field. Public so Record Donation can check its own optional date
     * before writing anything.
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

        // Every path that changes a stock figure reports the change, so the low-stock
        // rule lives in one place rather than being re-derived per caller. Adding
        // units can only improve a level, so in practice this raises nothing; it is
        // here so the rule stays in one place should a future path lower a count.
        notificationService.notifyStockCrossing(bank, bloodType, usableBefore, usableAfter);

        // A new batch is the only way usable stock can rise, so this is the only place
        // an appeal cooldown can be recovered out of (Business Rule 12). If these units
        // take the group back over the threshold that triggered its cooldown, that
        // cooldown is finished for good. Stock falling by expiry at midnight is not an
        // event anything reacts to yet (a nightly job is future work).
        //
        // What comes back is whatever cooldown survives, which is exactly what the
        // returned row should report: the state this batch has just produced.
        AppealCooldown cooldown = appealCooldownPolicy.clearIfRecovered(bank.getId(), bloodType, usableAfter);

        // Read back through the same query every screen uses (it sees the batch just
        // saved: a query flushes pending changes first), so the response is exactly
        // what the next GET of the inventory would show.
        GroupStock groupStock = batchStockService.stockForBank(bank.getId()).get(bloodType);
        return toResponse(groupStock, cooldown, Instant.now());
    }

    // ---------- batch-level views: bank staff of that bank, and ADMIN ----------

    /**
     * One bank's live batches (any units left, expired ones included), optionally for
     * one blood group, in issue order. Hospitals and donors are refused before this is
     * reached (SecurityConfig); staff of another bank are refused here.
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
     * One batch and where its units went: issued to hospital requests, or written off.
     * A batch that belongs to another bank is a 404, exactly as one that does not exist,
     * so a bank cannot learn anything about another bank's stock by guessing ids.
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
     * The bank whose stock the caller is about to change — by Add New Batch or a
     * write-off — if it exists (404) and the caller may change it (403). Only active
     * BLOOD_BANK_STAFF of that very bank may: adding and discarding units are
     * physical acts on a bank's own shelf, so an ADMIN, who can see every bank's
     * stock, cannot change any of it (Stage 3b, the project owner's decision).
     *
     * ADMIN is refused here as well as by SecurityConfig's rule for /api/inventory
     * writes, so a future route or caller that skips the URL rule still cannot get
     * through. The role is checked before the bank is looked up, so the refusal
     * reveals nothing about which bank ids exist.
     *
     * Public so the write-off action (BatchWriteOffService) applies this same rule
     * rather than a copy of it: an access rule written twice is a rule that will one
     * day be fixed in one place and not the other.
     */
    public BloodBank requireStockChangeAccess(UUID bloodBankId, AuthenticatedUser principal) {
        if (principal.getRole() != Role.BLOOD_BANK_STAFF) {
            throw new AccessDeniedException("Only the blood bank's own staff can change its stock.");
        }
        BloodBank bank = requireBank(bloodBankId);
        User user = requireOwnBankStaff(bloodBankId, principal);
        // A suspended account's token is already refused on every request
        // (JwtAuthenticationFilter); this is the same rule once more, at the point
        // where stock actually changes.
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new AccessDeniedException("This account is suspended.");
        }
        return bank;
    }

    /**
     * Who may READ batch-level detail: ADMIN for any bank, BLOOD_BANK_STAFF only for
     * the bank on their own User row. Reading only — changing stock is
     * {@link #requireStockChangeAccess}, which ADMIN does not pass.
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
     * One table row: the usable stock figures, the Business Rule 3 badge judged on
     * them, and the Business Rule 12 state of its Send Appeal button.
     *
     * The last of those is asked of {@link AppealCooldownPolicy} rather than worked
     * out here, so this screen and the send endpoint cannot come to different
     * conclusions about whether an appeal is allowed.
     */
    private InventoryItemResponse toResponse(GroupStock stock, AppealCooldown cooldown, Instant now) {
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
            stock.batchCount()
        );
    }
}
