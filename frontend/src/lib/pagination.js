/* ================================================================
   BloodLink — shared paging arithmetic

   The same small set of pure functions every paged list on this site needs:
   when to disable Previous/Next, how to pull a stale page number back into
   range, and how to label "Page X of Y". Originally written for the Audit
   Log (frontend/src/lib/auditLogPaging.js, which now just re-exports these
   under its own names so nothing that already depends on it has to change),
   and reused as-is for the blood bank's Donors page.
   ================================================================ */

/** The page-size choices offered wherever this project lets someone pick one. */
export const DEFAULT_PAGE_SIZES = [20, 50, 100];

/** Whether the Previous control should be enabled. `page` is 0-based, so the
 *  first page is 0 — there is nothing before it. */
export function canGoToPreviousPage(page) {
  return page > 0;
}

/** Whether the Next control should be enabled. `totalPages` of 0 (an empty
 *  list) disables it the same way the last real page does. */
export function canGoToNextPage(page, totalPages) {
  return page + 1 < totalPages;
}

/**
 * Keeps a remembered page number valid once the server has reported how many
 * pages actually exist — typically after changing the page size, or a filter
 * narrowing the result set, leaves the current page pointing past the new
 * last page. Clamps down to the new last page rather than resetting to the
 * first one, so the view stays as close as possible to where the person was.
 */
export function clampPageToAvailable(page, totalPages) {
  if (totalPages <= 0) return 0;
  return Math.min(Math.max(page, 0), totalPages - 1);
}

/** True when the list — or the requested page of it — has nothing in it. */
export function isPageEmpty(totalElements) {
  return totalElements === 0;
}

/** "Page 3 of 7". `totalPages` of 0 (an empty list) still reads as "Page 1 of
 *  1" rather than "Page 1 of 0", which would describe a page that cannot exist. */
export function describePage(page, totalPages) {
  return `Page ${page + 1} of ${Math.max(totalPages, 1)}`;
}

/**
 * "Showing 14-26 of 58" — which rows of the whole result this page holds.
 *
 * Worked out from the page number and size the server reported, not from how many
 * rows happen to be on screen, except for the last page, which is clamped to the
 * total so it never claims rows that do not exist ("Showing 53-58", not "53-65").
 * An empty result, or a page past the end, reads "Showing 0 of N".
 */
export function describeShowingRange(page, size, totalElements) {
  const first = page * size + 1;
  if (totalElements === 0 || first > totalElements) return `Showing 0 of ${totalElements}`;
  const last = Math.min((page + 1) * size, totalElements);
  return `Showing ${first}-${last} of ${totalElements}`;
}
