package com.bloodlink.appeal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.notification.NotificationService.AppealReach;
import com.bloodlink.security.AuthenticatedUser;

/**
 * AppealService is deliberately thin — which bank is acting is
 * ActingBankResolver's answer, and who hears about it is NotificationService's —
 * so what is worth pinning down here is exactly the seam between them: the blood
 * group is parsed and validated before either collaborator is trusted with it, and
 * the AppealReach both of them agree on is passed through to the wire unchanged.
 */
@ExtendWith(MockitoExtension.class)
class AppealServiceTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private ActingBankResolver actingBankResolver;
    @Mock private NotificationService notificationService;

    private AppealService service;

    @BeforeEach
    void setUp() {
        service = new AppealService(actingBankResolver, notificationService);
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
}
