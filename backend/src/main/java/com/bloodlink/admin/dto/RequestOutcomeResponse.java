package com.bloodlink.admin.dto;

/**
 * Citywide request counts by outcome for the Request Outcomes donut, one field per RequestStatus (Business Rule 20)
 * so the frontend reads them directly; total is included so the percentages add up.
 */
public record RequestOutcomeResponse(
    long pending,
    /** Some units received, some still outstanding (Business Rule 20) — the slice
     *  the now-retired APPROVED used to hold. */
    long partiallyFulfilled,
    long fulfilled,
    long rejected,
    long total
) {}
