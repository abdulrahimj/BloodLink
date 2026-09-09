/* ================================================================
   BloodLink — donation appeal calls

   A blood bank asking donors of one group to come in. Business Rule 1:
   appeals flow Blood Bank -> Donor and never Hospital -> Donor, which is why
   nothing here carries a sender — the backend stamps the signed-in staff
   member's own bank onto the appeal and would ignore one sent from here.

   Both calls apply the same Business Rule 2 filter server-side: a donor
   inside their 90-day recovery window is neither counted by the preview nor
   notified by the send. The two figures come from one calculation on the
   backend, so what the confirmation promises is what actually goes out.
   ================================================================ */

import { apiRequest } from './client.js';

/**
 * GET /api/appeals/reach?bloodType=O- — how far an appeal would reach, sending
 * nothing. Returns { bloodType, eligibleDonors, donorsInRecovery }.
 *
 * The blood group is URL-encoded because a literal "+" in a query string decodes
 * as a space: un-encoded, "A+" would arrive as "A " and be rejected as an unknown
 * group. encodeURIComponent gives "A%2B", which decodes back correctly.
 */
export function getAppealReach(bloodType) {
  return apiRequest(`/api/appeals/reach?bloodType=${encodeURIComponent(bloodType)}`);
}

/**
 * POST /api/appeals — sends the appeal.
 *
 * `bloodBankId` is required only for an ADMIN, who belongs to no single bank;
 * bank staff act as their own bank and the backend ignores it for them.
 * Returns a receipt: { bloodBankId, bloodBankName, bloodType, donorsNotified,
 * donorsSkippedIneligible }.
 */
export function sendDonationAppeal({ bloodType, note, bloodBankId }) {
  return apiRequest('/api/appeals', {
    method: 'POST',
    body: { bloodType, note: note ?? null, bloodBankId: bloodBankId ?? null },
  });
}
