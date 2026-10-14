/* ================================================================
   BloodLink — donor calls

   Wraps the donor's own-profile endpoints. Both are scoped server-side to
   whichever account owns the JWT (see AuthenticatedUser on the backend),
   so there is no donor id to pass here — only "me".
   ================================================================ */

import { apiRequest } from './client.js';

/** GET /api/donors/me — dashboard summary: profile, eligibility, totals. */
export function getMyDonorProfile() {
  return apiRequest('/api/donors/me');
}

/** GET /api/donors/me/donations — full donation history, most recent first. */
export function getMyDonationHistory() {
  return apiRequest('/api/donors/me/donations');
}

/**
 * GET /api/donors/roster — one page of the signed-in blood bank staff
 * member's own donor roster: donors with at least one donation at that bank
 * (Business Rule 21). Restricted server-side to BLOOD_BANK_STAFF and ADMIN.
 *
 * `params` is whatever frontend/src/lib/donorRoster.js's
 * buildDonorRosterParams produced — page, size, sort, and only the filters
 * actually in use. `bloodBankId` is additional and meaningful only for an
 * ADMIN, who belongs to no bank and must name one; bank staff act as their
 * own bank and the server ignores it for them.
 *
 * Resolves to { content, page, size, totalElements, totalPages }, the same
 * page envelope shape the Audit Log uses.
 */
export function getDonorRoster(params, bloodBankId = null) {
  const query = new URLSearchParams(
    Object.fromEntries(Object.entries(params).map(([key, value]) => [key, String(value)]))
  );
  if (bloodBankId) query.set('bloodBankId', bloodBankId);
  return apiRequest(`/api/donors/roster?${query.toString()}`);
}

/**
 * GET /api/donors/roster/{donorId}/donations — that donor's donation history
 * at this bank only, newest first, most recent 50. 404 (ApiError.status) if
 * this donor has no donation at this bank — including when the donor id does
 * not exist at all; the two cases answer identically on purpose.
 */
export function getDonorHistoryAtBank(donorId, bloodBankId = null) {
  const query = bloodBankId ? `?bloodBankId=${encodeURIComponent(bloodBankId)}` : '';
  return apiRequest(`/api/donors/roster/${donorId}/donations${query}`);
}
