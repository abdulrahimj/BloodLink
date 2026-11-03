package com.bloodlink.stock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.ContributionAllocation;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.ContributionAllocationRepository;
import com.bloodlink.stock.FefoAllocator.Allocation;
import com.bloodlink.stock.FefoAllocator.Candidate;
import com.bloodlink.stock.FefoAllocator.InsufficientUsableStockException;

/**
 * Batch-level stock, read and issued: the one place usable stock is counted and units leave a shelf,
 * so every screen, alert and report agrees. Expired units are reported separately.
 */
@Service
public class BatchStockService {

    private final BloodInventoryBatchRepository batchRepository;
    private final ContributionAllocationRepository allocationRepository;
    private final StockPolicy stockPolicy;

    // Public so tests in other packages can exercise the real rules over mocked repositories.
    public BatchStockService(
        BloodInventoryBatchRepository batchRepository,
        ContributionAllocationRepository allocationRepository,
        StockPolicy stockPolicy
    ) {
        this.batchRepository = batchRepository;
        this.allocationRepository = allocationRepository;
        this.stockPolicy = stockPolicy;
    }

    /**
     * One group's stock at one bank (all zeros and null dates if never stocked): usable, expiring-soon (28 days)
     * and expired units, live batch count, earliest usable expiry, last update.
     */
    public record GroupStock(
        BloodType bloodType,
        int usableUnits,
        int expiringSoonUnits,
        int expiredUnits,
        int batchCount,
        LocalDate earliestUsableExpiry,
        Instant lastUpdated
    ) {
        static GroupStock none(BloodType bloodType) {
            return new GroupStock(bloodType, 0, 0, 0, 0, null, null);
        }
    }

    /** What issuing units took from which batch, and usable stock either side of it. */
    public record Issue(List<Draw> draws, int usableBefore, int usableAfter) {

        /** Units taken from one locked batch. */
        public record Draw(BloodInventoryBatch batch, int units) {}

        public int totalUnits() {
            return draws.stream().mapToInt(Draw::units).sum();
        }
    }

    /** Refused: the bank's usable stock does not cover what is being issued. */
    public static final class InsufficientStockException extends RuntimeException {

        private final int usableUnits;
        private final int expiredUnits;

        InsufficientStockException(int requestedUnits, int usableUnits, int expiredUnits) {
            super("Requested " + requestedUnits + " units but only " + usableUnits + " usable units are available.");
            this.usableUnits = usableUnits;
            this.expiredUnits = expiredUnits;
        }

        public int getUsableUnits() {
            return usableUnits;
        }

        /** Units of the same group in this bank's expired batches: on the shelf, but
         *  unusable and so not counted. */
        public int getExpiredUnits() {
            return expiredUnits;
        }
    }

    // ---------- reading ----------

    /** All eight blood groups' stock at one bank: always eight entries, in enum order. */
    @Transactional(readOnly = true)
    public Map<BloodType, GroupStock> stockForBank(UUID bloodBankId) {
        LocalDate today = stockPolicy.today();
        Map<BloodType, GroupStock> found = batchRepository
            .summariseGroupsForBank(bloodBankId, today, StockPolicy.expiringSoonCutoff(today)).stream()
            .map(row -> new GroupStock(
                row.getBloodType(),
                Math.toIntExact(row.getUsableUnits()),
                Math.toIntExact(row.getExpiringSoonUnits()),
                Math.toIntExact(row.getExpiredUnits()),
                Math.toIntExact(row.getLiveBatches()),
                row.getEarliestUsableExpiry(),
                row.getLastUpdated()))
            .collect(Collectors.toMap(GroupStock::bloodType, Function.identity()));

        Map<BloodType, GroupStock> all = new EnumMap<>(BloodType.class);
        for (BloodType type : BloodType.values()) {
            all.put(type, found.getOrDefault(type, GroupStock.none(type)));
        }
        return all;
    }

    /** Usable units of one group at one bank: the figure Business Rules 3 and 12 judge. */
    @Transactional(readOnly = true)
    public int usableUnits(UUID bloodBankId, BloodType bloodType) {
        return Math.toIntExact(batchRepository.sumUsableUnits(bloodBankId, bloodType, stockPolicy.today()));
    }

    /** Usable units of one group at every bank holding at least 1, by bank id; banks with none are absent. */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> usableUnitsByBank(BloodType bloodType) {
        Map<UUID, Integer> units = new LinkedHashMap<>();
        batchRepository.findUsableUnitsByBank(bloodType, stockPolicy.today())
            .forEach(row -> units.put(row.getBankId(), Math.toIntExact(row.getUsableUnits())));
        return units;
    }

    // ---------- daily expiry notices (Business Rule 26) ----------

    /** The reminder comes 7 days before expiry; separate from StockPolicy's 28-day "expiring soon". */
    public static final int EXPIRY_REMINDER_DAYS = 7;

    /**
     * Batches due their seven-day reminder: usable, holding units, never reminded, and already in stock on their
     * seventh day before expiry, so a run missed that day catches them up while a batch added later gets none.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<BloodInventoryBatch> batchesDueExpiryReminder(UUID bloodBankId, LocalDate today) {
        return batchRepository
            .findUnremindedUsableExpiringBy(bloodBankId, today, today.plusDays(EXPIRY_REMINDER_DAYS)).stream()
            .filter(batch -> !LocalDate.ofInstant(batch.getReceivedAt(), ZoneOffset.UTC)
                .isAfter(batch.getExpiryDate().minusDays(EXPIRY_REMINDER_DAYS)))
            .toList();
    }

    /** Expired batches still holding units, oldest expiry first: the ones to write off. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<BloodInventoryBatch> expiredBatchesHoldingUnits(UUID bloodBankId, LocalDate today) {
        return batchRepository.findExpiredHoldingUnits(bloodBankId, today);
    }

    /** Records that these batches had their seven-day reminder, so it is never sent again. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markExpiryReminderSent(List<UUID> batchIds, Instant at) {
        if (!batchIds.isEmpty()) {
            batchRepository.markExpiryNoticed(batchIds, at);
        }
    }

    // ---------- issuing ----------

    /**
     * Takes units off one bank's usable batches of a group, first expiry first out, in the caller's transaction
     * (MANDATORY); batches locked in issue order. Throws InsufficientStockException with nothing deducted.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Issue issue(UUID bloodBankId, BloodType bloodType, int units) {
        LocalDate today = stockPolicy.today();
        List<BloodInventoryBatch> locked = batchRepository.lockUsableBatchesInIssueOrder(bloodBankId, bloodType, today);
        int usableBefore = locked.stream().mapToInt(BloodInventoryBatch::getUnitsRemaining).sum();

        List<Allocation> allocations;
        try {
            allocations = FefoAllocator.allocate(
                units,
                locked.stream().map(b -> new Candidate(b.getId(), b.getExpiryDate(), b.getUnitsRemaining())).toList(),
                today);
        } catch (InsufficientUsableStockException refused) {
            // The locked batches are usable ones only, so the expired figure for the
            // refusal message is read separately; it is information, not stock.
            int expired = Math.toIntExact(batchRepository.sumExpiredUnits(bloodBankId, bloodType, today));
            throw new InsufficientStockException(units, refused.getUsableUnits(), expired);
        }

        Map<UUID, BloodInventoryBatch> byId = locked.stream()
            .collect(Collectors.toMap(BloodInventoryBatch::getId, Function.identity()));
        List<Issue.Draw> draws = allocations.stream()
            .map(allocation -> {
                BloodInventoryBatch batch = byId.get(allocation.batchId());
                batch.deductUnits(allocation.units());
                return new Issue.Draw(batch, allocation.units());
            })
            .toList();

        return new Issue(draws, usableBefore, usableBefore - units);
    }

    /**
     * Records one allocation row per batch a contribution drew on; refuses before writing
     * if they do not add up to the contribution's units.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAllocations(BloodRequestContribution contribution, Issue issue) {
        if (issue.totalUnits() != contribution.getUnitsContributed()) {
            throw new IllegalStateException("Allocations total " + issue.totalUnits()
                + " units but the contribution is for " + contribution.getUnitsContributed() + ".");
        }
        for (Issue.Draw draw : issue.draws()) {
            allocationRepository.save(ContributionAllocation.of(contribution, draw.batch(), draw.units()));
        }
    }
}
