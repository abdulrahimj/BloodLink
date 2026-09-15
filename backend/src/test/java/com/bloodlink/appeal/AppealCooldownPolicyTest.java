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
 * Business Rule 12, at the boundaries where it is easy to get wrong.
 *
 * The rule has two independent ways out of a cooldown — stock recovering past the
 * threshold that triggered it, and the timer simply running out — and they behave
 * differently per tier. Every test here pins one of those edges to the exact unit
 * or the exact hour, because an off-by-one either silences a bank that genuinely
 * needs donors or lets it ask the same people twice in an afternoon.
 *
 * The clock is passed in rather than read inside the policy, so the 5- and
 * 24-hour boundaries are checked exactly instead of approximately, and without a
 * test that takes five hours to run.
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
     * The case that makes the appeal thresholds visibly different from the Business
     * Rule 3 badge thresholds. A group holding 30-39 units is still labelled "Low"
     * in the table — Rule 3's Low band runs to 40 — but it cannot be appealed for,
     * because an appeal needs a real shortage and 30 is where that line sits. No
     * cooldown is involved: this is refused on stock alone.
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
        // Rule 1 is checked first, so a group that has recovered past 30 reports why
        // it is unavailable honestly, rather than reporting a cooldown that is
        // itself no longer in force.
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
     * A CRITICAL cooldown ends the moment stock reaches 15, long before its 5 hours
     * are up. The shortage it was protecting against is over; the group is merely
     * Low now, and a Low-tier appeal is a legitimate thing to send.
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
     * The behaviour this design exists for: a group recovers past its threshold and
     * then relapses into shortage inside what would have been the original
     * cooldown's window.
     *
     * The relapse is a new shortage and gets its own chance to appeal immediately —
     * it is not throttled by the tail of a timer belonging to an appeal that has
     * already done its job. Recovery cleared that cooldown for good, so by the time
     * stock falls again there is simply nothing left to block on.
     *
     * The contrast is the whole point: had recovery been re-derived from current
     * stock on each check instead of cleared at the crossing, the row would still
     * be sitting there, and this second shortage would have been met with a
     * "COOLING_DOWN" from a spent timer — silencing a bank that genuinely needs
     * donors again.
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
     * evaluate() deliberately no longer consults current stock to decide whether a
     * cooldown has ended. A row that still exists is one whose shortage was never
     * resolved, so it blocks regardless of what the units happen to read now —
     * clearing is the crossing's job, not this method's.
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
     * Five hours exactly is still "no more than 5 hours elapsed", so the cooldown
     * holds; a second past it, the bank may ask again. Checked either side of the
     * boundary rather than at one point, because a >= / > slip only shows up here.
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
     * A second appeal overwrites the existing row rather than adding another: the
     * table holds one current cooldown per (bank, group), and a second row would
     * leave the button reading whichever the database happened to return first.
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
     * Unreachable through the endpoint, which refuses a no-shortage send long
     * before this point — but guarded rather than silently writing a row with no
     * tier, which every later read would then have to defend against.
     */
    @Test
    void recordingASendWithNoShortageIsRefusedRatherThanStoringATierlessRow() {
        assertThatThrownBy(() -> policy.recordSent(bank(), BloodType.O_NEGATIVE, 30, SENT_AT))
            .isInstanceOf(IllegalStateException.class);

        verify(cooldownRepository, never()).save(any());
    }
}
