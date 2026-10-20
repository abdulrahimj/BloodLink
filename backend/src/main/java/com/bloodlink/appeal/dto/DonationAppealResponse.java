package com.bloodlink.appeal.dto;

import java.util.UUID;

/**
 * The receipt for a sent appeal; donorsNotified tells "reached 23 donors" apart from "reached nobody".
 * It counts donors written to, not donors online; the notification waits either way.
 */
public record DonationAppealResponse(
    UUID bloodBankId,
    String bloodBankName,
    String bloodType,
    int donorsNotified,

    /**
     * Donors of this group left out because they are inside Business Rule 2's 90-day recovery window,
     * reported so a low reach is explicable and the safety filter visible.
     */
    int donorsSkippedIneligible
) {}
