/* BloodLink — Audit Log paging: a thin re-export of lib/pagination.js under this module's original names,
   so existing imports didn't have to change. */

export {
  DEFAULT_PAGE_SIZES as AUDIT_LOG_PAGE_SIZES,
  canGoToPreviousPage,
  canGoToNextPage,
  clampPageToAvailable,
  isPageEmpty as isAuditLogEmpty,
  describePage as describeAuditLogPage,
} from './pagination.js';
