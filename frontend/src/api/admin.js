/* ================================================================
   BloodLink — admin calls

   Account management, adding facilities, and citywide reporting, all restricted server-side to
   ADMIN (SecurityConfig gates the whole /api/admin/** prefix on the role, so
   there is nothing to scope here the way a bank- or hospital-facing call
   would need to — an administrator's view is never "my own facility").
   ================================================================ */

import { apiRequest } from './client.js';

/**
 * GET /api/admin/users — one page of accounts, searched, filtered and sorted by the
 * server. `params` is what lib/userAccounts.js's buildUserAccountsParams produced:
 * page, size, sort, direction, and q / role / status only when set. Resolves to
 * { content, page, size, totalElements, totalPages }; the totals count only the
 * accounts that match. A bad value is a 400 naming the field.
 */
export function getAdminUsers(params) {
  const query = new URLSearchParams(
    Object.fromEntries(Object.entries(params).map(([key, value]) => [key, String(value)]))
  );
  return apiRequest(`/api/admin/users?${query.toString()}`);
}

/**
 * GET /api/admin/reports/users — { total, donors, hospitalStaff, bloodBankStaff,
 * admins }, counted by the database. Feeds the Dashboard's "Total Users" and
 * "Registered Donors" cards and the counts on the User Accounts role buttons.
 */
export function getUserCounts() {
  return apiRequest('/api/admin/reports/users');
}

/**
 * POST /api/admin/users — creates a staff or administrator account.
 *
 * `payload` is { fullName, phone, email, role, bloodBankId?, hospitalId? }, where
 * role is "HOSPITAL_STAFF", "BLOOD_BANK_STAFF" or "ADMIN" — never "DONOR", which
 * the server refuses outright (donors register themselves).
 *
 * No password is sent: the server generates one and returns it, in plaintext,
 * exactly once — { account, temporaryPassword }. That value is never retrievable
 * again and exists nowhere else, so the caller must show it to the administrator
 * before it is lost, and must not stash it anywhere afterwards.
 */
export function createStaffAccount(payload) {
  return apiRequest('/api/admin/users', { method: 'POST', body: payload });
}

/**
 * PATCH /api/admin/users/{id} — activates or suspends one account.
 *
 * `status` is "ACTIVE" or "SUSPENDED". A suspension takes effect immediately —
 * the backend rejects that account's existing token on its very next request,
 * not only at its next login — and self-suspension is refused server-side with
 * a 409, so there is always at least one path back in.
 */
export function updateUserStatus(userId, status) {
  return apiRequest(`/api/admin/users/${userId}`, {
    method: 'PATCH',
    body: { status },
  });
}

/**
 * PATCH /api/admin/users/{id}/details — changes another account's phone and
 * facility (Business Rule 17).
 *
 * Always the whole desired state: `phone`, plus `bloodBankId` for blood bank
 * staff or `hospitalId` for hospital staff (null otherwise). Role cannot be
 * changed here, and an administrator cannot use this on their own account —
 * both are refused server-side. Resolves to the updated account, in the same
 * shape the User Accounts table renders. Every real change is recorded in the
 * audit log.
 */
export function editAccountDetails(userId, { phone, bloodBankId = null, hospitalId = null }) {
  return apiRequest(`/api/admin/users/${userId}/details`, {
    method: 'PATCH',
    body: { phone, bloodBankId, hospitalId },
  });
}

/**
 * POST /api/admin/blood-banks — adds a blood bank (Business Rule 19).
 *
 * `payload` is { name, location, contactPhone }, all required. Resolves to the new
 * row in the same shape GET /api/blood-banks returns, so it can be added straight
 * to that list. A name another blood bank already has (ignoring case and spacing)
 * is a 409. The contact phone is stored as typed — it is a facility's display
 * number, not a login, so Business Rule 13's format does not apply.
 */
export function createBloodBank(payload) {
  return apiRequest('/api/admin/blood-banks', { method: 'POST', body: payload });
}

/** POST /api/admin/hospitals — adds a hospital; the same rules as createBloodBank. */
export function createHospital(payload) {
  return apiRequest('/api/admin/hospitals', { method: 'POST', body: payload });
}

/**
 * GET /api/admin/audit-log — one page of recorded administrator actions, newest
 * first. Read-only: the log has no endpoint that changes or removes an entry.
 *
 * `page` is 0-based (default 0); `size` defaults to 20 and the server refuses
 * anything above 100. Resolves to { content, page, size, totalElements, totalPages }.
 */
export function getAuditLog(page = 0, size = 20) {
  return apiRequest(`/api/admin/audit-log?page=${page}&size=${size}`);
}

/** GET /api/admin/reports/donations — six months of citywide donation counts,
 *  oldest first, for the trend chart. Every month appears even at zero. */
export function getDonationTrends() {
  return apiRequest('/api/admin/reports/donations');
}

/** GET /api/admin/reports/requests — citywide request counts by outcome, plus
 *  their total, for the donut chart. */
export function getRequestOutcomes() {
  return apiRequest('/api/admin/reports/requests');
}
