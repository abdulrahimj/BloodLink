package com.bloodlink.appeal.dto;

/**
 * What an appeal for one blood group would reach, asked before sending, since an appeal cannot be recalled.
 * donorsInRecovery makes the safety filter visible, explaining a small reach honestly.
 */
public record AppealReachResponse(
    String bloodType,
    int eligibleDonors,
    int donorsInRecovery
) {}
