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

    // ---------- rule 3: recovering past the trigger threshold ends it early ----------

    /**
     * A CRITICAL cooldown ends the moment stock reaches 15, long before its 5 hours
     * are up. The shortage it was protecting against is over; the group is merely
     * Low now, and a Low-tier appeal is a legitimate thing to send.
     */
    @Test
    void aCriticalCooldownReactivatesEarlyOnceStockReachesFifteen() {
        assertThat(policy.evaluate(15, cooldown(AppealTier.CRITICAL), after(Duration.ofMinutes(30))))
            .isEqualTo(AppealAvailability.AVAILABLE);
    }

    /** One unit short of recovery, and well inside the window: still locked. */
    @Test
    void aCriticalCooldownIsStillLockedAtFourteenUnits() {
        assertThat(policy.evaluate(14, cooldown(AppealTier.CRITICAL), after(Duration.ofMinutes(30))))
            .isEqualTo(AppealAvailability.COOLING_DOWN);
    }

    /**
     * A LOW cooldown's own threshold is 30 — the same line rule 1 draws — so its
     * early release is observable through the NO_SHORTAGE answer: either way the
     * cooldown is no longer what is stopping a send. What matters is that it is not
     * reported as COOLING_DOWN, because the cooldown really has ended and stock
     * dropping back under 30 later must find the button live again.
     */
    @Test
    void aLowCooldownReactivatesEarlyOnceStockReachesThirty() {
        AppealAvailability atRecovery =
            policy.evaluate(30, cooldown(AppealTier.LOW), after(Duration.ofHours(1)));

        assertThat(atRecovery).isNotEqualTo(AppealAvailability.COOLING_DOWN);
        assertThat(atRecovery).isEqualTo(AppealAvailability.NO_SHORTAGE);

        // And the point of it: once stock slips back under 30 inside the same
        // 24-hour window, the ended cooldown does not come back to block the send.
        assertThat(policy.evaluate(29, cooldown(AppealTier.LOW), after(Duration.ofHours(1))))
            .isEqualTo(AppealAvailability.COOLING_DOWN);
    }

    /** One unit short of recovery, well inside the 24 hours: still locked. */
    @Test
    void aLowCooldownIsStillLockedAtTwentyNineUnits() {
        assertThat(policy.evaluate(29, cooldown(AppealTier.LOW), after(Duration.ofHours(2))))
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
