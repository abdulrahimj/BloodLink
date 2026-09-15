package com.bloodlink.appeal;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.appeal.dto.AppealReachResponse;
import com.bloodlink.appeal.dto.DonationAppealPayload;
import com.bloodlink.appeal.dto.DonationAppealResponse;
import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventory;
import com.bloodlink.domain.enums.AppealAvailability;
import com.bloodlink.domain.enums.AppealTier;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.repository.BloodInventoryRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.ConflictException;

/**
 * Donation appeals: a blood bank telling donors of one group that it needs them.
 *
 * This is the blood bank's counterpart to the hospital's blood request. A hospital
 * short of blood asks the banks (RequestService); a bank short of blood asks the
 * donors, here. Business Rule 1 is that those are the only two directions — an
 * appeal never originates from a hospital, and a donor's screen must never show one
 * that appears to.
 *
 * The class is thin on purpose. Which bank is acting is
 * {@link ActingBankResolver}'s answer, who hears about it is
 * {@link NotificationService}'s — including the Business Rule 2 gate that keeps an
 * appeal away from donors still inside their recovery window — and whether the bank
 * may ask at all right now is {@link AppealCooldownPolicy}'s (Business Rule 12).
 * What is left here is the steps between them: validating the blood group,
 * refusing a send the cooldown forbids, and reporting how far the appeal reached.
 *
 * Those last two gates answer different questions and neither replaces the other.
 * The cooldown decides whether this bank may send an appeal for this group at this
 * moment; Business Rule 2 decides which donors it is safe to put that appeal in
 * front of. Adding the first changed nothing about the second.
 */
@Service
public class AppealService {

    private final ActingBankResolver actingBankResolver;
    private final NotificationService notificationService;
    private final AppealCooldownPolicy cooldownPolicy;
    private final BloodInventoryRepository bloodInventoryRepository;

    AppealService(
        ActingBankResolver actingBankResolver,
        NotificationService notificationService,
        AppealCooldownPolicy cooldownPolicy,
        BloodInventoryRepository bloodInventoryRepository
    ) {
        this.actingBankResolver = actingBankResolver;
        this.notificationService = notificationService;
        this.cooldownPolicy = cooldownPolicy;
        this.bloodInventoryRepository = bloodInventoryRepository;
    }

    /**
     * How far an appeal for one blood group would reach, sending nothing.
     *
     * Deliberately takes no blood bank. Donors are not attached to a bank in this
     * schema, so the eligible audience for a group is the same figure whichever bank
     * asks; requiring one would be friction that changes no answer. Who may ask at
     * all is still settled — SecurityConfig limits /api/appeals/** to bank staff and
     * administrators, and this returns counts, never a donor's identity.
     */
    @Transactional(readOnly = true)
    public AppealReachResponse previewReach(String bloodTypeLabel) {
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        BloodType bloodType = BloodType.fromLabel(bloodTypeLabel.trim());
        NotificationService.AppealReach reach = notificationService.previewAppealReach(bloodType);

        return new AppealReachResponse(bloodType.getLabel(), reach.eligible(), reach.inRecovery());
    }

    /**
     * Sends one appeal and reports how many donors it was written to.
     *
     * The whole thing is one transaction so the appeal is all-or-nothing: either
     * every matching donor has a notification waiting or none does. A half-sent
     * appeal would be worse than a failed one, because the staff member would be
     * told it went out and have no way of knowing which donors were missed. The
     * cooldown row is written inside that same transaction, so a failed send never
     * leaves a cooldown blocking the retry it did not earn.
     *
     * Business Rule 12's cooldown is enforced here as well as on the button,
     * because a disabled button is a courtesy and not a control — "role-gated UI
     * must also be role-gated server-side" (CLAUDE.md) applies just as much to a
     * rate limit as to a role. Without this check the whole feature would be one
     * curl away from doing nothing at all.
     */
    @Transactional
    public DonationAppealResponse sendAppeal(DonationAppealPayload payload, AuthenticatedUser principal) {
        BloodBank bank = actingBankResolver.resolve(payload.bloodBankId(), principal, "send a donation appeal");
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        BloodType bloodType = BloodType.fromLabel(payload.bloodType().trim());

        // One instant for the whole operation: the same value decides whether the
        // existing cooldown has expired and stamps the new one, so a send can never
        // be judged against one clock reading and recorded against a later one.
        Instant now = Instant.now();

        // A group this bank has never stocked has no inventory row at all, which is
        // 0 units — the worst possible shortage, not an absent one.
        int unitsAvailable = bloodInventoryRepository
            .findByBloodBankIdAndBloodType(bank.getId(), bloodType)
            .map(BloodInventory::getUnitsAvailable)
            .orElse(0);

        AppealAvailability availability =
            cooldownPolicy.evaluate(bank.getId(), bloodType, unitsAvailable, now);
        if (availability != AppealAvailability.AVAILABLE) {
            throw refusedAppeal(availability, bloodType, unitsAvailable);
        }

        // Reach carries two figures: who was written to, and who was held back by
        // Business Rule 2's recovery window. Both are passed on rather than only the
        // first, so a small reach can be read correctly by whoever sent the appeal.
        // That gate is untouched by the cooldown above: one decides whether the bank
        // may ask at all, the other decides which donors it is safe to ask.
        NotificationService.AppealReach reach =
            notificationService.sendDonationAppeal(bank, bloodType, blankToNull(payload.note()));

        // Recorded only after the send has succeeded, and with units as they were at
        // send time — that figure fixes this cooldown's length and the level stock
        // must reach to clear it.
        cooldownPolicy.recordSent(bank, bloodType, unitsAvailable, now);

        return new DonationAppealResponse(
            bank.getId(), bank.getName(), bloodType.getLabel(),
            reach.eligible(), reach.inRecovery()
        );
    }

    /** 409 with the actual figures behind the refusal, so the operator can see why
     *  their click was turned down rather than only that it was — the same shape
     *  RequestService uses for its own conflicts. */
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
            // Not a refusal at all; only ever reached if a new state is added above
            // without being handled here, which the build catches rather than
            // letting a refused send fall through as a success.
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
