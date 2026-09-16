/* ================================================================
   BloodLink — admin calls

   Account management and citywide reporting, all restricted server-side to
   ADMIN (SecurityConfig gates the whole /api/admin/** prefix on the role, so
   there is nothing to scope here the way a bank- or hospital-facing call
   would need to — an administrator's view is never "my own facility").
   ================================================================ */

import { apiRequest } from './client.js';

/** GET /api/admin/users — every account across all four roles. */
export function getAdminUsers() {
  return apiRequest('/api/admin/users');
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
