package com.bloodlink.appeal.dto;

/**
 * What an appeal for one blood group would reach, asked before sending it.
 *
 * Backs the confirmation step on the Blood Inventory screen. A staff member about
 * to appeal for O- should be able to see whether that reaches twelve donors or
 * nobody at all *before* they commit — an appeal cannot be recalled, and one sent
 * into an empty audience looks identical to one that worked.
 *
 * {@code donorsInRecovery} makes the safety filter visible rather than silent. "3
 * donors will be notified, 9 more are still within their 90-day recovery window"
 * explains a small number honestly; "3 donors will be notified" on its own reads
 * like the register is nearly empty, which would send staff off recruiting when
 * the real answer is to wait.
 */
public record AppealReachResponse(
    String bloodType,
    int eligibleDonors,
    int donorsInRecovery
) {}
