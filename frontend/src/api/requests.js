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
 * POST /api/requests/{id}/cancel: the raising hospital withdraws its own open request (Business Rule 35);
 * returns the request as it now stands. A 409 carries a message ready to show.
 */
export function cancelBloodRequest(requestId, { reason, note }) {
  return apiRequest(`/api/requests/${requestId}/cancel`, {
    method: 'POST',
    body: { reason, note },
  });
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

/**
 * GET /api/requests/summary: the Hospital dashboard's four stat cards, counted by the server over open and
 * finished requests (Business Rule 36); hospital staff only.
 */
export function getHospitalRequestSummary() {
  return apiRequest('/api/requests/summary');
}

/**
 * GET /api/requests/history: one page of finished requests, newest closed first (Business Rule 36).
 * params come from lib/requestHistory.js buildHistoryParams; a bad one is a 400 naming the field.
 */
export function getRequestHistory(params) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => query.set(key, String(value)));
  return apiRequest(`/api/requests/history?${query.toString()}`);
}
