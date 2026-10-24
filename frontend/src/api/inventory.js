/* BloodLink — inventory calls: per-group stock (any signed-in role), batch detail (bank staff and ADMIN);
   adding or writing off stock is refused (403) to anyone but that bank's own staff. */

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

/** GET /api/inventory/{bloodBankId}/batches?bloodType= — one group's batches with units left, expired included. */
export function getBatches(bloodBankId, bloodType) {
  return apiRequest(`/api/inventory/${bloodBankId}/batches?bloodType=${encodeURIComponent(bloodType)}`);
}

/** GET /api/inventory/{bloodBankId}/batches/{batchId} — one batch, with its issues to hospitals and write-offs. */
export function getBatchHistory(bloodBankId, batchId) {
  return apiRequest(`/api/inventory/${bloodBankId}/batches/${batchId}`);
}

/** POST .../batches/{batchId}/write-offs — own-bank staff only; answers { writeOff, batch }. */
export function writeOffBatch(bloodBankId, batchId, { units, reason, note }) {
  return apiRequest(`/api/inventory/${bloodBankId}/batches/${batchId}/write-offs`, {
    method: 'POST',
    body: { units, reason, note },
  });
}
