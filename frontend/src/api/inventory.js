/* BloodLink — inventory calls: per-bank, per-group stock, readable by any signed-in role;
   adding stock is refused (403) to anyone but that bank's own staff. */

import { apiRequest } from './client.js';

/** GET /api/inventory/{bloodBankId} — all eight blood groups, always. */
export function getInventory(bloodBankId) {
  return apiRequest(`/api/inventory/${bloodBankId}`);
}

/** GET /api/inventory/{bloodBankId}/summary — totals, expiring-soon, composition. */
export function getInventorySummary(bloodBankId) {
  return apiRequest(`/api/inventory/${bloodBankId}/summary`);
}

/**
 * PATCH /api/inventory/{bloodBankId}/{bloodType}: "Add New Batch";
 * bloodType is URL-encoded so "+" survives in the path.
 */
export function addInventoryBatch(bloodBankId, bloodType, { unitsToAdd, expiryDate }) {
  const encodedType = encodeURIComponent(bloodType);
  return apiRequest(`/api/inventory/${bloodBankId}/${encodedType}`, {
    method: 'PATCH',
    body: { unitsToAdd, expiryDate },
  });
}
