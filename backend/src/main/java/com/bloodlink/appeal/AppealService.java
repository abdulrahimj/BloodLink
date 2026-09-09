package com.bloodlink.appeal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.appeal.dto.AppealReachResponse;
import com.bloodlink.appeal.dto.DonationAppealPayload;
import com.bloodlink.appeal.dto.DonationAppealResponse;
import com.bloodlink.bloodbank.ActingBankResolver;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.notification.NotificationService;
import com.bloodlink.security.AuthenticatedUser;

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
 * {@link ActingBankResolver}'s answer, and who hears about it is
 * {@link NotificationService}'s — including the Business Rule 2 gate that keeps an
 * appeal away from donors still inside their recovery window. What is left here is
 * the step between them: validating the blood group and reporting how far the
 * appeal reached.
 */
@Service
public class AppealService {

    private final ActingBankResolver actingBankResolver;
    private final NotificationService notificationService;

    AppealService(ActingBankResolver actingBankResolver, NotificationService notificationService) {
        this.actingBankResolver = actingBankResolver;
        this.notificationService = notificationService;
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
     * told it went out and have no way of knowing which donors were missed.
     */
    @Transactional
    public DonationAppealResponse sendAppeal(DonationAppealPayload payload, AuthenticatedUser principal) {
        BloodBank bank = actingBankResolver.resolve(payload.bloodBankId(), principal, "send a donation appeal");
        // Propagates IllegalArgumentException for an unrecognised label -> 400.
        BloodType bloodType = BloodType.fromLabel(payload.bloodType().trim());

        // Reach carries two figures: who was written to, and who was held back by
        // Business Rule 2's recovery window. Both are passed on rather than only the
        // first, so a small reach can be read correctly by whoever sent the appeal.
        NotificationService.AppealReach reach =
            notificationService.sendDonationAppeal(bank, bloodType, blankToNull(payload.note()));

        return new DonationAppealResponse(
            bank.getId(), bank.getName(), bloodType.getLabel(),
            reach.eligible(), reach.inRecovery()
        );
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
