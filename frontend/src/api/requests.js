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
 * PATCH /api/requests/{id} — a blood bank contributing to a request, or declining
 * it (Business Rule 20).
 *
 * `action` is CONTRIBUTE or REJECT. `units` applies to CONTRIBUTE only, and the
 * server caps it at whatever is still outstanding: between the panel being drawn
 * and the button being pressed, another bank may have covered part of the request,
 * and the response says what was actually recorded. Asking for more units than this
 * bank holds is refused (409) rather than capped.
 *
 * `bloodBankId` is required only when the caller is an ADMIN acting on a bank's
 * behalf; bank staff act as their own bank and the backend ignores it for them.
 *
 * Fails with a 409 (ApiError.status) when the request is already closed, when this
 * bank has already declined it, or when the stock has moved — each carries a
 * message written to be shown as-is.
 */
export function actOnRequest(requestId, action, bloodBankId, units = null) {
  return apiRequest(`/api/requests/${requestId}`, {
    method: 'PATCH',
    body: { action, bloodBankId: bloodBankId ?? null, units },
  });
}
