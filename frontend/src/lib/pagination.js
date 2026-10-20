/* BloodLink — shared paging arithmetic: disabling Previous/Next, clamping a stale page and "Page X of Y",
   used by the Audit Log and Donors pages. */

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
 * Pulls a remembered page back within range once the server reports the page count,
 * clamping to the last page rather than resetting to the first.
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
 * "Showing 14-26 of 58" from the reported page and size, the last page clamped to the total;
 * empty or past the end reads "Showing 0 of N".
 */
export function describeShowingRange(page, size, totalElements) {
  const first = page * size + 1;
  if (totalElements === 0 || first > totalElements) return `Showing 0 of ${totalElements}`;
  const last = Math.min((page + 1) * size, totalElements);
  return `Showing ${first}-${last} of ${totalElements}`;
}
