import { test } from "node:test";
import assert from "node:assert/strict";

import {
  USER_ACCOUNT_PAGE_SIZES,
  initialUserAccountsState,
  applyUserAccountsChange,
  goToUserAccountsPage,
  toggleUserAccountsDirection,
  buildUserAccountsParams,
  hasActiveUserAccountFilters,
  clearUserAccountFilters,
  describeUserAccountsEmptyState,
  stateAfterAccountCreated,
  roleCount,
} from "./userAccounts.js";

const onPage3 = (overrides = {}) => ({ ...initialUserAccountsState(), page: 3, ...overrides });

test("starts on page 0 with 13 rows, name A-Z, no search or filters", () => {
  assert.deepEqual(initialUserAccountsState(),
    { page: 0, size: 13, q: "", role: "", status: "", sort: "name", direction: "asc" });
});

test("only page sizes that fit without scrolling are offered", () => {
  assert.deepEqual(USER_ACCOUNT_PAGE_SIZES, [10, 13]);
});

test("the default request sends paging and sort only", () => {
  assert.deepEqual(buildUserAccountsParams(initialUserAccountsState()),
    { page: 0, size: 13, sort: "name", direction: "asc" });
});

test("search, role and status travel together with sort as one request", () => {
  const state = { page: 1, size: 10, q: "  Kamara ", role: "DONOR", status: "ACTIVE", sort: "name", direction: "desc" };
  assert.deepEqual(buildUserAccountsParams(state),
    { page: 1, size: 10, sort: "name", direction: "desc", q: "Kamara", role: "DONOR", status: "ACTIVE" });
});

test("a blank search box is left out of the request", () => {
  assert.equal("q" in buildUserAccountsParams(onPage3({ q: "   " })), false);
});

test("each filter alone returns to page 0 and keeps the search and sort", () => {
  const start = onPage3({ q: "Kamara", sort: "createdAt", direction: "desc" });
  for (const change of [{ role: "DONOR" }, { status: "SUSPENDED" }]) {
    const next = applyUserAccountsChange(start, change);
    assert.equal(next.page, 0);
    assert.equal(next.q, "Kamara");
    assert.equal(next.sort, "createdAt");
    assert.equal(next.direction, "desc");
  }
});

test("changing the search, the sort or the page size also returns to page 0", () => {
  assert.equal(applyUserAccountsChange(onPage3(), { q: "Bah" }).page, 0);
  assert.equal(applyUserAccountsChange(onPage3(), { sort: "role" }).page, 0);
  assert.equal(applyUserAccountsChange(onPage3(), { size: 10 }).page, 0);
});

test("flipping the direction returns to page 0 and keeps the search", () => {
  const next = toggleUserAccountsDirection(onPage3({ q: "Bah", direction: "asc" }));
  assert.equal(next.direction, "desc");
  assert.equal(next.page, 0);
  assert.equal(next.q, "Bah");
  assert.equal(toggleUserAccountsDirection(next).direction, "asc");
});

test("Previous/Next move the page and touch nothing else", () => {
  const next = goToUserAccountsPage(onPage3({ role: "ADMIN", q: "x" }), 4);
  assert.equal(next.page, 4);
  assert.equal(next.role, "ADMIN");
  assert.equal(next.q, "x");
});

test("Clear filters shows only when a search or filter is active", () => {
  assert.equal(hasActiveUserAccountFilters(initialUserAccountsState()), false);
  assert.equal(hasActiveUserAccountFilters(onPage3({ q: "  " })), false);
  assert.equal(hasActiveUserAccountFilters(onPage3({ q: "a" })), true);
  assert.equal(hasActiveUserAccountFilters(onPage3({ role: "DONOR" })), true);
  assert.equal(hasActiveUserAccountFilters(onPage3({ status: "ACTIVE" })), true);
  // Sort and page size are not filters.
  assert.equal(hasActiveUserAccountFilters(onPage3({ sort: "role", size: 10 })), false);
});

test("Clear filters drops search and filters but keeps sort, direction and size", () => {
  const cleared = clearUserAccountFilters(onPage3({ q: "Kamara", role: "DONOR", status: "ACTIVE",
    sort: "createdAt", direction: "desc", size: 10 }));
  assert.deepEqual(cleared,
    { page: 0, size: 10, q: "", role: "", status: "", sort: "createdAt", direction: "desc" });
});

test("the empty state names search and filters when they are active", () => {
  assert.equal(describeUserAccountsEmptyState(0, onPage3({ role: "ADMIN" })),
    "No users match your search or filters.");
  assert.equal(describeUserAccountsEmptyState(0, initialUserAccountsState()), "No accounts on record.");
  assert.equal(describeUserAccountsEmptyState(7, onPage3({ q: "x" })), null);
});

test("after creating an account, a hiding role filter switches to the new role", () => {
  const next = stateAfterAccountCreated(onPage3({ role: "DONOR", q: "keep" }),
    { role: "ADMIN", status: "ACTIVE" });
  assert.equal(next.role, "ADMIN");
  assert.equal(next.page, 0);
  assert.equal(next.q, "keep");
});

test("after creating an account, a Suspended filter switches to Active", () => {
  assert.equal(stateAfterAccountCreated(onPage3({ status: "SUSPENDED" }),
    { role: "ADMIN", status: "ACTIVE" }).status, "ACTIVE");
});

test("after creating an account with nothing hiding it, the page stays where it is", () => {
  const state = onPage3({ role: "ADMIN" });
  assert.equal(stateAfterAccountCreated(state, { role: "ADMIN", status: "ACTIVE" }), state);
});

test("role button counts come from the count endpoint, All being the total", () => {
  const counts = { total: 63, donors: 58, hospitalStaff: 3, bloodBankStaff: 4, admins: 1 };
  assert.equal(roleCount(counts, ""), 63);
  assert.equal(roleCount(counts, "DONOR"), 58);
  assert.equal(roleCount(counts, "HOSPITAL_STAFF"), 3);
  assert.equal(roleCount(counts, "BLOOD_BANK_STAFF"), 4);
  assert.equal(roleCount(counts, "ADMIN"), 1);
  assert.equal(roleCount(null, "DONOR"), 0);
});
