package com.bloodlink.appeal.dto;

import java.util.UUID;

/**
 * The receipt for a sent appeal.
 *
 * {@code donorsNotified} is the point of it: an appeal is fire-and-forget from the
 * sender's side, so without a count the staff member who sent it has no way to tell
 * "reached the 23 O- donors on record" from "reached nobody, because no donor has
 * been recorded with that group yet". Those two need very different next actions.
 *
 * It counts donors written to, not donors who were online — the notification is
 * durable and waiting either way (see NotificationService).
 */
public record DonationAppealResponse(
    UUID bloodBankId,
    String bloodBankName,
    String bloodType,
    int donorsNotified,

    /**
     * Donors of this group deliberately left out because they are still inside
     * Business Rule 2's 90-day recovery window.
     *
     * Reported so a low reach is explicable rather than mysterious: an appeal that
     * reached 3 of 20 donors is a bank whose register is fine and whose donors gave
     * recently, which is a different situation from one with only 3 donors on that
     * group. It also makes the safety filter visible to the person who sent the
     * appeal, instead of silently shrinking their audience.
     */
    int donorsSkippedIneligible
) {}
