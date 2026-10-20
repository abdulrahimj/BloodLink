package com.bloodlink.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bloodlink.stock.FefoAllocator.Allocation;
import com.bloodlink.stock.FefoAllocator.Candidate;
import com.bloodlink.stock.FefoAllocator.InsufficientUsableStockException;

/**
 * First expiry, first out, as pure logic; the matching database order is proven in BatchStockSchemaIT.
 */
class FefoAllocatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private static final UUID ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ID_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static Candidate batch(UUID id, int daysFromToday, int units) {
        return new Candidate(id, TODAY.plusDays(daysFromToday), units);
    }

    // ---------- the order ----------

    @Test
    void theEarliestExpiringBatchIsUsedFirstWhateverOrderTheyArriveIn() {
        List<Candidate> batches = List.of(
            batch(ID_1, 30, 5),
            batch(ID_2, 2, 5),
            batch(ID_3, 10, 5)
        );

        assertThat(FefoAllocator.allocate(7, batches, TODAY)).containsExactly(
            new Allocation(ID_2, 5),  // expires in 2 days: emptied first
            new Allocation(ID_3, 2)   // expires in 10 days: only what is still needed
        );
    }

    @Test
    void batchesExpiringTheSameDayAreTakenInIdOrder() {
        List<Candidate> batches = List.of(batch(ID_3, 5, 2), batch(ID_1, 5, 2), batch(ID_2, 5, 2));

        assertThat(FefoAllocator.allocate(5, batches, TODAY)).containsExactly(
            new Allocation(ID_1, 2), new Allocation(ID_2, 2), new Allocation(ID_3, 1));
    }

    @Test
    void theIdTieBreakUsesPostgresUnsignedOrderNotJavasSignedOne() {
        // 80000000-... has its top bit set: Java's compareTo puts it first, PostgreSQL last; the allocator follows PostgreSQL.
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        assertThat(high.compareTo(low)).as("Java's own signed comparison").isNegative();

        List<Allocation> taken = FefoAllocator.allocate(1, List.of(batch(high, 5, 1), batch(low, 5, 1)), TODAY);

        assertThat(taken).containsExactly(new Allocation(low, 1));
        assertThat(FefoAllocator.compareAsPostgres(high, low)).isPositive();
        // The low 64 bits are compared unsigned as well.
        assertThat(FefoAllocator.compareAsPostgres(
            UUID.fromString("00000000-0000-0000-8000-000000000000"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"))).isPositive();
    }

    // ---------- partial use and exact exhaustion ----------

    @Test
    void aBatchCanBeUsedPartly() {
        assertThat(FefoAllocator.allocate(3, List.of(batch(ID_1, 5, 10)), TODAY))
            .containsExactly(new Allocation(ID_1, 3));
    }

    @Test
    void exactlyEmptyingOneBatchDoesNotTouchTheNext() {
        assertThat(FefoAllocator.allocate(4, List.of(batch(ID_1, 5, 4), batch(ID_2, 9, 6)), TODAY))
            .containsExactly(new Allocation(ID_1, 4));
    }

    @Test
    void takingEveryUsableUnitEmptiesEveryBatch() {
        assertThat(FefoAllocator.allocate(10, List.of(batch(ID_1, 5, 4), batch(ID_2, 9, 6)), TODAY))
            .containsExactly(new Allocation(ID_1, 4), new Allocation(ID_2, 6));
    }

    // ---------- what is never allocated ----------

    @Test
    void anExpiredBatchIsNeverAllocatedEvenIfPassedIn() {
        // Expired yesterday, so it would come first by date. It must be skipped.
        List<Candidate> batches = List.of(batch(ID_1, -1, 10), batch(ID_2, 5, 3));

        assertThat(FefoAllocator.allocate(3, batches, TODAY)).containsExactly(new Allocation(ID_2, 3));
    }

    @Test
    void aBatchExpiringTodayIsStillAllocated() {
        assertThat(FefoAllocator.allocate(2, List.of(batch(ID_1, 0, 2), batch(ID_2, 5, 2)), TODAY))
            .containsExactly(new Allocation(ID_1, 2));
    }

    @Test
    void aBatchWithNothingLeftIsSkipped() {
        assertThat(FefoAllocator.allocate(1, List.of(batch(ID_1, 1, 0), batch(ID_2, 5, 1)), TODAY))
            .containsExactly(new Allocation(ID_2, 1));
    }

    // ---------- refused ----------

    @Test
    void notEnoughUsableStockIsRefusedWithTheFiguresAndAllocatesNothing() {
        // 5 usable plus 10 expired: 15 on the shelf, but only 5 can be issued.
        List<Candidate> batches = List.of(batch(ID_1, -2, 10), batch(ID_2, 5, 3), batch(ID_3, 9, 2));

        assertThatThrownBy(() -> FefoAllocator.allocate(6, batches, TODAY))
            .isInstanceOf(InsufficientUsableStockException.class)
            .hasMessageContaining("Requested 6 units but only 5 usable units")
            .hasMessageContaining("10 more are expired")
            .satisfies(e -> {
                InsufficientUsableStockException refusal = (InsufficientUsableStockException) e;
                assertThat(refusal.getUnitsRequested()).isEqualTo(6);
                assertThat(refusal.getUsableUnits()).isEqualTo(5);
                assertThat(refusal.getExpiredUnits()).isEqualTo(10);
            });
    }

    @Test
    void noBatchesAtAllIsRefused() {
        assertThatThrownBy(() -> FefoAllocator.allocate(1, List.of(), TODAY))
            .isInstanceOf(InsufficientUsableStockException.class)
            .hasMessage("Requested 1 units but only 0 usable units are available.");
    }

    @Test
    void zeroOrNegativeUnitsAreRefused() {
        assertThatThrownBy(() -> FefoAllocator.allocate(0, List.of(batch(ID_1, 5, 3)), TODAY))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FefoAllocator.allocate(-1, List.of(batch(ID_1, 5, 3)), TODAY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- invariants ----------

    @Test
    void theInputListIsNotChanged() {
        List<Candidate> batches = new ArrayList<>(List.of(batch(ID_3, 9, 2), batch(ID_1, 5, 2)));
        List<Candidate> before = List.copyOf(batches);

        FefoAllocator.allocate(3, batches, TODAY);

        assertThat(batches).isEqualTo(before);
    }

    @Test
    void acrossManyRandomShelvesTheAllocationIsAlwaysExactAndInIssueOrder() {
        // A fixed seed, so a failure can be reproduced.
        Random random = new Random(20261016L);
        for (int run = 0; run < 500; run++) {
            List<Candidate> batches = new ArrayList<>();
            int usable = 0;
            for (int i = 0, n = 1 + random.nextInt(6); i < n; i++) {
                Candidate candidate = batch(UUID.randomUUID(), random.nextInt(20) - 5, random.nextInt(6));
                batches.add(candidate);
                if (StockPolicy.isUsable(candidate.expiryDate(), TODAY)) {
                    usable += candidate.unitsRemaining();
                }
            }
            if (usable == 0) {
                continue;
            }
            int wanted = 1 + random.nextInt(usable);

            List<Allocation> taken = FefoAllocator.allocate(wanted, batches, TODAY);

            assertThat(taken.stream().mapToInt(Allocation::units).sum()).isEqualTo(wanted);
            assertThat(taken).allMatch(allocation -> allocation.units() > 0);
            // Every batch taken from is usable, and taken from at most its remaining units.
            for (Allocation allocation : taken) {
                Candidate source = batches.stream()
                    .filter(c -> c.batchId().equals(allocation.batchId())).findFirst().orElseThrow();
                assertThat(StockPolicy.isUsable(source.expiryDate(), TODAY)).isTrue();
                assertThat(allocation.units()).isLessThanOrEqualTo(source.unitsRemaining());
            }
            // And only the last batch touched may be used partly: FEFO empties
            // earlier batches before moving on.
            for (int i = 0; i < taken.size() - 1; i++) {
                Allocation allocation = taken.get(i);
                Candidate source = batches.stream()
                    .filter(c -> c.batchId().equals(allocation.batchId())).findFirst().orElseThrow();
                assertThat(allocation.units()).isEqualTo(source.unitsRemaining());
            }
        }
    }
}
