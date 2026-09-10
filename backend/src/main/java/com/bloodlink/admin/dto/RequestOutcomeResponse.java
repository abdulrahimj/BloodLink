package com.bloodlink.admin.dto;

/**
 * Citywide blood request counts by outcome — the Admin Console's "Request
 * Outcomes" donut.
 *
 * A fixed shape with one field per RequestStatus, rather than a list of
 * (status, count) pairs: there are exactly four statuses (Business Rule 5) and
 * that will not change without a schema migration, so the frontend can read
 * `.pending`, `.approved` and so on directly instead of searching a list for
 * one it assumes is present — the same choice DashboardResponse already makes
 * for the Blood Bank Dashboard's stat cards.
 *
 * {@code total} is included rather than left for the frontend to sum, so a
 * donut's percentages and its center label are computed from figures the
 * backend already agrees add up, matching how InventorySummaryResponse hands
 * back totalUnits alongside the per-group breakdown.
 */
public record RequestOutcomeResponse(
    long pending,
    long approved,
    long fulfilled,
    long rejected,
    long total
) {}
