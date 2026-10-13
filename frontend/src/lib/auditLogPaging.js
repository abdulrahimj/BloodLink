/* ================================================================
   BloodLink — Audit Log paging arithmetic

   Kept as plain functions, separate from AdminConsole.jsx, so the paging logic
   can be tested without a browser: `npm test` runs auditLogPaging.test.js with
   Node's built-in test runner. The page component (AuditLogView) only calls
   these — it never recomputes "is this the last page?" itself.
   ================================================================ */

/** The page-size choices offered on the Audit Log page. */
export const AUDIT_LOG_PAGE_SIZES = [20, 50, 100];

/**
 * Whether the Previous button should be enabled. `page` is 0-based, so the
 * first page is 0 — there is nothing before it.
 */
export function canGoToPreviousPage(page) {
  return page > 0;
}

/**
 * Whether the Next button should be enabled. `totalPages` of 0 (an empty log)
 * disables it the same way the last real page does — there is nothing to
 * advance to either way.
 */
export function canGoToNextPage(page, totalPages) {
  return page + 1 < totalPages;
}

/**
 * Keeps a remembered page number valid once the server has reported how many
 * pages actually exist — the case this guards is changing the page size (or an
 * entry elsewhere making the log shrink, not that it ever does) leaving the
 * current page number pointing past the new last page. Clamps down to the new
 * last page rather than silently resetting to the first one, so the view stays
 * as close as possible to where the person was.
 */
export function clampPageToAvailable(page, totalPages) {
  if (totalPages <= 0) return 0;
  return Math.min(Math.max(page, 0), totalPages - 1);
}

/** True when the log — or the requested page of it — has nothing in it. */
export function isAuditLogEmpty(totalElements) {
  return totalElements === 0;
}

/** "Page 3 of 7". `totalPages` of 0 (an empty log) still reads as "Page 1 of 1"
 *  rather than "Page 1 of 0", which would describe a page that cannot exist. */
export function describeAuditLogPage(page, totalPages) {
  return `Page ${page + 1} of ${Math.max(totalPages, 1)}`;
}
