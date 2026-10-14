/* ================================================================
   BloodLink — Audit Log paging arithmetic

   Thin re-export of the generic paging functions in lib/pagination.js, kept
   under this module's original names so nothing that already imports from
   here — AdminConsole.jsx's AuditLogView, and this file's own test — had to
   change when those functions were generalised for reuse on the blood bank's
   Donors page (Business Rule 21). If this file disappeared entirely, nothing
   would be lost: every name below is exactly pagination.js's own export.
   ================================================================ */

export {
  DEFAULT_PAGE_SIZES as AUDIT_LOG_PAGE_SIZES,
  canGoToPreviousPage,
  canGoToNextPage,
  clampPageToAvailable,
  isPageEmpty as isAuditLogEmpty,
  describePage as describeAuditLogPage,
} from './pagination.js';
