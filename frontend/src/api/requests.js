/* BloodLink — blood request calls (Figure 3.5): hospitals submit and track, banks list and act;
   results are scoped server-side by role and facility. */

import { apiRequest } from './client.js';

/**
 * POST /api/requests: "Submit Blood Request", with no hospital id (the backend uses the caller's own);
 * returns the created request (201).
 */
export function submitBloodRequest({ bloodType, unitsRequested, urgencyLevel, patientReference }) {
  return apiRequest('/api/requests', {
    method: 'POST',
    body: { bloodType, unitsRequested, urgencyLevel, patientReference },
  });
}

/**
 * GET /api/requests: the caller's feed, newest first; bloodBankId only matters for an ADMIN or unassigned staff.
 */
export function getRequests(bloodBankId) {
  const query = bloodBankId ? `?bloodBankId=${encodeURIComponent(bloodBankId)}` : '';
  return apiRequest(`/api/requests${query}`);
}

/**
 * PATCH /api/requests/{id}: CONTRIBUTE (units capped at what is outstanding; more than held is a 409) or REJECT.
 * bloodBankId only for an ADMIN's REJECT; 409s carry messages ready to show.
 */
export function actOnRequest(requestId, action, bloodBankId, units = null) {
  return apiRequest(`/api/requests/${requestId}`, {
    method: 'PATCH',
    body: { action, bloodBankId: bloodBankId ?? null, units },
  });
}
