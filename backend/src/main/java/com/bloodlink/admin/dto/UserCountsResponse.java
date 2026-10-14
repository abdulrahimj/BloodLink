package com.bloodlink.admin.dto;

/**
 * How many accounts exist, in total and per role — {@code GET /api/admin/reports/users}.
 *
 * Feeds the Admin Console's "Total Users" and "Registered Donors" cards and the counts
 * shown on the User Accounts role buttons. These used to be counted in the browser
 * over a download of every account; now the database counts them and nothing has to
 * load every account. Every role is always present, as zero when it has no accounts.
 */
public record UserCountsResponse(
    long total,
    long donors,
    long hospitalStaff,
    long bloodBankStaff,
    long admins
) {
}
