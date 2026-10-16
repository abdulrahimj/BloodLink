package com.bloodlink.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;

import com.bloodlink.domain.BatchWriteOff;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.ContributionAllocation;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BatchSource;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.WriteOffReason;
import com.bloodlink.repository.BatchWriteOffRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.ContributionAllocationRepository;

/**
 * The Java half of the batch tables' rules (the database half is BatchStockSchemaIT):
 * a batch's identity has no setters, units cannot be taken below zero, write-off
 * notes follow the same rules as V18's CHECKs, and the two ledgers have no way to
 * change or remove an entry — the same code-level checks AuditLogAppendOnlyTest
 * makes for the audit log.
 */
class BatchStockEntitiesTest {

    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 27);

    // ---------- BloodInventoryBatch ----------

    @Test
    void aNewBatchHasEverythingItReceivedStillRemaining() {
        BloodInventoryBatch batch = BloodInventoryBatch.manual(new BloodBank(), BloodType.O_NEGATIVE, 12, EXPIRY);

        assertThat(batch.getUnitsReceived()).isEqualTo(12);
        assertThat(batch.getUnitsRemaining()).isEqualTo(12);
        assertThat(batch.getSource()).isEqualTo(BatchSource.MANUAL);
        assertThat(batch.getDonation()).isNull();
    }

    @Test
    void anEmptyBatchCannotBeCreated() {
        assertThatThrownBy(() -> BloodInventoryBatch.manual(new BloodBank(), BloodType.O_NEGATIVE, 0, EXPIRY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unitsCanBeDeductedDownToZeroButNotBelow() {
        BloodInventoryBatch batch = BloodInventoryBatch.manual(new BloodBank(), BloodType.A_POSITIVE, 5, EXPIRY);

        batch.deductUnits(3);
        batch.deductUnits(2);
        assertThat(batch.getUnitsRemaining()).isZero();
        // The batch stays, as history: received is unchanged.
        assertThat(batch.getUnitsReceived()).isEqualTo(5);

        assertThatThrownBy(() -> batch.deductUnits(1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("0 remaining");
        assertThatThrownBy(() -> batch.deductUnits(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBatchHasNoSetterForAnyIdentityColumn() {
        // Only units_remaining may change, and only through deductUnits.
        List<String> setters = publicSetters(BloodInventoryBatch.class);
        assertThat(setters).isEmpty();
    }

    // ---------- BatchWriteOff ----------

    @Test
    void aBlankNoteIsStoredAsNoNote() {
        BatchWriteOff writeOff = BatchWriteOff.record(batch(), 1, WriteOffReason.EXPIRED, "   ", new User());
        assertThat(writeOff.getNote()).isNull();
    }

    @Test
    void otherRequiresANote() {
        assertThatThrownBy(() -> BatchWriteOff.record(batch(), 1, WriteOffReason.OTHER, " ", new User()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("note is required");
        assertThat(BatchWriteOff.record(batch(), 1, WriteOffReason.OTHER, " Bag mislabelled ", new User()).getNote())
            .isEqualTo("Bag mislabelled");
        assertThat(WriteOffReason.OTHER.requiresNote()).isTrue();
        assertThat(Arrays.stream(WriteOffReason.values()).filter(WriteOffReason::requiresNote))
            .containsExactly(WriteOffReason.OTHER);
    }

    @Test
    void aNoteOverFiveHundredCharactersIsRefused() {
        assertThat(BatchWriteOff.record(batch(), 1, WriteOffReason.DAMAGED, "x".repeat(500), new User()).getNote())
            .hasSize(500);
        assertThatThrownBy(() -> BatchWriteOff.record(batch(), 1, WriteOffReason.DAMAGED, "x".repeat(501), new User()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWriteOffOfNoUnitsIsRefused() {
        assertThatThrownBy(() -> BatchWriteOff.record(batch(), 0, WriteOffReason.EXPIRED, null, new User()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- the ledgers cannot be changed from Java ----------

    @Test
    void theLedgerEntitiesHaveNoSettersAndAreImmutableToHibernate() {
        for (Class<?> ledger : List.of(ContributionAllocation.class, BatchWriteOff.class)) {
            assertThat(publicSetters(ledger)).as("public setters on " + ledger.getSimpleName()).isEmpty();
            assertThat(ledger.isAnnotationPresent(Immutable.class)).as(ledger.getSimpleName() + " @Immutable").isTrue();
        }
    }

    @Test
    void noBatchOrLedgerRepositoryCanDeleteOrUpdate() {
        // Switching any of these to JpaRepository "for consistency" would bring
        // deleteAll with it. Batches are history; the ledgers are append-only.
        for (Class<?> repository : List.of(
                BloodInventoryBatchRepository.class, ContributionAllocationRepository.class, BatchWriteOffRepository.class)) {
            assertThat(CrudRepository.class.isAssignableFrom(repository))
                .as(repository.getSimpleName() + " must not extend CrudRepository/JpaRepository").isFalse();
            List<String> names = Arrays.stream(repository.getMethods()).map(Method::getName).toList();
            assertThat(names).as(repository.getSimpleName()).noneMatch(name -> name.startsWith("delete")
                || name.startsWith("remove") || name.startsWith("update") || name.startsWith("modify"));
            assertThat(names).contains("save");
        }
    }

    // ---------- helpers ----------

    private static BloodInventoryBatch batch() {
        return BloodInventoryBatch.manual(new BloodBank(), BloodType.B_POSITIVE, 4, EXPIRY);
    }

    private static List<String> publicSetters(Class<?> type) {
        return Arrays.stream(type.getMethods())
            .filter(m -> m.getName().startsWith("set") && Modifier.isPublic(m.getModifiers()))
            .map(Method::getName)
            .toList();
    }
}
