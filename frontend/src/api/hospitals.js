/* BloodLink — hospital calls: reference data readable by any signed-in role. */

import { apiRequest } from './client.js';

/** GET /api/hospitals — the seeded hospitals, for pickers and read-only display. */
export function getHospitals() {
  return apiRequest('/api/hospitals');
}
