package com.bloodlink.appeal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.bloodlink.domain.AppealCooldown;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.enums.AppealAvailability;
import com.bloodlink.domain.enums.AppealTier;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.repository.AppealCooldownRepository;

/**
 * Business Rule 12 at its exact boundaries: recovery past the trigger threshold and the timer running out,
 * per tier. The clock is passed in so the 5h and 24h edges are tested exactly.
 */
@ExtendWith(MockitoExtension.class)
class AppealCooldownPolicyTest {

    private static final UUID BANK_ID = UUID.randomUUID();
    private static final Instant SENT_AT = Instant.parse("2026-09-15T08:00:00Z");

    @Mock private AppealCooldownRepository cooldownRepository;

    private AppealCooldownPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new AppealCooldownPolicy(cooldownRepository);
    }

    // ---------- fixtures ----------

    private static BloodBank bank() {
        BloodBank bank = new BloodBank();
        bank.setId(BANK_ID);
        bank.setName("Makeni Regional Blood Bank");
        return bank;
    }

    /** A cooldown started at SENT_AT under the given tier. */
    private static AppealCooldown cooldown(AppealTier tier) {
        AppealCooldown row = new AppealCooldown();
        row.setBloodBank(bank());
        row.setBloodType(BloodType.O_NEGATIVE);
        row.setLastSentAt(SENT_AT);
        row.setTriggeredTier(tier);
        return row;
    }

    /** How things stand {@code elapsed} after the appeal went out. */
    private static Instant after(Duration elapsed) {
        return SENT_AT.plus(elapsed);
    }

    // ---------- rule 1: no shortage, nothing to send ----------

    /**
     * 30–39 units shows a "Low" badge but can never be appealed for: refused on stock alone, no cooldown involved.
     */
    @Test
    void unitsFromThirtyToThirtyNineNeverAllowASendEvenWithNoCooldownAtAll() {
        for (int units = 30; units <= 39; units++) {
            assertThat(policy.evaluate(units, null, after(Duration.ZERO)))
                .as("%d units — Low badge, but above the appeal threshold", units)
                .isEqualTo(AppealAvailability.NO_SHORTAGE);
        }
    }

    @Test
    void noShortageBeatsAnActiveCooldownRatherThanBeingMaskedByIt() {
        // Rule 1 is checked first, so a recovered group reports the real reason, not a cooldown no longer in force.
        assertThat(policy.evaluate(30, cooldown(AppealTier.CRITICAL), after(Duration.ofMinutes(1))))
            .isEqualTo(AppealAvailability.NO_SHORTAGE);
    }

    @Test
    void aShortageWithNoPriorAppealIsAlwaysSendable() {
        assertThat(policy.evaluate(29, null, after(Duration.ZERO))).isEqualTo(AppealAvailability.AVAILABLE);
        assertThat(policy.evaluate(0, null, after(Duration.ZERO))).isEqualTo(AppealAvailability.AVAILABLE);
    }

    // ---------- recovery clears a cooldown permanently, at the crossing ----------

    /**
     * A CRITICAL cooldown ends when stock reaches 15, well before its 5 hours: the shortage is over.
     */
    @Test
    void aCriticalCooldownIsClearedOnceStockReachesFifteen() {
        AppealCooldown existing = cooldown(AppealTier.CRITICAL);
        when(cooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(existing));

        AppealCooldown surviving = policy.clearIfRecovered(BANK_ID, BloodType.O_NEGATIVE, 15);

        verify(cooldownRepository).delete(existing);
        assertThat(surviving).isNull();
    }

    /** One unit short of recovery: the cooldown stands, and is not deleted. */
    @Test
    void aCriticalCooldownIsNotClearedAtFourteenUnits() {
        AppealCooldown existing = cooldown(AppealTier.CRITICAL);
        when(cooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(existing));

        AppealCooldown surviving = policy.clearIfRecovered(BANK_ID, BloodType.O_NEGATIVE, 14);

        verify(cooldownRepository, never()).delete(any());
        assertThat(surviving).isSameAs(existing);
        // And it still blocks, well inside its window.
        assertThat(policy.evaluate(14, surviving, after(Duration.ofMinutes(30))))
            .isEqualTo(AppealAvailability.COOLING_DOWN);
    }

    /** A LOW cooldown's threshold is 30 — the same line rule 1 draws — so it is
     *  cleared by a recovery to 30, not merely masked by NO_SHORTAGE. */
    @Test
    void aLowCooldownIsClearedOnceStockReachesThirty() {
        AppealCooldown existing = cooldown(AppealTier.LOW);
        when(cooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(existing));

        AppealCooldown surviving = policy.clearIfRecovered(BANK_ID, BloodType.O_NEGATIVE, 30);

        verify(cooldownRepository).delete(existing);
        assertThat(surviving).isNull();
    }

    /** One unit short of recovery: still there, still blocking. */
    @Test
    void aLowCooldownIsNotClearedAtTwentyNineUnits() {
        AppealCooldown existing = cooldown(AppealTier.LOW);
        when(cooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(existing));

        AppealCooldown surviving = policy.clearIfRecovered(BANK_ID, BloodType.O_NEGATIVE, 29);

        verify(cooldownRepository, never()).delete(any());
        assertThat(policy.evaluate(29, surviving, after(Duration.ofHours(2))))
            .isEqualTo(AppealAvailability.COOLING_DOWN);
    }

    @Test
    void clearingAGroupThatHasNoCooldownIsHarmless() {
        when(cooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.empty());

        assertThat(policy.clearIfRecovered(BANK_ID, BloodType.O_NEGATIVE, 40)).isNull();

        verify(cooldownRepository, never()).delete(any());
    }

    /**
     * A group that recovers and then relapses inside the original window may appeal again immediately:
     * recovery cleared the cooldown, so no spent timer silences the new shortage.
     */
    @Test
    void aGroupThatRecoversThenRelapsesInsideTheWindowCanAppealAgainImmediately() {
        AppealCooldown existing = cooldown(AppealTier.CRITICAL);
        when(cooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(existing));

        // Sent at 10 units; an hour later a batch takes the group to 16, crossing
        // the 15 that triggered it. That clears the cooldown permanently.
        AppealCooldown afterRecovery = policy.clearIfRecovered(BANK_ID, BloodType.O_NEGATIVE, 16);
        verify(cooldownRepository).delete(existing);
        assertThat(afterRecovery).isNull();

        // Stock then falls back to 11 — still only two hours after the original
        // appeal, far inside its 5-hour window.
        assertThat(policy.evaluate(11, afterRecovery, after(Duration.ofHours(2))))
            .isEqualTo(AppealAvailability.AVAILABLE);

        // Even one minute after the original send, the relapse is appealable.
        assertThat(policy.evaluate(11, afterRecovery, after(Duration.ofMinutes(1))))
            .isEqualTo(AppealAvailability.AVAILABLE);
    }

    /**
     * evaluate() never ends a cooldown on stock alone: a surviving row blocks, and clearing is the crossing's job.
     */
    @Test
    void evaluateDoesNotEndACooldownItselfOnStockAlone() {
        // 20 units is above the CRITICAL tier's own 15-unit threshold, yet the row
        // is still present — which only happens if no recovery was ever recorded.
        assertThat(policy.evaluate(20, cooldown(AppealTier.CRITICAL), after(Duration.ofMinutes(30))))
            .isEqualTo(AppealAvailability.COOLING_DOWN);
    }

    // ---------- rule 2: the timer, when stock never recovers ----------

    /**
     * Exactly five hours still blocks; a second later it lifts. Checked on both sides to catch a >= / > slip.
     */
    @Test
    void aCriticalCooldownExpiresAfterFiveHoursWhenStockNeverRecovers() {
        AppealCooldown row = cooldown(AppealTier.CRITICAL);

        assertThat(policy.evaluate(10, row, after(Duration.ofHours(4).plusMinutes(59))))
            .isEqualTo(AppealAvailability.COOLING_DOWN);
        assertThat(policy.evaluate(10, row, after(Duration.ofHours(5))))
            .isEqualTo(AppealAvailability.COOLING_DOWN);
        assertThat(policy.evaluate(10, row, after(Duration.ofHours(5).plusSeconds(1))))
            .isEqualTo(AppealAvailability.AVAILABLE);
    }

    @Test
    void aLowCooldownExpiresAfterTwentyFourHoursWhenStockNeverRecovers() {
        AppealCooldown row = cooldown(AppealTier.LOW);

        assertThat(policy.evaluate(20, row, after(Duration.ofHours(23).plusMinutes(59))))
            .isEqualTo(AppealAvailability.COOLING_DOWN);
        assertThat(policy.evaluate(20, row, after(Duration.ofHours(24))))
            .isEqualTo(AppealAvailability.COOLING_DOWN);
        assertThat(policy.evaluate(20, row, after(Duration.ofHours(24).plusSeconds(1))))
            .isEqualTo(AppealAvailability.AVAILABLE);
    }

    /**
     * The tiers' windows are genuinely different lengths, not one constant used
     * twice: at six hours a CRITICAL cooldown has lapsed and a LOW one has not.
     */
    @Test
    void theTwoTiersDoNotShareOneWindow() {
        Instant sixHoursLater = after(Duration.ofHours(6));

        assertThat(policy.evaluate(10, cooldown(AppealTier.CRITICAL), sixHoursLater))
            .isEqualTo(AppealAvailability.AVAILABLE);
        assertThat(policy.evaluate(20, cooldown(AppealTier.LOW), sixHoursLater))
            .isEqualTo(AppealAvailability.COOLING_DOWN);
    }

    // ---------- recording a send ----------

    @Test
    void recordingASendUnderFifteenUnitsStartsACriticalCooldown() {
        when(cooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.empty());

        policy.recordSent(bank(), BloodType.O_NEGATIVE, 14, SENT_AT);

        ArgumentCaptor<AppealCooldown> saved = ArgumentCaptor.forClass(AppealCooldown.class);
        verify(cooldownRepository).save(saved.capture());
        assertThat(saved.getValue().getTriggeredTier()).isEqualTo(AppealTier.CRITICAL);
        assertThat(saved.getValue().getLastSentAt()).isEqualTo(SENT_AT);
        assertThat(saved.getValue().getBloodType()).isEqualTo(BloodType.O_NEGATIVE);
        assertThat(saved.getValue().getBloodBank().getId()).isEqualTo(BANK_ID);
    }

    @Test
    void recordingASendBetweenFifteenAndTwentyNineUnitsStartsALowCooldown() {
        when(cooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.empty());

        policy.recordSent(bank(), BloodType.O_NEGATIVE, 15, SENT_AT);

        ArgumentCaptor<AppealCooldown> saved = ArgumentCaptor.forClass(AppealCooldown.class);
        verify(cooldownRepository).save(saved.capture());
        assertThat(saved.getValue().getTriggeredTier()).isEqualTo(AppealTier.LOW);
    }

    /**
     * A second appeal overwrites the one row per (bank, group) rather than adding another.
     */
    @Test
    void recordingASendReusesTheExistingRowAndRestartsItsClock() {
        AppealCooldown existing = cooldown(AppealTier.LOW);
        when(cooldownRepository.findByBloodBankIdAndBloodType(BANK_ID, BloodType.O_NEGATIVE))
            .thenReturn(Optional.of(existing));

        Instant later = after(Duration.ofHours(30));
        policy.recordSent(bank(), BloodType.O_NEGATIVE, 9, later);

        ArgumentCaptor<AppealCooldown> saved = ArgumentCaptor.forClass(AppealCooldown.class);
        verify(cooldownRepository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
        // Re-tiered and re-clocked from this send, not left on the old terms.
        assertThat(saved.getValue().getTriggeredTier()).isEqualTo(AppealTier.CRITICAL);
        assertThat(saved.getValue().getLastSentAt()).isEqualTo(later);
    }

    /**
     * Unreachable through the endpoint, but guarded rather than storing a row with no tier.
     */
    @Test
    void recordingASendWithNoShortageIsRefusedRatherThanStoringATierlessRow() {
        assertThatThrownBy(() -> policy.recordSent(bank(), BloodType.O_NEGATIVE, 30, SENT_AT))
            .isInstanceOf(IllegalStateException.class);

        verify(cooldownRepository, never()).save(any());
    }
}
