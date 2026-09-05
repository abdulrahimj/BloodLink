package com.bloodlink.bloodbank.dto;

import java.util.List;
import java.util.UUID;

/** Backs the stat cards and requests panel on the Blood Bank Dashboard (home view). */
public record DashboardResponse(
    UUID bloodBankId,
    String bloodBankName,
    int totalUnits,
    /** Donations recorded at this bank so far this calendar month. */
    int donationsThisMonth,
    /** Citywide PENDING count — see BloodRequestRepository: no bank owns a request
     *  until it is fulfilled (Business Rule 6). */
    int pendingRequestsCount,
    /** Donors who have given at least once at this bank (see DonationRepository). */
    int registeredDonorsCount,
    /** Most recent requests across every status, newest first, capped for the panel. */
    List<PendingRequestItem> recentRequests
) {}
