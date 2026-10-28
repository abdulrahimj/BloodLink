/* BloodLink — donation calls: "Record Donation", the bank's write path into the donations table,
   restricted server-side to blood bank staff. */

import { apiRequest } from './client.js';

/**
 * GET /api/donors/lookup?phone=...: exact match only, so the donor register cannot be browsed;
 * a 404 ApiError when nobody matches.
 */
export function lookupDonorByPhone(phone) {
  return apiRequest(`/api/donors/lookup?phone=${encodeURIComponent(phone)}`);
}

/**
 * POST /api/donations: one all-or-nothing call recording the donation, the donor's date and a new stock batch;
 * bloodType is today's tested group (Business Rule 4). A donor in recovery is a 409 (Business Rule 2).
 * confirmBloodGroupChange: true only after staff confirm replacing a different profile group (Business Rule 28).
 */
export function recordDonation({ donorId, bloodType, unitsDonated, bloodBankId, confirmBloodGroupChange = false }) {
  return apiRequest('/api/donations', {
    method: 'POST',
    body: { donorId, bloodType, unitsDonated, bloodBankId: bloodBankId ?? null, confirmBloodGroupChange },
  });
}
