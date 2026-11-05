package com.bloodlink.request.dto;

/**
 * The Hospital dashboard's four stat cards, counted in the database over every request of the hospital, open or
 * finished, since the dashboard list now holds open requests only (Business Rule 36).
 */
public record HospitalRequestSummaryResponse(
    /** Requests raised this calendar month (UTC). */
    long requestsThisMonth,
    /** Open requests: PENDING or PARTIALLY_FULFILLED. */
    long awaitingUnits,
    /** Units received across all requests, finished ones included. */
    long unitsReceived,
    /** Open EMERGENCY requests. */
    long openEmergencies
) {
}
