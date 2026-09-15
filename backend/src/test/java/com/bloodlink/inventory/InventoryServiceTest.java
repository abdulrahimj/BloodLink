package com.bloodlink.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import com.bloodlink.domain.AppealCooldown;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventory;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AppealAvailability;
import com.bloodlink.domain.enums.AppealTier;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.StockStatus;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.inventory.dto.InventorySummaryResponse;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.AppealCooldownRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.NotFoundException;

/**
 * Covers CLAUDE.md's own numbers exactly (Business Rule 3: below 15 = Critical,
 * below 40 = Low), the "always eight rows" contract both frontend screens assume,
 * and the own-bank-only write rule agreed with the project owner.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID OTHER_BANK_ID = UUID.randomUUID();

    @Mock private BloodBankRepository bloodBankRepository;
    @Mock private BloodInventoryRepository bloodInventoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private BloodRequestRepository bloodRequestRepository;
    @Mock private NotificationService notificationService;
    @Mock private AppealCooldownRepository appealCooldownRepository;

    private InventoryService service;

    @BeforeEach
    void setUp() {
        // A real AppealCooldownPolicy over the mocked repository, not a mock of it —
        // the same choice RequestServiceTest makes for ActingBankResolver. Stubbing
        // it would make every appealAvailability assertion below a test of the stub
        // rather than of the rule the screen actually gets.
        service = new InventoryService(
            bloodBankRepository, bloodInventoryRepository, userRepository, bloodRequestRepository,
            notificationService, appealCooldownRepository,
            new AppealCooldownPolicy(appealCooldownRepository)
        );
    }

    private static BloodBank bank(UUID id) {
        BloodBank bank = new BloodBank();
        bank.setId(id);
        bank.setName("Makeni Regional Blood Bank");
        return bank;
    }

    private static BloodInventory row(BloodType type, int units, LocalDate expiry) {
        BloodInventory row = new BloodInventory();
        row.setBloodType(type);
        row.setUnitsAvailable(units);
        row.setExpiryDate(expiry);
        return row;
    }

    @Test
    void listInventoryAlwaysReturnsAllEightGroups() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        when(bloodInventoryRepository.findByBloodBankId(BANK_ID)).thenReturn(
            List.of(row(BloodType.O_POSITIVE, 89, LocalDate.now().plusDays(10)))
        );

        List<InventoryItemResponse> items = service.listInventory(BANK_ID);

        assertThat(items).hasSize(8);
        // A group never stocked at this bank still appears — as a zero row.
        InventoryItemResponse neverStocked = items.stream()
            .filter(i -> i.bloodType().equals("AB-")).findFirst().orElseThrow();
        assertThat(neverStocked.unitsAvailable()).isZero();
        assertThat(neverStocked.expiryDate()).isNull();
        assertThat(neverStocked.status()).isEqualTo(StockStatus.CRITICAL);
    }

    /**
     * The Send Appeal button's state travels with the stock it is derived from
     * (Business Rule 12), so the screen cannot draw the button from one moment's
     * cooldown and another moment's units.
     *
     * Also the clearest place to see the two threshold sets side by side: at 35
     * units the badge says Low (Rule 3's Low band runs to 40) while the button is
     * unavailable (an appeal needs fewer than 30). Both are correct, and they are
     * meant to disagree.
     */
    @Test
    void everyRowCarriesItsAppealButtonStateAndTheTwoThresholdSetsDifferOnPurpose() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        when(bloodInventoryRepository.findByBloodBankId(BANK_ID)).thenReturn(List.of(
            row(BloodType.O_POSITIVE, 35, LocalDate.now().plusDays(10)),  // Low badge, no appeal
            row(BloodType.A_NEGATIVE, 12, LocalDate.now().plusDays(10))   // Critical, appealable
        ));

        List<InventoryItemResponse> items = service.listInventory(BANK_ID);

        InventoryItemResponse lowButNotAppealable = itemFor(items, "O+");
        assertThat(lowButNotAppealable.status()).isEqualTo(StockStatus.LOW);
        assertThat(lowButNotAppealable.appealAvailability()).isEqualTo(AppealAvailability.NO_SHORTAGE);

        InventoryItemResponse shortAndAppealable = itemFor(items, "A-");
        assertThat(shortAndAppealable.status()).isEqualTo(StockStatus.CRITICAL);
        assertThat(shortAndAppealable.appealAvailability()).isEqualTo(AppealAvailability.AVAILABLE);
    }

    /**
     * A cooldown this bank holds turns its row's button to "Appeal Sent." — the
     * whole point of the feature, and the part that could not work before, since
     * nothing recorded that an appeal had been sent at all.
     */
    @Test
    void aRowWithARunningCooldownReportsItRatherThanOfferingTheButton() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        when(bloodInventoryRepository.findByBloodBankId(BANK_ID)).thenReturn(
            List.of(row(BloodType.O_NEGATIVE, 8, LocalDate.now().plusDays(10)))
        );

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

    /**
     * Adding stock is the only way a group's units can rise, so it is the only
     * place an appeal cooldown can be recovered out of (Business Rule 12). A batch
     * that takes the group back over the threshold that triggered its cooldown
     * deletes that cooldown for good.
     */
    @Test
    void addBatchClearsACooldownWhenTheBatchCrossesTheThresholdThatTriggeredIt() {
        BloodInventory existing = row(BloodType.O_NEGATIVE, 12, LocalDate.now().plusDays(20));
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        when(bloodInventoryRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(existing));
        when(bloodInventoryRepository.save(any(BloodInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        AppealCooldown cooldown = new AppealCooldown();
        cooldown.setBloodType(BloodType.O_NEGATIVE);
        cooldown.setTriggeredTier(AppealTier.CRITICAL);
        cooldown.setLastSentAt(Instant.now().minus(Duration.ofMinutes(20)));
        when(appealCooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(cooldown));

        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.ADMIN);

        // 12 + 3 = 15, exactly the CRITICAL tier's recovery threshold.
        InventoryItemResponse result = service.addBatch(
            BANK_ID, "O-", new AddBatchRequest(3, LocalDate.now().plusDays(40)), admin
        );

        verify(appealCooldownRepository).delete(cooldown);
        // And the row handed back already reflects the clearing, rather than
        // reporting the cooldown this very call just ended.
        assertThat(result.unitsAvailable()).isEqualTo(15);
        assertThat(result.appealAvailability()).isEqualTo(AppealAvailability.AVAILABLE);
    }

    /** A batch that does not reach the threshold leaves the cooldown alone. */
    @Test
    void addBatchLeavesACooldownRunningWhenTheBatchFallsShortOfTheThreshold() {
        BloodInventory existing = row(BloodType.O_NEGATIVE, 12, LocalDate.now().plusDays(20));
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        when(bloodInventoryRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(existing));
        when(bloodInventoryRepository.save(any(BloodInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        AppealCooldown cooldown = new AppealCooldown();
        cooldown.setBloodType(BloodType.O_NEGATIVE);
        cooldown.setTriggeredTier(AppealTier.CRITICAL);
        cooldown.setLastSentAt(Instant.now().minus(Duration.ofMinutes(20)));
        when(appealCooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(cooldown));

        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.ADMIN);

        // 12 + 2 = 14, one short of the 15 that would end it.
        InventoryItemResponse result = service.addBatch(
            BANK_ID, "O-", new AddBatchRequest(2, LocalDate.now().plusDays(40)), admin
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
        when(bloodInventoryRepository.findByBloodBankId(BANK_ID)).thenReturn(List.of(
            row(BloodType.A_POSITIVE, 110, LocalDate.now().plusDays(60)),  // sufficient, not expiring soon
            row(BloodType.A_NEGATIVE, 26, LocalDate.now().plusDays(5)),    // low, expiring soon
            row(BloodType.B_NEGATIVE, 11, LocalDate.now().plusDays(1))     // critical, expiring soon
        ));

        InventorySummaryResponse summary = service.getSummary(BANK_ID);

        assertThat(summary.totalUnits()).isEqualTo(147); // 110 + 26 + 11 (the other 5 groups are 0)
        assertThat(summary.expiringSoonUnits()).isEqualTo(37); // 26 + 11, within 28 days
        assertThat(summary.criticalGroupsCount()).isEqualTo(6); // B- plus the 5 never-stocked groups
        // Honest zero: nothing can be FULFILLED yet without the Requests module.
        assertThat(summary.unitsIssuedThisMonth()).isZero();
        assertThat(summary.composition()).anySatisfy(slice -> {
            if (slice.status() == StockStatus.SUFFICIENT) {
                assertThat(slice.units()).isEqualTo(110);
            }
        });
    }

    @Test
    void addBatchCreatesTheRowWhenThisGroupHasNeverBeenStocked() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        when(bloodInventoryRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.empty());
        when(bloodInventoryRepository.save(any(BloodInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.ADMIN);
        LocalDate expiry = LocalDate.now().plusDays(30);

        InventoryItemResponse result = service.addBatch(
            BANK_ID, "O-", new AddBatchRequest(5, expiry), admin
        );

        assertThat(result.bloodType()).isEqualTo("O-");
        assertThat(result.unitsAvailable()).isEqualTo(5);
        assertThat(result.expiryDate()).isEqualTo(expiry);
    }

    @Test
    void addBatchAccumulatesUnitsAndKeepsTheEarlierExpiryDate() {
        LocalDate existingExpiry = LocalDate.now().plusDays(10);
        LocalDate newBatchExpiry = LocalDate.now().plusDays(45); // later than existing
        BloodInventory existing = row(BloodType.O_POSITIVE, 20, existingExpiry);

        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        when(bloodInventoryRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_POSITIVE))
            .thenReturn(Optional.of(existing));
        when(bloodInventoryRepository.save(any(BloodInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.ADMIN);

        InventoryItemResponse result = service.addBatch(
            BANK_ID, "O+", new AddBatchRequest(15, newBatchExpiry), admin
        );

        assertThat(result.unitsAvailable()).isEqualTo(35); // 20 + 15
        // The existing batch expires sooner and is still on the shelf — that date
        // must not be lost just because a longer-dated batch arrived.
        assertThat(result.expiryDate()).isEqualTo(existingExpiry);
    }

    @Test
    void staffCannotModifyAnotherBanksInventory() {
        when(bloodBankRepository.findById(OTHER_BANK_ID)).thenReturn(Optional.of(bank(OTHER_BANK_ID)));

        AuthenticatedUser staffPrincipal = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.BLOOD_BANK_STAFF);
        User staffUser = new User();
        staffUser.setId(staffPrincipal.getId());
        staffUser.setBloodBank(bank(BANK_ID)); // assigned to BANK_ID, not OTHER_BANK_ID
        when(userRepository.findById(staffPrincipal.getId())).thenReturn(Optional.of(staffUser));

        assertThatThrownBy(() -> service.addBatch(
            OTHER_BANK_ID, "A+", new AddBatchRequest(1, LocalDate.now().plusDays(30)), staffPrincipal
        )).isInstanceOf(AccessDeniedException.class);
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
            BANK_ID, "A+", new AddBatchRequest(1, LocalDate.now().plusDays(30)), staffPrincipal
        )).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffCanModifyTheirOwnBank() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        when(bloodInventoryRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.A_POSITIVE))
            .thenReturn(Optional.empty());
        when(bloodInventoryRepository.save(any(BloodInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthenticatedUser staffPrincipal = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.BLOOD_BANK_STAFF);
        User staffUser = new User();
        staffUser.setId(staffPrincipal.getId());
        staffUser.setBloodBank(bank(BANK_ID));
        when(userRepository.findById(staffPrincipal.getId())).thenReturn(Optional.of(staffUser));

        InventoryItemResponse result = service.addBatch(
            BANK_ID, "A+", new AddBatchRequest(10, LocalDate.now().plusDays(30)), staffPrincipal
        );

        assertThat(result.unitsAvailable()).isEqualTo(10);
    }

    @Test
    void unknownBankIsReportedAsNotFound() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listInventory(BANK_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void unrecognisedBloodTypeLabelIsRejected() {
        when(bloodBankRepository.findById(BANK_ID)).thenReturn(Optional.of(bank(BANK_ID)));
        AuthenticatedUser admin = new AuthenticatedUser(UUID.randomUUID(), "+232", "hash", Role.ADMIN);

        assertThatThrownBy(() -> service.addBatch(
            BANK_ID, "Z+", new AddBatchRequest(1, LocalDate.now().plusDays(1)), admin
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
