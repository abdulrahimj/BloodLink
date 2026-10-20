package com.bloodlink.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.bloodlink.appeal.AppealCooldownPolicy;
import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.audit.AuditLogService;
import com.bloodlink.domain.BatchWriteOff;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.WriteOffReason;
import com.bloodlink.inventory.dto.BatchWriteOffResponse;
import com.bloodlink.inventory.dto.WriteOffBatchRequest;
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
import com.bloodlink.stock.StockPolicy;
import com.bloodlink.stock.StockPolicy.ExpiryState;
import com.bloodlink.web.error.ConflictException;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * The write-off's rules over mocked repositories (Business Rule 24): access, refusals, expired batches,
 * and row, audit and alert sharing one clock instant. Locking is proven in BatchWriteOffIT and PartialFulfilmentRaceIT.
 */
@ExtendWith(MockitoExtension.class)
class BatchWriteOffServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID OTHER_BANK_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final UUID STAFF_ID = UUID.randomUUID();

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T09:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.now(CLOCK);

    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private BloodInventoryBatchRepository batchRepository;
    @Mock private ContributionAllocationRepository allocationRepository;
    @Mock private BatchWriteOffRepository writeOffRepository;
    @Mock private UserRepository userRepository;
    @Mock private BloodRequestContributionRepository contributionRepository;
    @Mock private NotificationService notificationService;
    @Mock private AppealCooldownRepository appealCooldownRepository;
    @Mock private AuditLogService auditLogService;

    private BatchWriteOffService service;
    private BloodBank bank;
    private User staff;

    @BeforeEach
    void setUp() {
        StockPolicy stockPolicy = new StockPolicy(CLOCK);
        BatchStockService batchStockService = new BatchStockService(batchRepository, allocationRepository, stockPolicy);
        InventoryService inventoryService = new InventoryService(
            bloodBankRepository, batchRepository, allocationRepository, writeOffRepository,
            batchStockService, stockPolicy, userRepository, contributionRepository, notificationService,
            appealCooldownRepository, new AppealCooldownPolicy(appealCooldownRepository),
            new ActingBankResolver(userRepository, bloodBankRepository)
        );
        service = new BatchWriteOffService(
            inventoryService, batchRepository, writeOffRepository, batchStockService, stockPolicy,
            userRepository, auditLogService, notificationService, CLOCK
        );

        bank = bank(BANK_ID, "Makeni Regional Blood Bank");
        staff = user(STAFF_ID, "Fatmata Kamara", Role.BLOOD_BANK_STAFF, bank);
    }

    // ---------- what a write-off writes ----------

    @Test
    void aValidWriteOffDeductsTheUnitsAndRecordsTheWriteOffTheAuditEntryAndTheAlert() {
        signedInAsStaff();
        BloodInventoryBatch batch = batch(bank, 10, TODAY.plusDays(10));
        lockReturns(batch);
        when(batchRepository.sumUsableUnits(BANK_ID, BloodType.O_POSITIVE, TODAY)).thenReturn(40L);
        savesWriteOffs();

        BatchWriteOffResponse response = service.writeOff(
            BANK_ID, BATCH_ID, new WriteOffBatchRequest(3, "DAMAGED", "  Bag leaking  "), principal(staff));

        assertThat(batch.getUnitsRemaining()).isEqualTo(7);
        assertThat(batch.getLastUpdated()).as("stamped from the injected clock").isEqualTo(CLOCK.instant());

        ArgumentCaptor<BatchWriteOff> saved = ArgumentCaptor.forClass(BatchWriteOff.class);
        verify(writeOffRepository).save(saved.capture());
        assertThat(saved.getValue().getBatch()).isSameAs(batch);
        assertThat(saved.getValue().getUnits()).isEqualTo(3);
        assertThat(saved.getValue().getReason()).isEqualTo(WriteOffReason.DAMAGED);
        assertThat(saved.getValue().getNote()).isEqualTo("Bag leaking");
        assertThat(saved.getValue().getWrittenOffBy()).isSameAs(staff);
        assertThat(saved.getValue().getWrittenOffAt()).isEqualTo(CLOCK.instant());

        verify(auditLogService).recordStockWrittenOff(STAFF_ID, batch,
            "Wrote off 3 units of O+ from a batch at Makeni Regional Blood Bank expiring " + TODAY.plusDays(10)
                + ". Reason: Damaged. 7 of 10 units remain in the batch. Note: Bag leaking",
            CLOCK.instant());
        // 40 usable units before, 37 after: Sufficient into Low, which the rule judges.
        verify(notificationService).notifyStockCrossing(bank, BloodType.O_POSITIVE, 40, 37);

        assertThat(response.writeOff().units()).isEqualTo(3);
        assertThat(response.writeOff().reason()).isEqualTo(WriteOffReason.DAMAGED);
        assertThat(response.writeOff().writtenOffByName()).isEqualTo("Fatmata Kamara");
        assertThat(response.writeOff().writtenOffAt()).isEqualTo(CLOCK.instant());
        assertThat(response.batch().unitsRemaining()).isEqualTo(7);
        assertThat(response.batch().state()).isEqualTo(ExpiryState.EXPIRING_SOON);
    }

    @Test
    void anExpiredBatchCanBeWrittenOffAndUsableStockIsUntouched() {
        signedInAsStaff();
        BloodInventoryBatch batch = batch(bank, 5, TODAY.minusDays(1));
        lockReturns(batch);
        savesWriteOffs();

        BatchWriteOffResponse response = service.writeOff(
            BANK_ID, BATCH_ID, new WriteOffBatchRequest(5, "EXPIRED", null), principal(staff));

        assertThat(batch.getUnitsRemaining()).isZero();
        assertThat(response.batch().state()).isEqualTo(ExpiryState.EXPIRED);
        verify(auditLogService).recordStockWrittenOff(eq(STAFF_ID), eq(batch),
            eq("Wrote off 5 units of O+ from a batch at Makeni Regional Blood Bank expiring " + TODAY.minusDays(1)
                + " (already expired). Reason: Expired. 0 of 5 units remain in the batch."),
            eq(CLOCK.instant()));
        // Expired units were never counted as stock, so no stock figure fell: nothing
        // to sum and no alert to judge.
        verify(batchRepository, never()).sumUsableUnits(any(), any(), any());
        verifyNoInteractions(notificationService);
    }

    // ---------- refused ----------

    /**
     * Stage 3b: an administrator is refused on the role alone, before any lookup or lock, so nothing is written.
     */
    @Test
    void anAdministratorIsRefusedAndNothingIsWritten() {
        User admin = user(UUID.randomUUID(), "System Administrator", Role.ADMIN, null);

        assertThatThrownBy(() -> service.writeOff(
                BANK_ID, BATCH_ID, new WriteOffBatchRequest(1, "EXPIRED", null), principal(admin)))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Only the blood bank's own staff can change its stock.");

        verifyNoInteractions(bloodBankRepository);
        verify(batchRepository, never()).lockById(any());
        assertNothingWritten();
    }

    @Test
    void aSuspendedStaffMemberIsRefusedEvenForTheirOwnBank() {
        staff.setStatus(AccountStatus.SUSPENDED);
        signedInAsStaff();

        assertThatThrownBy(() -> service.writeOff(
                BANK_ID, BATCH_ID, new WriteOffBatchRequest(1, "EXPIRED", null), principal(staff)))
            .isInstanceOf(AccessDeniedException.class);

        verify(batchRepository, never()).lockById(any());
        assertNothingWritten();
    }

    @Test
    void moreUnitsThanTheBatchHoldsIsRefusedAndNothingIsWritten() {
        signedInAsStaff();
        BloodInventoryBatch batch = batch(bank, 4, TODAY.plusDays(10));
        lockReturns(batch);

        assertThatThrownBy(() -> service.writeOff(
                BANK_ID, BATCH_ID, new WriteOffBatchRequest(5, "DAMAGED", null), principal(staff)))
            .isInstanceOf(ConflictException.class)
            .hasMessage("This batch has only 4 units left — 5 units cannot be written off.");

        assertThat(batch.getUnitsRemaining()).isEqualTo(4);
        assertNothingWritten();
    }

    @Test
    void aBatchWithNoUnitsLeftIsRefused() {
        signedInAsStaff();
        BloodInventoryBatch batch = batch(bank, 2, TODAY.plusDays(10));
        batch.deductUnits(2);
        lockReturns(batch);

        assertThatThrownBy(() -> service.writeOff(
                BANK_ID, BATCH_ID, new WriteOffBatchRequest(1, "DAMAGED", null), principal(staff)))
            .isInstanceOf(ConflictException.class)
            .hasMessage("This batch has no units left to write off.");
        assertNothingWritten();
    }

    @Test
    void staffOfAnotherBankAreRefusedBeforeAnyBatchIsLocked() {
        User otherStaff = user(UUID.randomUUID(), "Other Technician", Role.BLOOD_BANK_STAFF,
            bank(OTHER_BANK_ID, "Holy Spirit Blood Bank"));
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank));
        when(userRepository.findById(otherStaff.getId())).thenReturn(Optional.of(otherStaff));

        assertThatThrownBy(() -> service.writeOff(
                BANK_ID, BATCH_ID, new WriteOffBatchRequest(1, "DAMAGED", null), principal(otherStaff)))
            .isInstanceOf(AccessDeniedException.class);

        verify(batchRepository, never()).lockById(any());
        assertNothingWritten();
    }

    @Test
    void aBatchOfAnotherBankAskedForUnderYourOwnBankIsNotFound() {
        signedInAsStaff();
        BloodInventoryBatch othersBatch = batch(bank(OTHER_BANK_ID, "Holy Spirit Blood Bank"), 10, TODAY.plusDays(10));
        lockReturns(othersBatch);

        assertThatThrownBy(() -> service.writeOff(
                BANK_ID, BATCH_ID, new WriteOffBatchRequest(1, "DAMAGED", null), principal(staff)))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("No batch found for this id at this blood bank.");

        assertThat(othersBatch.getUnitsRemaining()).isEqualTo(10);
        assertNothingWritten();
    }

    @Test
    void anUnknownBatchIsNotFound() {
        signedInAsStaff();
        when(batchRepository.lockById(BATCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.writeOff(
                BANK_ID, BATCH_ID, new WriteOffBatchRequest(1, "DAMAGED", null), principal(staff)))
            .isInstanceOf(NotFoundException.class);
        assertNothingWritten();
    }

    @Test
    void anUnknownReasonIsRefusedOnTheReasonFieldBeforeAnyBatchIsLocked() {
        signedInAsStaff();

        for (String reason : new String[] { "LOST", "expired", "" }) {
            assertThatThrownBy(() -> service.writeOff(
                    BANK_ID, BATCH_ID, new WriteOffBatchRequest(1, reason, null), principal(staff)))
                .isInstanceOf(InvalidFieldException.class)
                .hasMessage("Reason must be one of EXPIRED, DAMAGED, FAILED_TESTING, DATA_ENTRY_ERROR, OTHER.")
                .extracting(e -> ((InvalidFieldException) e).getField()).isEqualTo("reason");
        }
        verify(batchRepository, never()).lockById(any());
        assertNothingWritten();
    }

    @Test
    void lessThanOneUnitIsRefusedOnTheUnitsField() {
        signedInAsStaff();

        for (Integer units : new Integer[] { 0, -2, null }) {
            assertThatThrownBy(() -> service.writeOff(
                    BANK_ID, BATCH_ID, new WriteOffBatchRequest(units, "DAMAGED", null), principal(staff)))
                .isInstanceOf(InvalidFieldException.class)
                .extracting(e -> ((InvalidFieldException) e).getField()).isEqualTo("units");
        }
        verify(batchRepository, never()).lockById(any());
    }

    @Test
    void otherWithoutANoteAndANoteOverFiveHundredCharactersAreRefusedOnTheNoteField() {
        signedInAsStaff();

        assertThatThrownBy(() -> service.writeOff(
                BANK_ID, BATCH_ID, new WriteOffBatchRequest(1, "OTHER", "   "), principal(staff)))
            .isInstanceOf(InvalidFieldException.class)
            .hasMessage("A note is required when the reason is OTHER.")
            .extracting(e -> ((InvalidFieldException) e).getField()).isEqualTo("note");
        assertThatThrownBy(() -> service.writeOff(
                BANK_ID, BATCH_ID, new WriteOffBatchRequest(1, "DAMAGED", "x".repeat(501)), principal(staff)))
            .isInstanceOf(InvalidFieldException.class)
            .extracting(e -> ((InvalidFieldException) e).getField()).isEqualTo("note");

        verify(batchRepository, never()).lockById(any());
        assertNothingWritten();
    }

    // ---------- helpers ----------

    private void signedInAsStaff() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank));
        when(userRepository.findById(STAFF_ID)).thenReturn(Optional.of(staff));
    }

    private void lockReturns(BloodInventoryBatch batch) {
        when(batchRepository.lockById(BATCH_ID)).thenReturn(Optional.of(batch));
    }

    private void savesWriteOffs() {
        when(writeOffRepository.save(any(BatchWriteOff.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void assertNothingWritten() {
        verify(writeOffRepository, never()).save(any());
        verify(auditLogService, never()).recordStockWrittenOff(any(), any(), anyString(), any());
        verify(notificationService, never()).notifyStockCrossing(any(), any(), anyInt(), anyInt());
    }

    private static BloodInventoryBatch batch(BloodBank owner, int units, LocalDate expiry) {
        return BloodInventoryBatch.manual(owner, BloodType.O_POSITIVE, units, expiry);
    }

    private static BloodBank bank(UUID id, String name) {
        BloodBank created = new BloodBank();
        created.setId(id);
        created.setName(name);
        return created;
    }

    private static User user(UUID id, String fullName, Role role, BloodBank bloodBank) {
        User user = new User();
        user.setId(id);
        user.setFullName(fullName);
        user.setRole(role);
        user.setBloodBank(bloodBank);
        return user;
    }

    private static AuthenticatedUser principal(User user) {
        return new AuthenticatedUser(user.getId(), "+23276000000", "hash", user.getRole());
    }
}
