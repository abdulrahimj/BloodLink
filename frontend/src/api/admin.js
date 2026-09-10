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
