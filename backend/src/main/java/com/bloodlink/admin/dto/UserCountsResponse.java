package com.bloodlink.admin.dto;

/**
 * Account counts in total and per role (GET /api/admin/reports/users), counted by the database and zero-filled.
 * Feeds the console's Total Users and Registered Donors cards and the role buttons.
 */
public record UserCountsResponse(
    long total,
    long donors,
    long hospitalStaff,
    long bloodBankStaff,
    long admins
) {
}
