package com.bloodlink.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
import com.bloodlink.domain.AppealCooldown;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.AppealAvailability;
import com.bloodlink.domain.enums.AppealTier;
import com.bloodlink.domain.enums.BatchSource;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.inventory.dto.InventorySummaryResponse;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.AppealCooldownRepository;
import com.bloodlink.repository.BatchWriteOffRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository.GroupStockRow;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.ContributionAllocationRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.stock.BatchStockService;
import com.bloodlink.stock.StockPolicy;
import com.bloodlink.web.error.InvalidFieldException;
import com.bloodlink.web.error.NotFoundException;

/**
 * Business Rule 3's thresholds, the always-eight-rows contract, own-bank-only writes, usable-stock figures
 * and each batch's own expiry (Business Rule 24). The SQL itself is proven in BatchStockCutoverIT.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID OTHER_BANK_ID = UUID.randomUUID();

    /** Fixed at the real current instant, so dates written relative to it are valid
     *  entries whatever day the suite runs on. */
    private static final Clock CLOCK = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.now(CLOCK);
    private static final LocalDate CUTOFF = StockPolicy.expiringSoonCutoff(TODAY);

    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private BloodInventoryBatchRepository batchRepository;
    @Mock private ContributionAllocationRepository allocationRepository;
    @Mock private BatchWriteOffRepository writeOffRepository;
    @Mock private UserRepository userRepository;
    @Mock private BloodRequestContributionRepository contributionRepository;
    @Mock private NotificationService notificationService;
    @Mock private AppealCooldownRepository appealCooldownRepository;

    private InventoryService service;

    @BeforeEach
    void setUp() {
        // A real AppealCooldownPolicy and BatchStockService over mocked repositories, so the rules are tested.
        StockPolicy stockPolicy = new StockPolicy(CLOCK);
        service = new InventoryService(
            bloodBankRepository, batchRepository, allocationRepository, writeOffRepository,
            new BatchStockService(batchRepository, allocationRepository, stockPolicy), stockPolicy,
            userRepository, contributionRepository, notificationService, appealCooldownRepository,
            new AppealCooldownPolicy(appealCooldownRepository), new ActingBankResolver(userRepository, bloodBankRepository)
        );
    }

    /**
     * A signed-in staff member of BANK_ID, the only caller that may add stock since Stage 3b.
     */
    private AuthenticatedUser ownBankStaff() {
        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.BLOOD_BANK_STAFF);
        User user = new User();
        user.setId(principal.getId());
        user.setBloodBank(bank(BANK_ID));
        when(userRepository.findById(principal.getId())).thenReturn(Optional.of(user));
        return principal;
    }

    private static BloodBank bank(UUID id) {
        BloodBank bank = new BloodBank();
        bank.setId(id);
        bank.setName("Makeni Regional Blood Bank");
        return bank;
    }

    /** One group's figures as the per-group SQL would return them. */
    private record Group(BloodType getBloodType, long getUsableUnits, long getExpiringSoonUnits,
                         long getExpiredUnits, long getLiveBatches, LocalDate getEarliestUsableExpiry,
                         Instant getLastUpdated) implements GroupStockRow {}

    /** A group holding only usable stock, none of it expiring soon. */
    private static GroupStockRow usable(BloodType type, int units, LocalDate earliestExpiry) {
        boolean soon = !earliestExpiry.isAfter(CUTOFF);
        return new Group(type, units, soon ? units : 0, 0, 1, earliestExpiry, Instant.now());
    }

    private void groupsAre(GroupStockRow... rows) {
        when(batchRepository.summariseGroupsForBank(BANK_ID, TODAY, CUTOFF)).thenReturn(List.of(rows));
    }

    @Test
    void listInventoryAlwaysReturnsAllEightGroups() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        groupsAre(usable(BloodType.O_POSITIVE, 89, TODAY.plusDays(10)));

        List<InventoryItemResponse> items = service.listInventory(BANK_ID);

        assertThat(items).hasSize(8);
        // A group never stocked at this bank still appears — as a zero row.
        InventoryItemResponse neverStocked = items.stream()
            .filter(i -> i.bloodType().equals("AB-")).findFirst().orElseThrow();
        assertThat(neverStocked.unitsAvailable()).isZero();
        assertThat(neverStocked.expiryDate()).isNull();
        assertThat(neverStocked.status()).isEqualTo(StockStatus.CRITICAL);
        assertThat(neverStocked.expiredUnits()).isZero();
        assertThat(neverStocked.batchCount()).isZero();
        assertThat(neverStocked.lastUpdated()).isNull();
    }

    /**
     * The Send Appeal state travels with its stock (Business Rule 12); at 35 units the badge says Low
     * while the button is unavailable, deliberately.
     */
    @Test
    void everyRowCarriesItsAppealButtonStateAndTheTwoThresholdSetsDifferOnPurpose() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        groupsAre(
            usable(BloodType.O_POSITIVE, 35, TODAY.plusDays(10)),  // Low badge, no appeal
            usable(BloodType.A_NEGATIVE, 12, TODAY.plusDays(10))   // Critical, appealable
        );

        List<InventoryItemResponse> items = service.listInventory(BANK_ID);

        InventoryItemResponse lowButNotAppealable = itemFor(items, "O+");
        assertThat(lowButNotAppealable.status()).isEqualTo(StockStatus.LOW);
        assertThat(lowButNotAppealable.appealAvailability()).isEqualTo(AppealAvailability.NO_SHORTAGE);

        InventoryItemResponse shortAndAppealable = itemFor(items, "A-");
        assertThat(shortAndAppealable.status()).isEqualTo(StockStatus.CRITICAL);
        assertThat(shortAndAppealable.appealAvailability()).isEqualTo(AppealAvailability.AVAILABLE);
    }

    /**
     * Expired units are not stock (Business Rule 24): 35 units with 25 expired is a Critical 10 that may appeal.
     */
    @Test
    void expiredUnitsAreReportedSeparatelyAndNeverCountTowardsTheBadgeOrTheAppeal() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        groupsAre(new Group(BloodType.O_POSITIVE, 10, 0, 25, 2, TODAY.plusDays(30), Instant.now()));

        InventoryItemResponse row = itemFor(service.listInventory(BANK_ID), "O+");

        assertThat(row.unitsAvailable()).isEqualTo(10);
        assertThat(row.expiredUnits()).isEqualTo(25);
        assertThat(row.batchCount()).isEqualTo(2);
        assertThat(row.expiryDate()).as("earliest USABLE expiry").isEqualTo(TODAY.plusDays(30));
        assertThat(row.status()).isEqualTo(StockStatus.CRITICAL);
        assertThat(row.appealAvailability()).isEqualTo(AppealAvailability.AVAILABLE);
    }

    /**
     * A running cooldown turns the row's button to "Appeal Sent.".
     */
    @Test
    void aRowWithARunningCooldownReportsItRatherThanOfferingTheButton() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        groupsAre(usable(BloodType.O_NEGATIVE, 8, TODAY.plusDays(10)));

        AppealCooldown cooldown = new AppealCooldown();
        cooldown.setBloodType(BloodType.O_NEGATIVE);
        cooldown.setTriggeredTier(AppealTier.CRITICAL);
        cooldown.setLastSentAt(Instant.now().minus(Duration.ofMinutes(30)));
        when(appealCooldownRepository.findByBloodBankId(BANK_ID)).thenReturn(List.of(cooldown));

        List<InventoryItemResponse> items = service.listInventory(BANK_ID);

        assertThat(itemFor(items, "O-").appealAvailability()).isEqualTo(AppealAvailability.COOLING_DOWN);
        // Only that group is affected — a cooldown is per (bank, group), so the
        // other seven rows are untouched by it.
        assertThat(itemFor(items, "A-").appealAvailability()).isEqualTo(AppealAvailability.AVAILABLE);
    }

    /** One row out of the fixed eight, by its clinical label. */
    private static InventoryItemResponse itemFor(List<InventoryItemResponse> items, String label) {
        return items.stream().filter(i -> i.bloodType().equals(label)).findFirst().orElseThrow();
    }

    /** Stubs the stock a group holds before a batch arrives, and after it. */
    private void usableBeforeAndAfter(BloodType type, int before, int after, LocalDate earliestAfter) {
        when(batchRepository.sumUsableUnits(BANK_ID, type, TODAY)).thenReturn((long) before);
        when(batchRepository.save(any(BloodInventoryBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        groupsAre(usable(type, after, earliestAfter));
    }

    private BloodInventoryBatch savedBatch() {
        ArgumentCaptor<BloodInventoryBatch> captor = ArgumentCaptor.forClass(BloodInventoryBatch.class);
        verify(batchRepository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * A batch that takes the group back over its cooldown's trigger threshold deletes that cooldown (Business Rule 12).
     */
    @Test
    void addBatchClearsACooldownWhenTheBatchCrossesTheThresholdThatTriggeredIt() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        usableBeforeAndAfter(BloodType.O_NEGATIVE, 12, 15, TODAY.plusDays(20));

        AppealCooldown cooldown = new AppealCooldown();
        cooldown.setBloodType(BloodType.O_NEGATIVE);
        cooldown.setTriggeredTier(AppealTier.CRITICAL);
        cooldown.setLastSentAt(Instant.now().minus(Duration.ofMinutes(20)));
        when(appealCooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(cooldown));

        AuthenticatedUser staff = ownBankStaff();

        // 12 + 3 = 15, exactly the CRITICAL tier's recovery threshold.
        InventoryItemResponse result = service.addBatch(
            BANK_ID, "O-", new AddBatchRequest(3, TODAY.plusDays(40)), staff
        );

        verify(appealCooldownRepository).delete(cooldown);
        // And the row handed back already reflects the clearing, rather than
        // reporting the cooldown this very call just ended.
        assertThat(result.unitsAvailable()).isEqualTo(15);
        assertThat(result.appealAvailability()).isEqualTo(AppealAvailability.AVAILABLE);
        // The low-stock check is told usable stock either side of the batch.
        verify(notificationService).notifyStockCrossing(any(), any(), eq(12), eq(15));
    }

    /** A batch that does not reach the threshold leaves the cooldown alone. */
    @Test
    void addBatchLeavesACooldownRunningWhenTheBatchFallsShortOfTheThreshold() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        usableBeforeAndAfter(BloodType.O_NEGATIVE, 12, 14, TODAY.plusDays(20));

        AppealCooldown cooldown = new AppealCooldown();
        cooldown.setBloodType(BloodType.O_NEGATIVE);
        cooldown.setTriggeredTier(AppealTier.CRITICAL);
        cooldown.setLastSentAt(Instant.now().minus(Duration.ofMinutes(20)));
        when(appealCooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(cooldown));

        AuthenticatedUser staff = ownBankStaff();

        // 12 + 2 = 14, one short of the 15 that would end it.
        InventoryItemResponse result = service.addBatch(
            BANK_ID, "O-", new AddBatchRequest(2, TODAY.plusDays(40)), staff
        );

        verify(appealCooldownRepository, never()).delete(any());
        assertThat(result.appealAvailability()).isEqualTo(AppealAvailability.COOLING_DOWN);
    }

    @Test
    void statusThresholdsMatchBusinessRuleThreeExactly() {
        assertThat(StockStatus.fromUnits(14)).isEqualTo(StockStatus.CRITICAL);
        assertThat(StockStatus.fromUnits(15)).isEqualTo(StockStatus.LOW);   // boundary: 15 is not "below 15"
        assertThat(StockStatus.fromUnits(39)).isEqualTo(StockStatus.LOW);
        assertThat(StockStatus.fromUnits(40)).isEqualTo(StockStatus.SUFFICIENT); // boundary: 40 is not "below 40"
        assertThat(StockStatus.fromUnits(0)).isEqualTo(StockStatus.CRITICAL);
    }

    @Test
    void summaryAggregatesTotalsCompositionAndExpiringSoon() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        groupsAre(
            // Sufficient, not expiring soon, and 9 more units in an expired batch.
            new Group(BloodType.A_POSITIVE, 110, 0, 9, 2, TODAY.plusDays(60), Instant.now()),
            usable(BloodType.A_NEGATIVE, 26, TODAY.plusDays(5)),    // low, expiring soon
            usable(BloodType.B_NEGATIVE, 11, TODAY.plusDays(1))     // critical, expiring soon
        );

        InventorySummaryResponse summary = service.getSummary(BANK_ID);

        // 110 + 26 + 11 (the other 5 groups are 0). The 9 expired units are not stock.
        assertThat(summary.totalUnits()).isEqualTo(147);
        assertThat(summary.expiringSoonUnits()).isEqualTo(37); // 26 + 11, within 28 days
        assertThat(summary.expiredUnits()).isEqualTo(9);
        assertThat(summary.expiringSoonWindowDays()).isEqualTo(28);
        assertThat(summary.criticalGroupsCount()).isEqualTo(6); // B- plus the 5 never-stocked groups
        // Nothing issued this month in this fixture.
        assertThat(summary.unitsIssuedThisMonth()).isZero();
        assertThat(summary.composition()).anySatisfy(slice -> {
            if (slice.status() == StockStatus.SUFFICIENT) {
                assertThat(slice.units()).isEqualTo(110);
            }
        });
    }

    @Test
    void addBatchCreatesANewBatchWhenThisGroupHasNeverBeenStocked() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        LocalDate expiry = TODAY.plusDays(30);
        usableBeforeAndAfter(BloodType.O_NEGATIVE, 0, 5, expiry);

        AuthenticatedUser staff = ownBankStaff();

        InventoryItemResponse result = service.addBatch(
            BANK_ID, "O-", new AddBatchRequest(5, expiry), staff
        );

        assertThat(result.bloodType()).isEqualTo("O-");
        assertThat(result.unitsAvailable()).isEqualTo(5);
        assertThat(result.expiryDate()).isEqualTo(expiry);

        BloodInventoryBatch batch = savedBatch();
        assertThat(batch.getUnitsReceived()).isEqualTo(5);
        assertThat(batch.getUnitsRemaining()).isEqualTo(5);
        assertThat(batch.getExpiryDate()).isEqualTo(expiry);
        assertThat(batch.getSource()).isEqualTo(BatchSource.MANUAL);
    }

    /**
     * A new batch keeps its own expiry date (Business Rule 24), while the group still shows the earlier expiry
     * until those units are gone.
     */
    @Test
    void addBatchKeepsTheNewBatchsOwnDateAndTheGroupStillShowsTheEarlierExpiry() {
        LocalDate existingExpiry = TODAY.plusDays(10);
        LocalDate newBatchExpiry = TODAY.plusDays(45); // later than existing

        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        usableBeforeAndAfter(BloodType.O_POSITIVE, 20, 35, existingExpiry);

        AuthenticatedUser staff = ownBankStaff();

        InventoryItemResponse result = service.addBatch(
            BANK_ID, "O+", new AddBatchRequest(15, newBatchExpiry), staff
        );

        assertThat(result.unitsAvailable()).isEqualTo(35); // 20 + 15
        // The existing batch expires sooner and is still on the shelf — that date
        // must not be lost just because a longer-dated batch arrived.
        assertThat(result.expiryDate()).isEqualTo(existingExpiry);
        // And the new batch keeps the date it arrived with.
        BloodInventoryBatch batch = savedBatch();
        assertThat(batch.getExpiryDate()).isEqualTo(newBatchExpiry);
        assertThat(batch.getUnitsReceived()).isEqualTo(15);
    }

    // ---------- the entered expiry date: today .. today + 365 days ----------

    @Test
    void anExpiryDateInThePastIsA400OnTheFieldAndNothingIsSaved() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        AuthenticatedUser staff = ownBankStaff();

        assertThatThrownBy(() -> service.addBatch(BANK_ID, "O+", new AddBatchRequest(5, TODAY.minusDays(1)), staff))
            .isInstanceOf(InvalidFieldException.class)
            .hasMessageContaining("cannot be in the past")
            .satisfies(e -> assertThat(((InvalidFieldException) e).getField()).isEqualTo("expiryDate"));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void anExpiryDateMoreThan365DaysAwayIsA400OnTheFieldAndNothingIsSaved() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        AuthenticatedUser staff = ownBankStaff();

        assertThatThrownBy(() -> service.addBatch(BANK_ID, "O+", new AddBatchRequest(5, TODAY.plusDays(366)), staff))
            .isInstanceOf(InvalidFieldException.class)
            .hasMessageContaining("within 365 days")
            .satisfies(e -> assertThat(((InvalidFieldException) e).getField()).isEqualTo("expiryDate"));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void todayAndExactly365DaysAwayAreBothAccepted() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        when(batchRepository.save(any(BloodInventoryBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchRepository.summariseGroupsForBank(BANK_ID, TODAY, CUTOFF)).thenReturn(List.of());
        AuthenticatedUser staff = ownBankStaff();

        service.addBatch(BANK_ID, "O+", new AddBatchRequest(1, TODAY), staff);
        service.addBatch(BANK_ID, "O+", new AddBatchRequest(1, TODAY.plusDays(365)), staff);
    }

    // ---------- who may add stock and read batch detail ----------

    @Test
    void staffCannotModifyAnotherBanksInventory() {
        when(bloodBankRepository.findById(OTHER_BANK_ID)).thenReturn(Optional.of(bank(OTHER_BANK_ID)));

        AuthenticatedUser staffPrincipal = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.BLOOD_BANK_STAFF);
        User staffUser = new User();
        staffUser.setId(staffPrincipal.getId());
        staffUser.setBloodBank(bank(BANK_ID)); // assigned to BANK_ID, not OTHER_BANK_ID
        when(userRepository.findById(staffPrincipal.getId())).thenReturn(Optional.of(staffUser));

        assertThatThrownBy(() -> service.addBatch(
            OTHER_BANK_ID, "A+", new AddBatchRequest(1, TODAY.plusDays(30)), staffPrincipal
        )).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffCannotReadAnotherBanksBatches() {
        when(bloodBankRepository.findById(OTHER_BANK_ID)).thenReturn(Optional.of(bank(OTHER_BANK_ID)));

        AuthenticatedUser staffPrincipal = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.BLOOD_BANK_STAFF);
        User staffUser = new User();
        staffUser.setId(staffPrincipal.getId());
        staffUser.setBloodBank(bank(BANK_ID));
        when(userRepository.findById(staffPrincipal.getId())).thenReturn(Optional.of(staffUser));

        assertThatThrownBy(() -> service.listBatches(OTHER_BANK_ID, null, staffPrincipal))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.batchHistory(OTHER_BANK_ID, UUID.randomUUID(), staffPrincipal))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aBatchOfAnotherBankIsNotFoundEvenForAnAdministrator() {
        // A 404, the same as an id that matches nothing: guessing ids reveals nothing.
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        BloodInventoryBatch elsewhere = BloodInventoryBatch.manual(bank(OTHER_BANK_ID), BloodType.O_POSITIVE, 4, TODAY);
        UUID batchId = UUID.randomUUID();
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(elsewhere));
        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.ADMIN);

        assertThatThrownBy(() -> service.batchHistory(BANK_ID, batchId, admin)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void staffWithNoAssignedBankCannotModifyAny() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));

        AuthenticatedUser staffPrincipal = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.BLOOD_BANK_STAFF);
        User staffUser = new User();
        staffUser.setId(staffPrincipal.getId());
        staffUser.setBloodBank(null); // never assigned by an administrator
        when(userRepository.findById(staffPrincipal.getId())).thenReturn(Optional.of(staffUser));

        assertThatThrownBy(() -> service.addBatch(
            BANK_ID, "A+", new AddBatchRequest(1, TODAY.plusDays(30)), staffPrincipal
        )).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffCanModifyTheirOwnBank() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        usableBeforeAndAfter(BloodType.A_POSITIVE, 0, 10, TODAY.plusDays(30));

        AuthenticatedUser staffPrincipal = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.BLOOD_BANK_STAFF);
        User staffUser = new User();
        staffUser.setId(staffPrincipal.getId());
        staffUser.setBloodBank(bank(BANK_ID));
        when(userRepository.findById(staffPrincipal.getId())).thenReturn(Optional.of(staffUser));

        InventoryItemResponse result = service.addBatch(
            BANK_ID, "A+", new AddBatchRequest(10, TODAY.plusDays(30)), staffPrincipal
        );

        assertThat(result.unitsAvailable()).isEqualTo(10);
    }

    // ---------- ADMIN reads stock but cannot change it (Stage 3b) ----------

    @Test
    void anAdministratorCannotAddABatchAndNothingIsReadOrSaved() {
        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.ADMIN);

        assertThatThrownBy(() -> service.addBatch(BANK_ID, "O+", new AddBatchRequest(5, TODAY.plusDays(30)), admin))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessage("Only the blood bank's own staff can change its stock.");

        // Refused on the role alone, before the bank is even looked up.
        verify(bloodBankRepository, never()).findById(any());
        verify(batchRepository, never()).save(any());
        verify(notificationService, never()).notifyStockCrossing(any(), any(), anyInt(), anyInt());
    }

    @Test
    void aSuspendedStaffMemberCannotAddABatchEvenToTheirOwnBank() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        AuthenticatedUser staffPrincipal = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.BLOOD_BANK_STAFF);
        User staffUser = new User();
        staffUser.setId(staffPrincipal.getId());
        staffUser.setBloodBank(bank(BANK_ID));
        staffUser.setStatus(AccountStatus.SUSPENDED);
        when(userRepository.findById(staffPrincipal.getId())).thenReturn(Optional.of(staffUser));

        assertThatThrownBy(() -> service.addBatch(
            BANK_ID, "A+", new AddBatchRequest(1, TODAY.plusDays(30)), staffPrincipal
        )).isInstanceOf(AccessDeniedException.class);
        verify(batchRepository, never()).save(any());
    }

    @Test
    void anAdministratorCanStillReadAnyBanksBatches() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        BloodInventoryBatch batch = BloodInventoryBatch.manual(bank(BANK_ID), BloodType.O_POSITIVE, 4, TODAY.plusDays(10));
        when(batchRepository.findLiveForBank(BANK_ID)).thenReturn(List.of(batch));
        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.ADMIN);

        assertThat(service.listBatches(BANK_ID, null, admin)).singleElement()
            .satisfies(row -> assertThat(row.unitsRemaining()).isEqualTo(4));
    }

    @Test
    void unknownBankIsReportedAsNotFound() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listInventory(BANK_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void unrecognisedBloodTypeLabelIsRejected() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        AuthenticatedUser staff = ownBankStaff();

        assertThatThrownBy(() -> service.addBatch(
            BANK_ID, "Z+", new AddBatchRequest(1, TODAY.plusDays(1)), staff
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
