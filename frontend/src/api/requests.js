/* ================================================================
   BloodLink — blood request calls

   The hospital-request-to-blood-bank sequence (Figure 3.5). One set of
   calls for both sides of it: a hospital submits and tracks, a blood bank
   lists and acts. What comes back is scoped server-side by the caller's
   role and facility, so nothing here has to ask "whose requests?" — see
   RequestService on the backend.
   ================================================================ */

import { apiRequest } from './client.js';

/**
 * POST /api/requests — "Submit Blood Request".
 *
 * Carries no hospital id on purpose: the backend stamps the signed-in staff
 * member's own hospital onto the new request and would ignore one sent here.
 * Returns the created request (201) so a tracking list can show it at once.
 */
export function submitBloodRequest({ bloodType, unitsRequested, urgencyLevel, patientReference }) {
  return apiRequest('/api/requests', {
    method: 'POST',
    body: { bloodType, unitsRequested, urgencyLevel, patientReference },
  });
}

/**
 * GET /api/requests — the caller's request feed, newest first.
 *
 * `bloodBankId` is optional and only has an effect for an ADMIN, or for bank
 * staff an administrator has not yet assigned to a bank: it picks whose feed to
 * look at. Assigned staff are always scoped to their own bank whatever is passed.
 */
export function getRequests(bloodBankId) {
  const query = bloodBankId ? `?bloodBankId=${encodeURIComponent(bloodBankId)}` : '';
  return apiRequest(`/api/requests${query}`);
}

/**
 * PATCH /api/requests/{id} — one step along the request lifecycle.
 *
 * `action` is APPROVE, REJECT or FULFIL — the transition, not the target status,
 * because fulfilling also deducts stock and must never be reachable by simply
 * declaring a request finished.
 *
 * `bloodBankId` is required only when the caller is an ADMIN acting on a bank's
 * behalf; bank staff act as their own bank and the backend ignores it for them.
 *
 * Fails with a 409 (ApiError.status) when the transition is illegal or the bank
 * no longer holds enough stock — both carry a message written to be shown as-is.
 */
export function actOnRequest(requestId, action, bloodBankId) {
  return apiRequest(`/api/requests/${requestId}`, {
    method: 'PATCH',
    body: { action, bloodBankId: bloodBankId ?? null },
  });
}
