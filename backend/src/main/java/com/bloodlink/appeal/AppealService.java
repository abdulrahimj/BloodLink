package com.bloodlink.appeal;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.appeal.dto.AppealReachResponse;
import com.bloodlink.appeal.dto.DonationAppealPayload;
import com.bloodlink.appeal.dto.DonationAppealResponse;
import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.enums.AppealAvailability;
import com.bloodlink.domain.enums.AppealTier;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.stock.BatchStockService;
import com.bloodlink.web.error.ConflictException;

/**
 * Donation appeals: a blood bank asking donors of one group to come in (Business Rule 1: never a hospital).
 * Thin on purpose: ActingBankResolver picks the bank, AppealCooldownPolicy allows it, NotificationService picks donors.
 */
@Service
public class AppealService {

    private final ActingBankResolver actingBankResolver;
    private final NotificationService notificationService;
    private final AppealCooldownPolicy cooldownPolicy;
    private final BatchStockService batchStockService;

    AppealService(
        ActingBankResolver actingBankResolver,
        NotificationService notificationService,
        AppealCooldownPolicy cooldownPolicy,
        BatchStockService batchStockService
    ) {
        this.actingBankResolver = actingBankResolver;
        this.notificationService = notificationService;
        this.cooldownPolicy = cooldownPolicy;
        this.batchStockService = batchStockService;
    }

    /**
     * How far an appeal for one group would reach, sending nothing; no bank is needed, as donors aren't tied to banks.
     * Returns counts only, never a donor's identity.
     */
    @Transactional(readOnly = true)
    public AppealReachResponse previewReach(String bloodTypeLabel) {
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        BloodType bloodType = BloodType.fromLabel(bloodTypeLabel.trim());
        NotificationService.AppealReach reach = notificationService.previewAppealReach(bloodType);

        return new AppealReachResponse(bloodType.getLabel(), reach.eligible(), reach.inRecovery());
    }

    /**
     * Sends one appeal in one transaction: every matching donor is notified or none is, with the cooldown row.
     * Business Rule 12's cooldown is enforced here too, because a disabled button is a courtesy, not a control.
     */
    @Transactional
    public DonationAppealResponse sendAppeal(DonationAppealPayload payload, AuthenticatedUser principal) {
        BloodBank bank = actingBankResolver.resolve(payload.bloodBankId(), principal, "send a donation appeal");
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        BloodType bloodType = BloodType.fromLabel(payload.bloodType().trim());

        // One instant for the whole operation, so the cooldown check and the new stamp share one clock reading.
        Instant now = Instant.now();

        // Usable units only (Business Rule 24): expired batches are not stock, so 35 units with 10 expired is short;
        // a group never stocked is 0 units.
        int unitsAvailable = batchStockService.usableUnits(bank.getId(), bloodType);

        AppealAvailability availability =
            cooldownPolicy.evaluate(bank.getId(), bloodType, unitsAvailable, now);
        if (availability != AppealAvailability.AVAILABLE) {
            throw refusedAppeal(availability, bloodType, unitsAvailable);
        }

        // Reach carries who was notified and who was held back by Business Rule 2's recovery window,
        // so a small reach can be read correctly; that gate is separate from the cooldown.
        NotificationService.AppealReach reach =
            notificationService.sendDonationAppeal(bank, bloodType, blankToNull(payload.note()));

        // Recorded only after the send succeeded, with units at send time, which fix the cooldown's terms.
        cooldownPolicy.recordSent(bank, bloodType, unitsAvailable, now);

        return new DonationAppealResponse(
            bank.getId(), bank.getName(), bloodType.getLabel(),
            reach.eligible(), reach.inRecovery()
        );
    }

    /** 409 with the figures behind the refusal, so the operator sees why it was refused,
     * the same shape RequestService uses. */
    private ConflictException refusedAppeal(
        AppealAvailability availability, BloodType bloodType, int unitsAvailable
    ) {
        return switch (availability) {
            case NO_SHORTAGE -> new ConflictException(
                "%s is not short enough to appeal for — %d units in stock, and an appeal needs fewer than %d."
                    .formatted(bloodType.getLabel(), unitsAvailable, AppealTier.SHORTAGE_BELOW)
            );
            case COOLING_DOWN -> new ConflictException(
                ("An appeal for %s was already sent from this blood bank recently. "
                    + "Please wait before sending another, or send again once stock has recovered.")
                    .formatted(bloodType.getLabel())
            );
            // Not a refusal; reached only if a new state is added above without being handled here.
            case AVAILABLE -> throw new IllegalStateException("An available appeal was refused.");
        };
    }

    /** An untouched optional text input arrives as "" rather than absent; treat the
     *  two the same so no appeal ends with a stray trailing space. */
    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
