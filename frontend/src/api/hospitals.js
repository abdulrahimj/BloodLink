/* ================================================================
   BloodLink — hospital calls

   Fixed reference data seeded by V6, exactly like the blood banks:
   two rows, no write path, readable by any signed-in role.
   ================================================================ */

import { apiRequest } from './client.js';

/** GET /api/hospitals — the seeded hospitals, for pickers and read-only display. */
export function getHospitals() {
  return apiRequest('/api/hospitals');
}
