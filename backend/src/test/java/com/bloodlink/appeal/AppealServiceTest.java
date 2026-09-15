package com.bloodlink.appeal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
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
import com.bloodlink.domain.BloodInventory;
import com.bloodlink.domain.enums.AppealTier;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.notification.NotificationService.AppealReach;
import com.bloodlink.repository.AppealCooldownRepository;
import com.bloodlink.repository.BloodInventoryRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;

/**
 * AppealService is deliberately thin — which bank is acting is
 * ActingBankResolver's answer, and who hears about it is NotificationService's —
 * so what is worth pinning down here is exactly the seams between them: the blood
 * group is parsed and validated before either collaborator is trusted with it, the
 * AppealReach both of them agree on is passed through to the wire unchanged, and
 * Business Rule 12's cooldown is enforced on the send itself rather than only on
 * the button that normally precedes it.
 *
 * The cooldown's own boundary arithmetic — which tier, which threshold, which
 * window — is pinned down in AppealCooldownPolicyTest. What is tested here is that
 * this service actually consults it, refuses when told to, and records a cooldown
 * only after a send has really gone out.
 */
@ExtendWith(MockitoExtension.class)
class AppealServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private ActingBankResolver actingBankResolver;
    @Mock private NotificationService notificationService;
    @Mock private AppealCooldownRepository cooldownRepository;
    @Mock private BloodInventoryRepository bloodInventoryRepository;

    private AppealService service;

    @BeforeEach
    void setUp() {
        // A real AppealCooldownPolicy over the mocked repository, not a mock of it —
        // the same choice RequestServiceTest makes for ActingBankResolver. The
        // cooldown is a rule this service is now responsible for enforcing, so
        // stubbing it away would leave that enforcement untested here.
        //
        // Both mocks return Optional.empty() by default, which reads as "no stock
        // row and no prior appeal" — 0 units is the worst possible shortage, so the
        // send tests below that predate the cooldown are unaffected by it.
        service = new AppealService(
            actingBankResolver,
            notificationService,
            new AppealCooldownPolicy(cooldownRepository),
            bloodInventoryRepository
        );
    }

    /** This bank's stock row for O-, at the given level. */
    private void stubStock(BloodType type, int units) {
        BloodInventory row = new BloodInventory();
        row.setBloodType(type);
        row.setUnitsAvailable(units);
        when(bloodInventoryRepository.findByBloodBankIdAndBloodType(BANK_ID, type))
            .thenReturn(Optional.of(row));
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
        // One shared instance rather than two separate principal() calls:
        // AuthenticatedUser has no equals() override, so Mockito's argument
        // matching for the stub below would otherwise compare it by reference
        // against a different instance built for the real call, and never match.
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
        // The bank is still resolved first — an ADMIN who has not named a bank
        // should be told that, even on an otherwise-malformed request — but a bad
        // blood type must stop the appeal before NotificationService is reached.
        when(actingBankResolver.resolve(any(), any(), any())).thenReturn(bank());

        assertThatThrownBy(() -> service.sendAppeal(new DonationAppealPayload("XYZ", null, null), principal()))
            .isInstanceOf(IllegalArgumentException.class);

        verify(notificationService, never()).sendDonationAppeal(any(), any(), any());
    }

    @Test
    void sendAppealPropagatesAnActingBankResolverRefusalUntouched() {
        // Business Rule 1 delegated to ActingBankResolver — a hospital or donor
        // reaching this method at all would already be blocked by SecurityConfig,
        // but the service must not swallow or reword the refusal if it happens.
        when(actingBankResolver.resolve(any(), any(), any()))
            .thenThrow(new AccessDeniedException("Only blood bank staff can send a donation appeal."));

        assertThatThrownBy(() -> service.sendAppeal(new DonationAppealPayload("A+", null, null), principal()))
            .isInstanceOf(AccessDeniedException.class);

        verify(notificationService, never()).sendDonationAppeal(any(), any(), any());
    }

    // ---------- the cooldown, enforced on the send itself (Business Rule 12) ----------

    /**
     * The disabled button is a courtesy; this is the control. A send that arrives
     * anyway — a stale tab, a retried click, curl — is refused with a 409 and
     * nothing goes out, because otherwise the whole feature would be cosmetic.
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
     * A group that recovered past its threshold and then relapsed can appeal again
     * straight away, even inside what would have been the original window.
     *
     * The recovery deleted the cooldown when it happened (InventoryService.addBatch
     * calls clearIfRecovered), so by the time stock falls again there is no row left
     * to block on — which is what "no prior cooldown" below represents. The relapse
     * is a new shortage with its own chance to appeal, not the tail of a spent timer.
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
     * A group this bank has never stocked has no inventory row at all. That is 0
     * units — the worst shortage there is — not a missing figure that should block
     * the appeal, which is exactly when donors are most needed.
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
     * The cooldown gates whether the bank may ask; Business Rule 2 gates which
     * donors are safe to ask. Adding the first changed nothing about the second —
     * the reach figures still come back from NotificationService untouched,
     * including the donors it held back.
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
