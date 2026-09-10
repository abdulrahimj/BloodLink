/* ================================================================
   BloodLink — donation calls

   "Record Donation": the blood bank's write path into the donations table.
   Both calls are restricted server-side to BLOOD_BANK_STAFF and ADMIN.
   ================================================================ */

import { apiRequest } from './client.js';

/**
 * GET /api/donors/lookup?phone=... — the donor registered against one number.
 *
 * Exact match on the phone number as it was registered, and the only way to
 * find a donor from the bank's side: there is deliberately no browse or
 * partial-name search, so this cannot become a way to page through the donor
 * register. Throws ApiError with status 404 when nobody matches, carrying a
 * message that says what to do about it.
 */
export function lookupDonorByPhone(phone) {
  return apiRequest(`/api/donors/lookup?phone=${encodeURIComponent(phone)}`);
}

/**
 * POST /api/donations — records a donation.
 *
 * One call, three effects, all or nothing: a Donation row, the donor's
 * last-donation date moved to today, and the units added to this bank's stock
 * of the tested group (Business Rule 7's accumulation).
 *
 * `bloodType` is the group as tested today and must be entered by the staff
 * member — the backend never falls back to the donor's stored profile group
 * (Business Rule 4), and neither does the form.
 *
 * `bloodBankId` is required only for an ADMIN, who belongs to no single bank;
 * bank staff act as their own bank and the backend ignores it for them.
 * Returns a receipt including the resulting stock figure for that group.
 */
export function recordDonation({ donorId, bloodType, unitsDonated, bloodBankId }) {
  return apiRequest('/api/donations', {
    method: 'POST',
    body: { donorId, bloodType, unitsDonated, bloodBankId: bloodBankId ?? null },
  });
}
