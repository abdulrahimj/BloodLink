/* BloodLink — donor calls: the donor's own-profile endpoints, scoped server-side to the JWT's account,
   so there is no donor id to pass. */

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
 * GET /api/donors/roster: one page of this bank's donor roster (Business Rule 21), params from buildDonorRosterParams;
 * bloodBankId only matters for an ADMIN. Resolves to the usual page envelope.
 */
export function getDonorRoster(params, bloodBankId = null) {
  const query = new URLSearchParams(
    Object.fromEntries(Object.entries(params).map(([key, value]) => [key, String(value)]))
  );
  if (bloodBankId) query.set('bloodBankId', bloodBankId);
  return apiRequest(`/api/donors/roster?${query.toString()}`);
}

/**
 * GET /api/donors/roster/{donorId}/donations: that donor's history at this bank, newest first, 50 max;
 * 404 for no donation here or an unknown id alike.
 */
export function getDonorHistoryAtBank(donorId, bloodBankId = null) {
  const query = bloodBankId ? `?bloodBankId=${encodeURIComponent(bloodBankId)}` : '';
  return apiRequest(`/api/donors/roster/${donorId}/donations${query}`);
}

/**
 * PATCH /api/donors/{donorId}/blood-group: set or correct a donor's blood group after a test, with a required note
 * (Business Rule 28); active blood bank staff only. Resolves to { donorId, fullName, previousBloodType, bloodType }.
 */
export function correctDonorBloodGroup(donorId, { bloodType, note }) {
  return apiRequest(`/api/donors/${donorId}/blood-group`, {
    method: 'PATCH',
    body: { bloodType, note },
  });
}
