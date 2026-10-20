package com.bloodlink.inventory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.audit.AuditLogService;
import com.bloodlink.domain.BatchWriteOff;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.WriteOffReason;
import com.bloodlink.inventory.dto.BatchHistoryResponse;
import com.bloodlink.inventory.dto.BatchResponse;
import com.bloodlink.inventory.dto.BatchWriteOffResponse;
import com.bloodlink.inventory.dto.WriteOffBatchRequest;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BatchWriteOffRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.stock.BatchStockService;
import com.bloodlink.stock.StockPolicy;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Writing units off one batch with a reason (Business Rule 24), the way expired units finally leave the shelf.
 * Active staff of the batch's own bank only, never ADMIN, via InventoryService.requireStockChangeAccess.
 */
@Service
public class BatchWriteOffService {

    private final InventoryService inventoryService;
    private final BloodInventoryBatchRepository batchRepository;
    private final BatchWriteOffRepository writeOffRepository;
    private final BatchStockService batchStockService;
    private final StockPolicy stockPolicy;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    /** The application clock (TimeConfig): the one source of "now" for a write-off. */
    private final Clock clock;

    // Public, not package-private, so a test in another package can build the real
    // rules over mocked repositories (the same choice BatchStockService makes).
    public BatchWriteOffService(
        InventoryService inventoryService,
        BloodInventoryBatchRepository batchRepository,
        BatchWriteOffRepository writeOffRepository,
        BatchStockService batchStockService,
        StockPolicy stockPolicy,
        UserRepository userRepository,
        AuditLogService auditLogService,
        NotificationService notificationService,
        Clock clock
    ) {
        this.inventoryService = inventoryService;
        this.batchRepository = batchRepository;
        this.writeOffRepository = writeOffRepository;
        this.batchStockService = batchStockService;
        this.stockPolicy = stockPolicy;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /**
     * Writes units off one batch: access (403/404), then the request (400s), then the batch lock and ownership (404),
     * then the units left (409); deduction, ledger row and audit entry in one transaction, then the low-stock check.
     */
    @Transactional
    public BatchWriteOffResponse writeOff(
        UUID bloodBankId, UUID batchId, WriteOffBatchRequest request, AuthenticatedUser principal
    ) {
        BloodBank bank = inventoryService.requireStockChangeAccess(bloodBankId, principal);

        int units = requireUnits(request.units());
        WriteOffReason reason = requireReason(request.reason());
        BatchWriteOff.problemWithNote(reason, request.note()).ifPresent(problem -> {
            throw new InvalidFieldException("note", problem);
        });

        BloodInventoryBatch batch = batchRepository.lockById(batchId)
            .filter(found -> found.getBloodBank().getId().equals(bloodBankId))
            .orElseThrow(() -> new NotFoundException("No batch found for this id at this blood bank."));

        int remaining = batch.getUnitsRemaining();
        if (remaining == 0) {
            throw new ConflictException("This batch has no units left to write off.");
        }
        if (units > remaining) {
            throw new ConflictException("This batch has only %s left — %s cannot be written off."
                .formatted(unitsText(remaining), unitsText(units)));
        }

        User actor = userRepository.findById(principal.getId())
            .orElseThrow(() -> new NotFoundException("Account not found."));
        LocalDate today = stockPolicy.today();
        boolean usable = StockPolicy.isUsable(batch.getExpiryDate(), today);
        // Read before the deduction, while this transaction holds the batch's lock.
        int usableBefore = usable ? batchStockService.usableUnits(bloodBankId, batch.getBloodType()) : 0;

        Instant now = clock.instant();
        batch.deductUnits(units, now);
        BatchWriteOff writeOff = writeOffRepository.save(
            BatchWriteOff.record(batch, units, reason, request.note(), actor, now));
        auditLogService.recordStockWrittenOff(actor.getId(), batch, describe(bank, batch, writeOff, today), now);

        if (usable) {
            notificationService.notifyStockCrossing(bank, batch.getBloodType(), usableBefore, usableBefore - units);
        }

        return new BatchWriteOffResponse(
            new BatchHistoryResponse.WriteOffEntry(
                writeOff.getId(), units, reason, writeOff.getNote(), actor.getFullName(), now),
            BatchResponse.from(batch, today)
        );
    }

    /** Bean Validation refuses a missing or non-positive figure first; this is the
     *  same rule for a caller that reached the service another way. */
    private static int requireUnits(Integer units) {
        if (units == null || units < 1) {
            throw new InvalidFieldException("units", "Must write off at least 1 unit");
        }
        return units;
    }

    private static WriteOffReason requireReason(String reason) {
        return WriteOffReason.fromName(reason == null ? null : reason.trim())
            .orElseThrow(() -> new InvalidFieldException(
                "reason", "Reason must be one of " + WriteOffReason.allNames() + "."));
    }

    /**
     * The audit entry's wording: what was discarded, from which batch, why and what is left,
     * e.g. "Wrote off 3 units of O+ ... 7 of 10 units remain in the batch."
     */
    static String describe(BloodBank bank, BloodInventoryBatch batch, BatchWriteOff writeOff, LocalDate today) {
        String details = "Wrote off %s of %s from a batch at %s expiring %s%s. Reason: %s. %d of %d units remain in the batch."
            .formatted(
                unitsText(writeOff.getUnits()),
                batch.getBloodType().getLabel(),
                bank.getName(),
                batch.getExpiryDate(),
                StockPolicy.isExpired(batch.getExpiryDate(), today) ? " (already expired)" : "",
                writeOff.getReason().getLabel(),
                batch.getUnitsRemaining(),
                batch.getUnitsReceived());
        return writeOff.getNote() == null ? details : details + " Note: " + writeOff.getNote();
    }

    private static String unitsText(int units) {
        return units == 1 ? "1 unit" : units + " units";
    }
}
