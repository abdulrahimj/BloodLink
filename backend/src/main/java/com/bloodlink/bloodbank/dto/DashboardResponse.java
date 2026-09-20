package com.bloodlink.bloodbank.dto;

import java.util.UUID;

/**
 * Backs the four stat cards on the Blood Bank Dashboard (home view).
 *
 * The "Incoming Hospital Requests" panel below them is fed by GET /api/requests
 * instead, not from here: that panel carries the contribute and decline actions, and
 * a request the operator can act on has to be the same object the action endpoint
 * returns — one representation of a request, not a read-only copy that drifts out
 * of step with the one the buttons post to.
 */
public record DashboardResponse(
    UUID bloodBankId,
    String bloodBankName,
    int totalUnits,
    /** Donations recorded at this bank so far this calendar month. */
    int donationsThisMonth,
    /** Requests this bank could contribute to right now — still short of units, and
     *  not declined by this bank (Business Rule 20). Scoped like the panel's own
     *  feed, so the card and the list underneath it never disagree. */
    int openRequestsCount,
    /** Donors who have given at least once at this bank (see DonationRepository). */
    int registeredDonorsCount
) {}
