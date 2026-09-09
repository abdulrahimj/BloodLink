package com.bloodlink.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventory;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.CompositionSlice;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.inventory.dto.InventorySummaryResponse;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryRepository;
import com.bloodlink.repository.BloodRequestRepository;
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
    private final BloodRequestRepository bloodRequestRepository;
    private final NotificationService notificationService;

    InventoryService(
        BloodBankRepository bloodBankRepository,
        BloodInventoryRepository bloodInventoryRepository,
        UserRepository userRepository,
        BloodRequestRepository bloodRequestRepository,
        NotificationService notificationService
    ) {
        this.bloodBankRepository = bloodBankRepository;
        this.bloodInventoryRepository = bloodInventoryRepository;
        this.userRepository = userRepository;
        this.bloodRequestRepository = bloodRequestRepository;
        this.notificationService = notificationService;
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

        return List.of(BloodType.values()).stream()
            .map(type -> toResponse(type, byType.get(type)))
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
        long unitsIssued = bloodRequestRepository.sumUnitsForBankAndStatusInRange(
            bloodBankId, RequestStatus.FULFILLED, monthStart, monthEnd
        );

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

        return toResponse(bloodType, saved);
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
    private InventoryItemResponse toResponse(BloodType type, BloodInventory row) {
        int units = row == null ? 0 : row.getUnitsAvailable();
        return new InventoryItemResponse(
            type.getLabel(),
            units,
            row == null ? null : row.getExpiryDate(),
            row == null ? null : row.getLastUpdated(),
            StockStatus.fromUnits(units)
        );
    }

    private LocalDate earlierOf(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
