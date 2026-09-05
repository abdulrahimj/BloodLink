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
