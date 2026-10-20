/* BloodLink — Donors page logic (Business Rule 21): query building, reset-to-page-0, badge text and empty states
   as plain functions tested by npm test. Paging arithmetic is in lib/pagination.js. */

/** The filter/search/sort/paging state the Donors page keeps in one object,
 *  so a single "change one thing" function can reason about all of it. */
export function initialDonorRosterState() {
  return { page: 0, size: 20, q: "", bloodType: "", eligibility: "", sort: "lastDonation" };
}

/**
 * Applies a change to search, a filter, sort or page size and always returns to page 0,
 * since the old page no longer describes the results.
 */
export function applyDonorRosterFilterChange(state, changes) {
  return { ...state, ...changes, page: 0 };
}

/** Moves to a specific page without touching any filter — what Previous,
 *  Next and the clamp-on-load effect all actually do. */
export function goToDonorRosterPage(state, page) {
  return { ...state, page };
}

/**
 * The query parameters for GET /api/donors/roster; empty filters are left out rather than sent blank.
 */
export function buildDonorRosterParams(state) {
  const params = { page: state.page, size: state.size, sort: state.sort };
  const q = state.q?.trim();
  if (q) params.q = q;
  if (state.bloodType) params.bloodType = state.bloodType;
  if (state.eligibility) params.eligibility = state.eligibility;
  return params;
}

/**
 * Whether any search or filter narrows the roster, which decides the empty-state message.
 */
export function hasActiveDonorRosterFilters(state) {
  return Boolean(state.q?.trim()) || Boolean(state.bloodType) || Boolean(state.eligibility);
}

/**
 * "No donors have donated at this blood bank yet" versus "No donors match your filters",
 * so a filter that matches nobody never reads as an empty register.
 */
export function describeDonorRosterEmptyState(totalElements, state) {
  if (totalElements > 0) return null;
  return hasActiveDonorRosterFilters(state)
    ? "No donors match your filters."
    : "No donors have donated at this blood bank yet.";
}

/**
 * The eligibility badge: green "Eligible" or amber "Eligible from <date>"; the date arrives already formatted.
 */
export function describeDonorEligibility(eligibility, eligibleFromLabel) {
  if (eligibility === "ELIGIBLE") {
    return { text: "Eligible", tone: "green" };
  }
  return { text: `Eligible from ${eligibleFromLabel}`, tone: "amber" };
}
