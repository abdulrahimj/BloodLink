package com.bloodlink.stock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
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
 * Batch-level stock, read and issued — the one place the rest of the application
 * asks how much usable stock a bank holds, and the one place units leave a shelf.
 *
 * Every figure here counts USABLE stock only, as StockPolicy defines it: units in
 * batches whose expiry date is today or later (UTC). Units in expired batches are
 * reported separately and never counted as available. Because the Inventory screen,
 * the dashboard, hospital availability, low-stock alerts, appeals, request alerts
 * and the Admin Reports all read their stock from here (or from the same repository
 * queries, given the same "today"), they cannot disagree about what is on the shelf.
 */
@Service
public class BatchStockService {

    private final BloodInventoryBatchRepository batchRepository;
    private final ContributionAllocationRepository allocationRepository;
    private final StockPolicy stockPolicy;

    // Public, not package-private, so a test in another package can exercise the real
    // rules over mocked repositories rather than stubbing this service away (the same
    // choice AppealCooldownPolicy makes).
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
     * One blood group's stock at one bank. A group never stocked is all zeros with
     * null dates, which the Inventory screen shows as "Never stocked".
     *
     * @param usableUnits          units in batches not yet expired: what can be issued
     * @param expiringSoonUnits    of those, the units expiring within 28 days
     * @param expiredUnits         units still on the shelf in expired batches, awaiting
     *                             discard; never part of usableUnits
     * @param batchCount           batches with any units left, expired ones included
     * @param earliestUsableExpiry the soonest expiry among usable batches with units
     *                             left, or null when there are none
     * @param lastUpdated          the most recent change to any of the group's batches,
     *                             or null if the group has never been stocked
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

    /** Banks holding at least {@code units} usable units of one group. */
    @Transactional(readOnly = true)
    public List<UUID> bankIdsWithUsableUnitsAtLeast(BloodType bloodType, int units) {
        return batchRepository.findBankIdsWithUsableUnitsAtLeast(bloodType, units, stockPolicy.today());
    }

    // ---------- issuing ----------

    /**
     * Takes {@code units} usable units of one group off one bank's shelf, first
     * expiry first out, and returns what was taken from which batch.
     *
     * Must run inside the caller's transaction ({@code MANDATORY}): the caller has
     * already locked the request row, the batches are locked here, and the
     * deduction, the contribution row and its allocations must all commit or roll
     * back together. The batches are locked in issue order (earliest expiry, then
     * id) by {@link BloodInventoryBatchRepository#lockUsableBatchesInIssueOrder};
     * {@link FefoAllocator} decides the split; the deductions are made on the locked,
     * managed entities and written when the transaction flushes.
     *
     * @throws InsufficientStockException if the usable units do not cover it, in
     *         which case nothing is deducted
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
     * Records which batches a contribution's units came from: one allocation row per
     * batch drawn on. Refuses, before writing anything, if the draws do not add up
     * to the contribution's units, so a contribution can never claim more or fewer
     * units than left the shelves.
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
