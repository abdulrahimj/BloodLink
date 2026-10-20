/* BloodLink — donation appeal calls: nothing carries a sender, since the backend stamps the caller's bank
   (Business Rule 1). Preview and send share one server-side eligible-donor calculation (Business Rule 2). */

import { apiRequest } from './client.js';

/**
 * GET /api/appeals/reach: { bloodType, eligibleDonors, donorsInRecovery }, sending nothing;
 * the group is URL-encoded so "+" is not read as a space.
 */
export function getAppealReach(bloodType) {
  return apiRequest(`/api/appeals/reach?bloodType=${encodeURIComponent(bloodType)}`);
}

/**
 * POST /api/appeals: bloodBankId only matters for an ADMIN; resolves to a receipt
 * { bloodBankId, bloodBankName, bloodType, donorsNotified, donorsSkippedIneligible }.
 */
export function sendDonationAppeal({ bloodType, note, bloodBankId }) {
  return apiRequest('/api/appeals', {
    method: 'POST',
    body: { bloodType, note: note ?? null, bloodBankId: bloodBankId ?? null },
  });
}

/** POST /api/notifications/{notificationId}/accept — accepts a donor's appeal. */
export function acceptDonationAppeal(notificationId) {
  return apiRequest(`/api/notifications/${encodeURIComponent(notificationId)}/accept`, {
    method: 'POST',
  });
}
