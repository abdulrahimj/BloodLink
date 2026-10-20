package com.bloodlink.bloodbank.dto;

import java.util.UUID;

/**
 * Backs the four stat cards on the Blood Bank Dashboard.
 * The requests panel reads GET /api/requests instead, so the actions use the same representation.
 */
public record DashboardResponse(
    UUID bloodBankId,
    String bloodBankName,
    int totalUnits,
    /** Donations recorded at this bank so far this calendar month. */
    int donationsThisMonth,
    /** Requests this bank could contribute to now (still short, not declined by it; Business Rule 20),
     * scoped like the panel so the card and list agree. */
    int openRequestsCount,
    /** Donors who have given at least once at this bank (see DonationRepository). */
    int registeredDonorsCount
) {}
