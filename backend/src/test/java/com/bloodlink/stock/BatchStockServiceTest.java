package com.bloodlink.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.ContributionAllocation;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.ContributionAllocationRepository;
import com.bloodlink.stock.BatchStockService.GroupStock;
import com.bloodlink.stock.BatchStockService.InsufficientStockException;
import com.bloodlink.stock.BatchStockService.Issue;

/**
 * What BatchStockService does with what its queries return: issuing first expiry
 * first out from the locked batches, refusing with the usable and expired figures,
 * refusing allocations that do not add up to their contribution, and always
 * reporting eight groups. The queries themselves are proven against a real
 * database in BatchStockCutoverIT and the race tests.
 */
@ExtendWith(MockitoExtension.class)
class BatchStockServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.now(CLOCK);

    @Mock private BloodInventoryBatchRepository batchRepository;
    @Mock private ContributionAllocationRepository allocationRepository;

    private BatchStockService service;

    @BeforeEach
    void setUp() {
        service = new BatchStockService(batchRepository, allocationRepository, new StockPolicy(CLOCK));
    }

    private static BloodInventoryBatch batch(int units, LocalDate expiry) {
        BloodInventoryBatch batch = BloodInventoryBatch.manual(new BloodBank(), BloodType.O_NEGATIVE, units, expiry);
        ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());
        return batch;
    }

    @Test
    void issuingDeductsFromTheLockedBatchesEarliestExpiryFirst() {
        BloodInventoryBatch soon = batch(3, TODAY.plusDays(2));
        BloodInventoryBatch later = batch(5, TODAY.plusDays(40));
        when(batchRepository.lockUsableBatchesInIssueOrder(BANK_ID, BloodType.O_NEGATIVE, TODAY))
            .thenReturn(List.of(soon, later));

        Issue issue = service.issue(BANK_ID, BloodType.O_NEGATIVE, 4);

        assertThat(issue.draws()).extracting(Issue.Draw::batch).containsExactly(soon, later);
        assertThat(issue.draws()).extracting(Issue.Draw::units).containsExactly(3, 1);
        assertThat(issue.totalUnits()).isEqualTo(4);
        assertThat(issue.usableBefore()).isEqualTo(8);
        assertThat(issue.usableAfter()).isEqualTo(4);
        assertThat(soon.getUnitsRemaining()).isZero();
        assertThat(later.getUnitsRemaining()).isEqualTo(4);
    }

    @Test
    void notEnoughUsableStockIsRefusedWithTheExpiredFigureAndNothingIsDeducted() {
        BloodInventoryBatch only = batch(2, TODAY.plusDays(10));
        when(batchRepository.lockUsableBatchesInIssueOrder(BANK_ID, BloodType.O_NEGATIVE, TODAY)).thenReturn(List.of(only));
        when(batchRepository.sumExpiredUnits(BANK_ID, BloodType.O_NEGATIVE, TODAY)).thenReturn(7L);

        assertThatThrownBy(() -> service.issue(BANK_ID, BloodType.O_NEGATIVE, 3))
            .isInstanceOf(InsufficientStockException.class)
            .satisfies(e -> {
                InsufficientStockException refusal = (InsufficientStockException) e;
                assertThat(refusal.getUsableUnits()).isEqualTo(2);
                assertThat(refusal.getExpiredUnits()).isEqualTo(7);
            });
        assertThat(only.getUnitsRemaining()).isEqualTo(2);
    }

    @Test
    void allocationsAreRecordedOnePerBatchDrawnOn() {
        BloodInventoryBatch soon = batch(3, TODAY.plusDays(2));
        BloodInventoryBatch later = batch(5, TODAY.plusDays(40));
        when(batchRepository.lockUsableBatchesInIssueOrder(BANK_ID, BloodType.O_NEGATIVE, TODAY))
            .thenReturn(List.of(soon, later));
        Issue issue = service.issue(BANK_ID, BloodType.O_NEGATIVE, 4);
        BloodRequestContribution contribution = new BloodRequestContribution();
        contribution.setUnitsContributed(4);

        service.recordAllocations(contribution, issue);

        ArgumentCaptor<ContributionAllocation> saved = ArgumentCaptor.forClass(ContributionAllocation.class);
        verify(allocationRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ContributionAllocation::getBatch).containsExactly(soon, later);
        assertThat(saved.getAllValues()).extracting(ContributionAllocation::getUnitsAllocated).containsExactly(3, 1);
        assertThat(saved.getAllValues()).allSatisfy(a -> assertThat(a.getContribution()).isSameAs(contribution));
    }

    @Test
    void allocationsThatDoNotAddUpToTheContributionAreRefusedBeforeAnyIsWritten() {
        BloodInventoryBatch only = batch(5, TODAY.plusDays(10));
        Issue issue = new Issue(List.of(new Issue.Draw(only, 3)), 5, 2);
        BloodRequestContribution contribution = new BloodRequestContribution();
        contribution.setUnitsContributed(4); // claims 4, but only 3 left the shelf

        assertThatThrownBy(() -> service.recordAllocations(contribution, issue))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Allocations total 3 units but the contribution is for 4");
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void stockForABankAlwaysListsAllEightGroups() {
        when(batchRepository.summariseGroupsForBank(BANK_ID, TODAY, StockPolicy.expiringSoonCutoff(TODAY)))
            .thenReturn(List.of());

        Map<BloodType, GroupStock> stock = service.stockForBank(BANK_ID);

        assertThat(stock).hasSize(8).containsOnlyKeys(BloodType.values());
        assertThat(stock.values()).allSatisfy(group -> {
            assertThat(group.usableUnits()).isZero();
            assertThat(group.earliestUsableExpiry()).isNull();
            assertThat(group.lastUpdated()).isNull();
        });
    }
}
