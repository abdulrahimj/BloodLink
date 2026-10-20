package com.bloodlink.appeal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.bloodlink.domain.AppealCooldown;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.enums.AppealAvailability;
import com.bloodlink.domain.enums.AppealTier;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.repository.AppealCooldownRepository;

/**
 * Decides whether a bank may appeal for a blood group now (Business Rule 12), for both the button and the endpoint.
 * 30+ units or a cooldown inside its window blocks; recovery deletes the cooldown at the crossing (clearIfRecovered).
 */
@Component
public class AppealCooldownPolicy {

    private final AppealCooldownRepository cooldownRepository;

    // Public, not package-private, so InventoryService (another package) can hold
    // one — and so a test can exercise the real rule rather than stubbing it out.
    public AppealCooldownPolicy(AppealCooldownRepository cooldownRepository) {
        this.cooldownRepository = cooldownRepository;
    }

    /**
     * The decision as a pure function; now is passed in so one operation shares one instant
     * and the timer boundaries are testable. cooldown is null if the bank never appealed for the group.
     */
    public AppealAvailability evaluate(int unitsAvailable, AppealCooldown cooldown, Instant now) {
        // 1. No shortage wins outright, before any cooldown is even considered —
        //    a group at 30+ units is not appealable whatever its history.
        if (!AppealTier.isShortage(unitsAvailable)) {
            return AppealAvailability.NO_SHORTAGE;
        }

        // Nothing to block it: either this group has never been appealed for, or a
        // recovery has since cleared the cooldown away (see clearIfRecovered).
        if (cooldown == null) {
            return AppealAvailability.AVAILABLE;
        }

        // 2. A surviving cooldown blocks while its timer runs; stock is not consulted, because a recovered
        // shortage's cooldown was already deleted at the crossing.
        if (!cooldown.getTriggeredTier().hasExpiredBy(cooldown.getLastSentAt(), now)) {
            return AppealAvailability.COOLING_DOWN;
        }

        // 3. Timer ran out with the shortage unresolved — appealing again is
        //    exactly what should happen now.
        return AppealAvailability.AVAILABLE;
    }

    /**
     * Deletes the cooldown once usable stock climbs back over its trigger threshold (15 CRITICAL, 30 LOW), so a
     * later relapse gets a fresh appeal. Returns the cooldown still in force, or null.
     */
    public AppealCooldown clearIfRecovered(UUID bloodBankId, BloodType bloodType, int unitsAvailable) {
        AppealCooldown cooldown = cooldownRepository
            .findByBloodBankIdAndBloodType(bloodBankId, bloodType)
            .orElse(null);

        if (cooldown == null) {
            return null;
        }

        if (cooldown.getTriggeredTier().hasRecoveredAt(unitsAvailable)) {
            cooldownRepository.delete(cooldown);
            return null;
        }

        // Still short of the level that would end it: the cooldown stands.
        return cooldown;
    }

    /** The same decision, reading the bank's cooldown row for itself. */
    public AppealAvailability evaluate(UUID bloodBankId, BloodType bloodType, int unitsAvailable, Instant now) {
        AppealCooldown cooldown = cooldownRepository
            .findByBloodBankIdAndBloodType(bloodBankId, bloodType)
            .orElse(null);
        return evaluate(unitsAvailable, cooldown, now);
    }

    /**
     * Records an appeal just sent, starting or restarting the cooldown by updating the one row per (bank, group).
     * The tier is fixed from units at send time, since it decides the cooldown's length and its clearing level.
     */
    public void recordSent(BloodBank bank, BloodType bloodType, int unitsAtSend, Instant sentAt) {
        AppealTier tier = AppealTier.forUnits(unitsAtSend);
        if (tier == null) {
            // Unreachable through the endpoint, which refuses a no-shortage send first;
            // guarded anyway rather than storing a null tier.
            throw new IllegalStateException(
                "An appeal was recorded at " + unitsAtSend + " units, which is not a shortage."
            );
        }

        AppealCooldown cooldown = cooldownRepository
            .findByBloodBankIdAndBloodType(bank.getId(), bloodType)
            .orElseGet(() -> {
                AppealCooldown fresh = new AppealCooldown();
                fresh.setBloodBank(bank);
                fresh.setBloodType(bloodType);
                return fresh;
            });

        cooldown.setLastSentAt(sentAt);
        cooldown.setTriggeredTier(tier);
        cooldownRepository.save(cooldown);
    }
}
