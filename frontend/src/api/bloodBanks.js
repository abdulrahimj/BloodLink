/* ================================================================
   BloodLink — blood bank calls

   Wraps the reference-data listing and the staff-facing dashboard summary.
   Both bloodBankId values come from the caller (the bank picker, or the
   signed-in staff member's own bank) — nothing here resolves "mine" itself.
   ================================================================ */

import { apiRequest } from './client.js';

/** GET /api/blood-banks — the seeded banks, for pickers and read-only display. */
export function getBloodBanks() {
  return apiRequest('/api/blood-banks');
}

/** GET /api/blood-banks/{id}/dashboard — stat cards + requests panel data. */
export function getBankDashboard(bloodBankId) {
  return apiRequest(`/api/blood-banks/${bloodBankId}/dashboard`);
}
