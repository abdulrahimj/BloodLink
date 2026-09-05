/* ================================================================
   BloodLink — inventory calls

   Per-bank, per-blood-group stock. Reading works for any signed-in role;
   addBatch is rejected server-side (403) for anyone but BLOOD_BANK_STAFF on
   their own bank, or ADMIN on any bank — see InventoryService.
   ================================================================ */

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
 * PATCH /api/inventory/{bloodBankId}/{bloodType} — "Add New Batch".
 *
 * bloodType ("A+", "O-", ...) is URL-encoded because a literal "+" in a path
 * segment risks being read back as an encoded space; the backend expects
 * exactly this encoding (see InventoryController).
 */
export function addInventoryBatch(bloodBankId, bloodType, { unitsToAdd, expiryDate }) {
  const encodedType = encodeURIComponent(bloodType);
  return apiRequest(`/api/inventory/${bloodBankId}/${encodedType}`, {
    method: 'PATCH',
    body: { unitsToAdd, expiryDate },
  });
}
