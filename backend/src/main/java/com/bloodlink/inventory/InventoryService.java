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
import com.bloodlink.domain.BloodInventory;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.CompositionSlice;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.inventory.dto.InventorySummaryResponse;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.AppealCooldownRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryRepository;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.NotFoundException;

/**
 * Reads and updates one blood bank's stock.
 *
 * "Own bank only" for BLOOD_BANK_STAFF is enforced here, not in SecurityConfig —
 * a URL rule can match a role, but only a lookup against the caller's own User
 * row can tell whether {bloodBankId} in the path is *their* bank.
 */
@Service
public class InventoryService {

    /** Business Rule 3's "within 4 weeks" window for the Expiring Soon stat. */
    private static final int EXPIRING_SOON_WINDOW_DAYS = 28;

    private final BloodBankRepository bloodBankRepository;
    private final BloodInventoryRepository bloodInventoryRepository;
    private final UserRepository userRepository;
    private final BloodRequestContributionRepository contributionRepository;
    private final NotificationService notificationService;
    // Both only for the Send Appeal button's state on each row (Business Rule 12):
    // the repository supplies this bank's cooldown rows, the policy turns them and
    // current stock into the one answer the send endpoint would also give.
    private final AppealCooldownRepository appealCooldownRepository;
    private final AppealCooldownPolicy appealCooldownPolicy;

    InventoryService(
        BloodBankRepository bloodBankRepository,
        BloodInventoryRepository bloodInventoryRepository,
        UserRepository userRepository,
        BloodRequestContributionRepository contributionRepository,
        NotificationService notificationService,
        AppealCooldownRepository appealCooldownRepository,
        AppealCooldownPolicy appealCooldownPolicy
    ) {
        this.bloodBankRepository = bloodBankRepository;
        this.bloodInventoryRepository = bloodInventoryRepository;
        this.userRepository = userRepository;
        this.contributionRepository = contributionRepository;
        this.notificationService = notificationService;
        this.appealCooldownRepository = appealCooldownRepository;
        this.appealCooldownPolicy = appealCooldownPolicy;
    }

    /**
     * All eight blood groups' stock at one bank.
     * Always eight rows: a group this bank has never stocked comes back as a zero
     * row rather than being omitted, since both frontend screens render a fixed
     * eight-row table/chart.
     */
    @Transactional(readOnly = true)
    public List<InventoryItemResponse> listInventory(UUID bloodBankId) {
        requireBank(bloodBankId);
        Map<BloodType, BloodInventory> byType = indexByType(bloodInventoryRepository.findByBloodBankId(bloodBankId));

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
            .map(type -> toResponse(type, byType.get(type), cooldownsByType.get(type), now))
            .toList();
    }

    /** The stat cards and charts on the Blood Inventory detail page. */
    @Transactional(readOnly = true)
    public InventorySummaryResponse getSummary(UUID bloodBankId) {
        BloodBank bank = requireBank(bloodBankId);
        List<InventoryItemResponse> items = listInventory(bloodBankId);
        LocalDate expiringSoonCutoff = LocalDate.now().plusDays(EXPIRING_SOON_WINDOW_DAYS);

        int totalUnits = 0;
        int expiringSoonUnits = 0;
        int criticalGroups = 0;
        Map<StockStatus, Integer> compositionUnits = new EnumMap<>(StockStatus.class);
        for (StockStatus status : StockStatus.values()) {
            compositionUnits.put(status, 0);
        }

        for (InventoryItemResponse item : items) {
            totalUnits += item.unitsAvailable();
            compositionUnits.merge(item.status(), item.unitsAvailable(), Integer::sum);
            if (item.status() == StockStatus.CRITICAL) {
                criticalGroups++;
            }
            // A row with no expiry (never stocked) has nothing to expire.
            if (item.expiryDate() != null && !item.expiryDate().isAfter(expiringSoonCutoff)) {
                expiringSoonUnits += item.unitsAvailable();
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
            (int) unitsIssued, composition
        );
    }

    /**
     * "Add New Batch": adds freshly received/tested units to a (bank, group) row,
     * creating the row if this bank has never stocked that group before.
     *
     * The single expiry_date column has to represent the whole aggregate after this
     * batch is folded in, so it is kept at the *earliest* of the two dates — the
     * soonest-expiring stock is the one staff need to see and act on, and silently
     * losing track of it by overwriting with the newest batch's date would be a
     * patient-safety regression, not just a display quirk.
     */
    @Transactional
    public InventoryItemResponse addBatch(
        UUID bloodBankId, String bloodTypeLabel, AddBatchRequest request, AuthenticatedUser principal
    ) {
        BloodBank bank = requireBank(bloodBankId);
        assertCanModify(bloodBankId, principal);
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        BloodType bloodType = BloodType.fromLabel(bloodTypeLabel);

        BloodInventory row = bloodInventoryRepository.findByBloodBankIdAndBloodType(bloodBankId, bloodType)
            .orElseGet(() -> {
                BloodInventory created = new BloodInventory();
                created.setBloodBank(bank);
                created.setBloodType(bloodType);
                created.setUnitsAvailable(0);
                return created;
            });

        int unitsBefore = row.getUnitsAvailable();
        row.setUnitsAvailable(unitsBefore + request.unitsToAdd());
        row.setExpiryDate(
            row.getExpiryDate() == null
                ? request.expiryDate()
                : earlierOf(row.getExpiryDate(), request.expiryDate())
        );
        // touch() on the entity's @PrePersist/@PreUpdate sets lastUpdated.
        BloodInventory saved = bloodInventoryRepository.save(row);

        // Every path that changes a stock figure reports the change, so the
        // low-stock rule lives in one place rather than being re-derived per caller.
        // Adding units can only improve a level, so in practice this raises nothing
        // today — it is here so that a future path through addBatch which lowers a
        // count (a correction, a write-off) cannot silently skip the alert.
        notificationService.notifyStockCrossing(bank, bloodType, unitsBefore, saved.getUnitsAvailable());

        // This is the only place stock can rise, so it is the only place an appeal
        // cooldown can be recovered out of (Business Rule 12). If these units take
        // the group back over the threshold that triggered its cooldown, that
        // cooldown is finished for good — a later relapse into shortage is a new
        // event with its own chance to appeal, not the tail of this one's timer.
        //
        // What comes back is whatever cooldown survives, which is exactly what the
        // returned row should report: the state this batch has just produced, not
        // the one it replaced.
        AppealCooldown cooldown =
            appealCooldownPolicy.clearIfRecovered(bloodBankId, bloodType, saved.getUnitsAvailable());

        return toResponse(bloodType, saved, cooldown, Instant.now());
    }

    /**
     * ADMIN may modify any bank. BLOOD_BANK_STAFF may modify only the bank on their
     * own User row — null (no admin has assigned one yet) means access to none.
     */
    private void assertCanModify(UUID bloodBankId, AuthenticatedUser principal) {
        if (principal.getRole() == Role.ADMIN) {
            return;
        }
        User user = userRepository.findById(principal.getId())
            .orElseThrow(() -> new NotFoundException("Account not found."));
        boolean ownsThisBank = user.getBloodBank() != null && user.getBloodBank().getId().equals(bloodBankId);
        if (!ownsThisBank) {
            throw new AccessDeniedException("This account is not assigned to that blood bank.");
        }
    }

    private BloodBank requireBank(UUID bloodBankId) {
        return bloodBankRepository.findById(bloodBankId)
            .orElseThrow(() -> new NotFoundException("No blood bank found for this id."));
    }

    private Map<BloodType, BloodInventory> indexByType(List<BloodInventory> rows) {
        Map<BloodType, BloodInventory> byType = new EnumMap<>(BloodType.class);
        rows.forEach(row -> byType.put(row.getBloodType(), row));
        return byType;
    }

    /** Maps a possibly-absent row to a response — absent means never stocked. */
    /**
     * One table row: the stock figures, the Business Rule 3 badge, and the Business
     * Rule 12 state of its Send Appeal button.
     *
     * The last of those is asked of {@link AppealCooldownPolicy} rather than worked
     * out here, so this screen and the send endpoint cannot come to different
     * conclusions about whether an appeal is allowed.
     */
    private InventoryItemResponse toResponse(
        BloodType type, BloodInventory row, AppealCooldown cooldown, Instant now
    ) {
        int units = row == null ? 0 : row.getUnitsAvailable();
        return new InventoryItemResponse(
            type.getLabel(),
            units,
            row == null ? null : row.getExpiryDate(),
            row == null ? null : row.getLastUpdated(),
            StockStatus.fromUnits(units),
            appealCooldownPolicy.evaluate(units, cooldown, now)
        );
    }

    private LocalDate earlierOf(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
