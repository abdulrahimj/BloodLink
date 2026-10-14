/* ================================================================
   BloodLink — Admin Console User Accounts: search, filters, sort, paging

   Plain functions, kept out of AdminConsole.jsx so they can be tested without a
   browser (`npm test` runs userAccounts.test.js). The same shape as donorRoster.js:
   one state object, one "change something" function that returns to page 0, and
   builders for the request and the empty-state wording. The generic paging
   arithmetic (Previous/Next, clamping, "Showing 1-13 of N") is lib/pagination.js.
   ================================================================ */

/** Page sizes offered. Only ones that fit a 768px-tall laptop screen with no
 *  scrolling inside the table; the server allows up to 100, but bigger sizes
 *  would bring the scrollbar back, so they are not offered here. */
export const USER_ACCOUNT_PAGE_SIZES = [10, 13];

/** The sort choices, in the order the dropdown shows them. Values are exactly the
 *  server's whitelist (anything else is refused with a 400). */
export const USER_ACCOUNT_SORTS = [
  { value: "name", label: "Name" },
  { value: "role", label: "Role" },
  { value: "createdAt", label: "Joined" },
  { value: "status", label: "Status" },
];

/** Status filter values — the account status enum's own names. "Suspended" is what
 *  the rest of the console calls a disabled account. */
export const USER_ACCOUNT_STATUSES = [
  { value: "ACTIVE", label: "Active" },
  { value: "SUSPENDED", label: "Suspended" },
];

/** Page 0, 13 rows, name A-Z, no search and no filters. "" means "All". */
export function initialUserAccountsState() {
  return { page: 0, size: 13, q: "", role: "", status: "", sort: "name", direction: "asc" };
}

/**
 * Applies a change to the search, a filter, the sort, the direction or the page size,
 * and returns to page 0. Everything not named in `changes` is kept — changing the role
 * filter keeps the search text and the sort, and so on.
 */
export function applyUserAccountsChange(state, changes) {
  return { ...state, ...changes, page: 0 };
}

/** Moves to another page and changes nothing else — Previous, Next, and the clamp. */
export function goToUserAccountsPage(state, page) {
  return { ...state, page };
}

/** Flips A-Z / Z-A (newest / oldest for Joined), back on page 0. */
export function toggleUserAccountsDirection(state) {
  return applyUserAccountsChange(state, { direction: state.direction === "asc" ? "desc" : "asc" });
}

/**
 * The query parameters for GET /api/admin/users. Paging and sort are always sent;
 * search and filters only when set, so "All" and an empty search box are simply absent.
 */
export function buildUserAccountsParams(state) {
  const params = { page: state.page, size: state.size, sort: state.sort, direction: state.direction };
  const q = state.q?.trim();
  if (q) params.q = q;
  if (state.role) params.role = state.role;
  if (state.status) params.status = state.status;
  return params;
}

/** Whether search text or a filter is narrowing the list — what decides whether
 *  "Clear filters" shows. Sort and page size narrow nothing, so they do not count. */
export function hasActiveUserAccountFilters(state) {
  return Boolean(state.q?.trim()) || Boolean(state.role) || Boolean(state.status);
}

/** "Clear filters": drops the search and both filters, keeps the sort and page size. */
export function clearUserAccountFilters(state) {
  return applyUserAccountsChange(state, { q: "", role: "", status: "" });
}

/**
 * The empty-table message, or null when there are rows. "No users match your search
 * or filters" whenever something is narrowing the list, so an administrator is never
 * told there are no accounts at all when there are just none matching.
 */
export function describeUserAccountsEmptyState(totalElements, state) {
  if (totalElements > 0) return null;
  return hasActiveUserAccountFilters(state)
    ? "No users match your search or filters."
    : "No accounts on record.";
}

/**
 * The state to fetch after a new account is created. Normally unchanged — the table
 * reloads the page it is on. If a filter would hide the new account (Donor chosen and
 * an Administrator just created, or Suspended chosen when every new account starts
 * Active), that filter switches to show it, as the console did before paging, so the
 * administrator does not mistake its absence for a failure.
 */
export function stateAfterAccountCreated(state, account) {
  const changes = {};
  if (state.role && state.role !== account.role) changes.role = account.role;
  if (state.status && state.status !== account.status) changes.status = account.status;
  return Object.keys(changes).length === 0 ? state : applyUserAccountsChange(state, changes);
}

/** The count shown on a role button, from GET /api/admin/reports/users. "" is All. */
export function roleCount(counts, role) {
  if (!counts) return 0;
  switch (role) {
    case "": return counts.total;
    case "DONOR": return counts.donors;
    case "HOSPITAL_STAFF": return counts.hospitalStaff;
    case "BLOOD_BANK_STAFF": return counts.bloodBankStaff;
    case "ADMIN": return counts.admins;
    default: return 0;
  }
}
