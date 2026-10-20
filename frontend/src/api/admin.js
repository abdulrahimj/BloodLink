/* BloodLink — admin calls: account management, adding facilities and citywide reports,
   all restricted server-side to ADMIN. */

import { apiDownload, apiRequest } from './client.js';

/**
 * GET /api/admin/users: one page of accounts with params from buildUserAccountsParams;
 * resolves to { content, page, size, totalElements, totalPages }.
 */
export function getAdminUsers(params) {
  const query = new URLSearchParams(
    Object.fromEntries(Object.entries(params).map(([key, value]) => [key, String(value)]))
  );
  return apiRequest(`/api/admin/users?${query.toString()}`);
}

/**
 * GET /api/admin/reports/users: account counts per role, for the Dashboard cards and role buttons.
 */
export function getUserCounts() {
  return apiRequest('/api/admin/reports/users');
}

/**
 * POST /api/admin/users: creates a staff/admin account from { fullName, phone, email, role, facility id }, never DONOR.
 * Resolves to { account, temporaryPassword }, shown once and never stored.
 */
export function createStaffAccount(payload) {
  return apiRequest('/api/admin/users', { method: 'POST', body: payload });
}

/**
 * PATCH /api/admin/users/{id}: "ACTIVE" or "SUSPENDED"; a suspension takes effect immediately,
 * and self-suspension is a 409.
 */
export function updateUserStatus(userId, status) {
  return apiRequest(`/api/admin/users/${userId}`, {
    method: 'PATCH',
    body: { status },
  });
}

/**
 * PATCH /api/admin/users/{id}/details: the full desired phone and facility (Business Rule 17); not role, not your own.
 * Resolves to the updated account; real changes are audit-logged.
 */
export function editAccountDetails(userId, { phone, bloodBankId = null, hospitalId = null }) {
  return apiRequest(`/api/admin/users/${userId}/details`, {
    method: 'PATCH',
    body: { phone, bloodBankId, hospitalId },
  });
}

/**
 * POST /api/admin/blood-banks (Business Rule 19): { name, location, contactPhone }, resolving to the new row;
 * a duplicate name is a 409, and the phone is stored as typed.
 */
export function createBloodBank(payload) {
  return apiRequest('/api/admin/blood-banks', { method: 'POST', body: payload });
}

/** POST /api/admin/hospitals — adds a hospital; the same rules as createBloodBank. */
export function createHospital(payload) {
  return apiRequest('/api/admin/hospitals', { method: 'POST', body: payload });
}

/**
 * GET /api/admin/audit-log: one read-only page, newest first (page 0-based, size default 20, max 100);
 * resolves to { content, page, size, totalElements, totalPages }.
 */
export function getAuditLog(page = 0, size = 20) {
  return apiRequest(`/api/admin/audit-log?page=${page}&size=${size}`);
}

/** GET /api/admin/reports/donations — six months of citywide donation counts,
 *  oldest first, for the trend chart. Every month appears even at zero. */
export function getDonationTrends() {
  return apiRequest('/api/admin/reports/donations');
}

/** GET /api/admin/reports/requests — citywide request counts by outcome, plus
 *  their total, for the donut chart. */
export function getRequestOutcomes() {
  return apiRequest('/api/admin/reports/requests');
}

/* ---------- the Reports page (Business Rule 23): four sections, each with a CSV twin at /export ----------
   range is { from, to } (yyyy-MM-dd) for Requests and Donations only; bad ranges are a 400. */

/** Builds "?from=…&to=…" for the two date-ranged sections. */
function rangeQuery({ from, to }) {
  return `?${new URLSearchParams({ from, to }).toString()}`;
}

/** GET /api/admin/reports/summary/stock — units per group and per bank, today. */
export function getStockReport() {
  return apiRequest('/api/admin/reports/summary/stock');
}

/** GET /api/admin/reports/summary/requests — requests raised within the range. */
export function getRequestReport(range) {
  return apiRequest(`/api/admin/reports/summary/requests${rangeQuery(range)}`);
}

/** GET /api/admin/reports/summary/donations — donations recorded within the range. */
export function getDonationReport(range) {
  return apiRequest(`/api/admin/reports/summary/donations${rangeQuery(range)}`);
}

/** GET /api/admin/reports/summary/donors — donor totals and eligibility, today. */
export function getDonorReport() {
  return apiRequest('/api/admin/reports/summary/donors');
}

/**
 * GET .../summary/{section}/export: the section's CSV from the server; resolves to { blob, fileName }.
 */
export function downloadReportCsv(section, range) {
  const query = section === 'requests' || section === 'donations' ? rangeQuery(range) : '';
  return apiDownload(`/api/admin/reports/summary/${section}/export${query}`);
}
