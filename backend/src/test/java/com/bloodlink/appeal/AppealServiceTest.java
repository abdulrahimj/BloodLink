package com.bloodlink.appeal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
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

import com.bloodlink.appeal.dto.AppealReachResponse;
import com.bloodlink.appeal.dto.DonationAppealPayload;
import com.bloodlink.appeal.dto.DonationAppealResponse;
import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.AppealCooldown;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.enums.AppealTier;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.notification.NotificationService.AppealReach;
import com.bloodlink.repository.AppealCooldownRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.ContributionAllocationRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.stock.BatchStockService;
import com.bloodlink.stock.StockPolicy;
import com.bloodlink.web.error.ConflictException;

/**
 * AppealService's seams: the blood group is validated first, the reach passes through unchanged, and
 * Business Rule 12's cooldown is enforced on the send. Tier arithmetic is in AppealCooldownPolicyTest.
 */
@ExtendWith(MockitoExtension.class)
class AppealServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.now(CLOCK);

    @Mock private ActingBankResolver actingBankResolver;
    @Mock private NotificationService notificationService;
    @Mock private AppealCooldownRepository cooldownRepository;
    @Mock private BloodInventoryBatchRepository batchRepository;
    @Mock private ContributionAllocationRepository allocationRepository;

    private AppealService service;

    @BeforeEach
    void setUp() {
        // A real AppealCooldownPolicy and BatchStockService over mocked repositories, so the cooldown is enforced.
        // Defaults are 0 usable units and no cooldown, the worst shortage, so older send tests are unaffected.
        service = new AppealService(
            actingBankResolver,
            notificationService,
            new AppealCooldownPolicy(cooldownRepository),
            new BatchStockService(batchRepository, allocationRepository, new StockPolicy(CLOCK))
        );
    }

    /** This bank's USABLE units of one group, at the given level (Business Rule 24). */
    private void stubStock(BloodType type, int units) {
        when(batchRepository.sumUsableUnits(BANK_ID, type, TODAY)).thenReturn((long) units);
    }

    /** An existing cooldown for this bank and group, sent {@code elapsed} ago. */
    private void stubCooldown(BloodType type, AppealTier tier, Duration elapsed) {
        AppealCooldown cooldown = new AppealCooldown();
        cooldown.setBloodType(type);
        cooldown.setTriggeredTier(tier);
        cooldown.setLastSentAt(Instant.now().minus(elapsed));
        when(cooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, type))
            .thenReturn(Optional.of(cooldown));
    }

    private static BloodBank bank() {
        BloodBank bank = new BloodBank();
        bank.setId(BANK_ID);
        bank.setName("Holy Spirit Blood Bank");
        return bank;
    }

    private static AuthenticatedUser principal() {
        return new AuthenticatedUser(USER_ID, "+232 76 000 002", "hash", Role.BLOOD_BANK_STAFF);
    }

    // ---------- previewReach ----------

    @Test
    void previewReachAsksNotificationServiceForTheParsedGroupAndPassesTheFiguresThrough() {
        when(notificationService.previewAppealReach(BloodType.O_NEGATIVE))
            .thenReturn(new AppealReach(12, 3));

        AppealReachResponse response = service.previewReach("O-");

        assertThat(response.bloodType()).isEqualTo("O-");
        assertThat(response.eligibleDonors()).isEqualTo(12);
        assertThat(response.donorsInRecovery()).isEqualTo(3);
        // Nothing is sent by a preview — that guarantee lives in
        // NotificationService.previewAppealReach itself, which this only calls.
    }

    @Test
    void previewReachTrimsSurroundingWhitespaceBeforeParsing() {
        when(notificationService.previewAppealReach(BloodType.A_POSITIVE)).thenReturn(new AppealReach(0, 0));

        service.previewReach("  A+  ");

        verify(notificationService).previewAppealReach(BloodType.A_POSITIVE);
    }

    @Test
    void previewReachRejectsAnUnrecognisedGroupBeforeAskingNotificationService() {
        assertThatThrownBy(() -> service.previewReach("XYZ"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(notificationService, never()).previewAppealReach(any());
    }

    // ---------- sendAppeal ----------

    @Test
    void sendAppealResolvesTheActingBankAndSendsThroughNotificationService() {
        // One shared principal: AuthenticatedUser has no equals(), so separate instances would never match the stub.
        AuthenticatedUser principal = principal();
        when(actingBankResolver.resolve(null, principal, "send a donation appeal")).thenReturn(bank());
        when(notificationService.sendDonationAppeal(any(), eq(BloodType.O_NEGATIVE), any()))
            .thenReturn(new AppealReach(5, 2));

        DonationAppealResponse response = service.sendAppeal(
            new DonationAppealPayload("O-", "Drive this Saturday.", null), principal
        );

        assertThat(response.bloodBankId()).isEqualTo(BANK_ID);
        assertThat(response.bloodBankName()).isEqualTo("Holy Spirit Blood Bank");
        assertThat(response.bloodType()).isEqualTo("O-");
        assertThat(response.donorsNotified()).isEqualTo(5);
        assertThat(response.donorsSkippedIneligible()).isEqualTo(2);
    }

    @Test
    void sendAppealPassesTheActingBankItResolvedNotThePayloadsToNotificationService() {
        BloodBank resolvedBank = bank();
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(resolvedBank);
        when(notificationService.sendDonationAppeal(any(), any(), any())).thenReturn(new AppealReach(0, 0));

        service.sendAppeal(new DonationAppealPayload("A+", null, null), principal());

        ArgumentCaptor<BloodBank> bankCaptor = ArgumentCaptor.forClass(BloodBank.class);
        verify(notificationService).sendDonationAppeal(bankCaptor.capture(), eq(BloodType.A_POSITIVE), eq(null));
        assertThat(bankCaptor.getValue()).isSameAs(resolvedBank);
    }

    @Test
    void sendAppealNormalisesABlankNoteToNull() {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());
        when(notificationService.sendDonationAppeal(any(), any(), any())).thenReturn(new AppealReach(0, 0));

        service.sendAppeal(new DonationAppealPayload("A+", "   ", null), principal());

        verify(notificationService).sendDonationAppeal(any(), any(), eq(null));
    }

    @Test
    void sendAppealTrimsAGenuineNoteRatherThanDroppingIt() {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());
        when(notificationService.sendDonationAppeal(any(), any(), any())).thenReturn(new AppealReach(0, 0));

        service.sendAppeal(new DonationAppealPayload("A+", "  Come Saturday.  ", null), principal());

        verify(notificationService).sendDonationAppeal(any(), any(), eq("Come Saturday."));
    }

    @Test
    void sendAppealRejectsAnUnrecognisedGroupAfterTheBankIsResolvedButBeforeAnythingIsSent() {
        // The bank is still resolved first, but a bad blood type stops the appeal before NotificationService.
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());

        assertThatThrownBy(() -> service.sendAppeal(new DonationAppealPayload("XYZ", null, null), principal()))
            .isInstanceOf(IllegalArgumentException.class);

        verify(notificationService, never()).sendDonationAppeal(any(), any(), any());
    }

    @Test
    void sendAppealPropagatesAnActingBankResolverRefusalUntouched() {
        // Business Rule 1 is ActingBankResolver's refusal; the service must pass it on unchanged.
        when(actingBankResolver.resolve(any(), any(), any()))
            .thenThrow(new AccessDeniedException("Only blood bank staff can send a donation appeal."));

        assertThatThrownBy(() -> service.sendAppeal(new DonationAppealPayload("A+", null, null), principal()))
            .isInstanceOf(AccessDeniedException.class);

        verify(notificationService, never()).sendDonationAppeal(any(), any(), any());
    }

    // ---------- the cooldown, enforced on the send itself (Business Rule 12) ----------

    /**
     * The disabled button is a courtesy; this is the control: a send during a cooldown is a 409 and nothing goes out.
     */
    @Test
    void sendAppealIsRefusedWhileACooldownIsStillRunning() {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());
        stubStock(BloodType.O_NEGATIVE, 10);
        stubCooldown(BloodType.O_NEGATIVE, AppealTier.CRITICAL, Duration.ofHours(1));

        assertThatThrownBy(() -> service.sendAppeal(
            new DonationAppealPayload("O-", null, null), principal()
        ))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already sent");

        // Nothing sent, and no cooldown rewritten — a refused send must not extend
        // the cooldown that refused it.
        verify(notificationService, never()).sendDonationAppeal(any(), any(), any());
        verify(cooldownRepository, never()).save(any());
    }

    /**
     * The 30-39 band: a Low badge in the table, but above the appeal threshold, so
     * no appeal may be sent even though this bank has never appealed for the group.
     */
    @Test
    void sendAppealIsRefusedWithNoShortageEvenWithNoPriorCooldown() {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());
        stubStock(BloodType.O_NEGATIVE, 30);

        assertThatThrownBy(() -> service.sendAppeal(
            new DonationAppealPayload("O-", null, null), principal()
        ))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("not short enough");

        verify(notificationService, never()).sendDonationAppeal(any(), any(), any());
        verify(cooldownRepository, never()).save(any());
    }

    /**
     * A relapse after recovery can appeal straight away: recovery already deleted the cooldown,
     * which "no prior cooldown" here represents.
     */
    @Test
    void sendAppealIsAllowedForARelapseAfterAnEarlierRecoveryClearedTheCooldown() {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());
        // Back down to 11 shortly after an appeal that was sent at 10 and then
        // resolved by a batch — no cooldown row survives that recovery.
        stubStock(BloodType.O_NEGATIVE, 11);
        when(notificationService.sendDonationAppeal(any(), any(), any())).thenReturn(new AppealReach(4, 1));

        DonationAppealResponse response = service.sendAppeal(
            new DonationAppealPayload("O-", null, null), principal()
        );

        assertThat(response.donorsNotified()).isEqualTo(4);
        verify(notificationService).sendDonationAppeal(any(), eq(BloodType.O_NEGATIVE), any());
    }

    /**
     * A successful send records the cooldown, tiered by stock at that moment — this
     * is what makes the *next* click's button state real rather than a guess.
     */
    @Test
    void aSuccessfulSendRecordsTheCooldownTieredByStockAtSendTime() {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());
        stubStock(BloodType.O_NEGATIVE, 12);
        when(notificationService.sendDonationAppeal(any(), any(), any())).thenReturn(new AppealReach(7, 0));

        service.sendAppeal(new DonationAppealPayload("O-", null, null), principal());

        ArgumentCaptor<AppealCooldown> saved = ArgumentCaptor.forClass(AppealCooldown.class);
        verify(cooldownRepository).save(saved.capture());
        assertThat(saved.getValue().getTriggeredTier()).isEqualTo(AppealTier.CRITICAL); // 12 < 15
        assertThat(saved.getValue().getBloodType()).isEqualTo(BloodType.O_NEGATIVE);
        assertThat(saved.getValue().getBloodBank().getId()).isEqualTo(BANK_ID);
        assertThat(saved.getValue().getLastSentAt()).isNotNull();
    }

    @Test
    void aSuccessfulSendAtFifteenUnitsOrMoreRecordsTheLowTierNotCritical() {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());
        stubStock(BloodType.O_NEGATIVE, 29);
        when(notificationService.sendDonationAppeal(any(), any(), any())).thenReturn(new AppealReach(3, 0));

        service.sendAppeal(new DonationAppealPayload("O-", null, null), principal());

        ArgumentCaptor<AppealCooldown> saved = ArgumentCaptor.forClass(AppealCooldown.class);
        verify(cooldownRepository).save(saved.capture());
        assertThat(saved.getValue().getTriggeredTier()).isEqualTo(AppealTier.LOW);
    }

    /**
     * A never-stocked group is 0 units, the worst shortage, not a missing figure that blocks the appeal.
     */
    @Test
    void aGroupWithNoStockRowIsTreatedAsZeroUnitsRatherThanBlockingTheAppeal() {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());
        // No stubStock call: the repository's default empty Optional is the point.
        when(notificationService.sendDonationAppeal(any(), any(), any())).thenReturn(new AppealReach(2, 0));

        service.sendAppeal(new DonationAppealPayload("O-", null, null), principal());

        ArgumentCaptor<AppealCooldown> saved = ArgumentCaptor.forClass(AppealCooldown.class);
        verify(cooldownRepository).save(saved.capture());
        assertThat(saved.getValue().getTriggeredTier()).isEqualTo(AppealTier.CRITICAL);
    }

    /**
     * The cooldown gates whether the bank may ask; Business Rule 2 still decides which donors,
     * and its figures come back untouched.
     */
    @Test
    void theCooldownDoesNotDisturbBusinessRuleTwosOwnFilter() {
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());
        stubStock(BloodType.O_NEGATIVE, 5);
        when(notificationService.sendDonationAppeal(any(), any(), any())).thenReturn(new AppealReach(6, 9));

        DonationAppealResponse response = service.sendAppeal(
            new DonationAppealPayload("O-", null, null), principal()
        );

        assertThat(response.donorsNotified()).isEqualTo(6);
        assertThat(response.donorsSkippedIneligible()).isEqualTo(9);
    }
}
