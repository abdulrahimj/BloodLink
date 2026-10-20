/* BloodLink — blood bank calls: the reference listing and the staff dashboard summary;
   the bloodBankId always comes from the caller. */

import { apiRequest } from './client.js';

/** GET /api/blood-banks — the seeded banks, for pickers and read-only display. */
export function getBloodBanks() {
  return apiRequest('/api/blood-banks');
}

/** GET /api/blood-banks/{id}/dashboard — stat cards + requests panel data. */
export function getBankDashboard(bloodBankId) {
  return apiRequest(`/api/blood-banks/${bloodBankId}/dashboard`);
}
