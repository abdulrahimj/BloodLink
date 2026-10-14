/* ================================================================
   BloodLink — Donors page logic (Business Rule 21)

   Kept as plain functions, separate from BloodBankDonors.jsx, so the query-
   building, the "reset to page 0" rule, the eligibility badge text, and the
   empty-state choice can all be tested without a browser: `npm test` runs
   donorRoster.test.js with Node's built-in test runner. The page component
   only calls these — it never builds a query string or decides an empty-state
   message by hand.

   The generic paging arithmetic (Previous/Next, clamping a stale page,
   "Page X of Y") is not repeated here — see lib/pagination.js, the same
   functions the Audit Log page uses.
   ================================================================ */

/** The filter/search/sort/paging state the Donors page keeps in one object,
 *  so a single "change one thing" function can reason about all of it. */
export function initialDonorRosterState() {
  return { page: 0, size: 20, q: "", bloodType: "", eligibility: "", sort: "lastDonation" };
}

/**
 * Applies a change to the search box, a filter, the sort choice, or the page
 * size — every control except Previous/Next — and returns to page 0.
 *
 * Returning to page 0 is deliberate and unconditional here: whatever page the
 * person was on described a result set that no longer applies once any of
 * these change, so showing it would either be stale or, worse, silently empty
 * because the new filter has fewer pages than the old page number implied.
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
 * The query parameters to send to GET /api/donors/roster for the given state.
 *
 * Only the non-empty filters are included, so a blank search box or an
 * unset filter is simply absent from the request rather than sent as an
 * empty string — the backend treats "not given" and "given but blank" the
 * same way, but an absent parameter is the honest way to say "no filter".
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
 * Whether any filter or search narrows the roster right now — the one fact
 * that decides which of the two empty-state messages applies. Page and size
 * are not filters in this sense: a roster that is genuinely empty on page 0
 * is empty regardless of what size was asked for.
 */
export function hasActiveDonorRosterFilters(state) {
  return Boolean(state.q?.trim()) || Boolean(state.bloodType) || Boolean(state.eligibility);
}

/**
 * "No donors have donated at this blood bank yet" when the roster is
 * genuinely empty, versus "No donors match your filters" when it only looks
 * empty because of what is currently selected. Conflating the two would tell
 * a bank with a full register of donors that it has none at all, the moment
 * a search box happens to match nobody.
 */
export function describeDonorRosterEmptyState(totalElements, state) {
  if (totalElements > 0) return null;
  return hasActiveDonorRosterFilters(state)
    ? "No donors match your filters."
    : "No donors have donated at this blood bank yet.";
}

/**
 * The eligibility badge: green "Eligible", or amber "Eligible from <date>".
 *
 * Takes the date already formatted (the page calls formatFullDate on
 * `eligibleFrom` before this), so this function stays a plain string decision
 * with nothing to fetch or parse — the same reason describeAppealReach takes
 * donor counts rather than raw records.
 */
export function describeDonorEligibility(eligibility, eligibleFromLabel) {
  if (eligibility === "ELIGIBLE") {
    return { text: "Eligible", tone: "green" };
  }
  return { text: `Eligible from ${eligibleFromLabel}`, tone: "amber" };
}
