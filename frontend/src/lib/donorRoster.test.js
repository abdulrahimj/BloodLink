import { test } from "node:test";
import assert from "node:assert/strict";

import {
  initialDonorRosterState,
  applyDonorRosterFilterChange,
  goToDonorRosterPage,
  buildDonorRosterParams,
  hasActiveDonorRosterFilters,
  describeDonorRosterEmptyState,
  describeDonorEligibility,
} from "./donorRoster.js";

test("the initial state is page 0, size 20, sorted by last donation, no filters", () => {
  const state = initialDonorRosterState();
  assert.deepEqual(state, { page: 0, size: 20, q: "", bloodType: "", eligibility: "", sort: "lastDonation" });
});

test("changing a filter returns to page 0, whatever page was current", () => {
  const onPageThree = { ...initialDonorRosterState(), page: 3 };
  const next = applyDonorRosterFilterChange(onPageThree, { bloodType: "O-" });
  assert.equal(next.page, 0);
  assert.equal(next.bloodType, "O-");
});

test("changing the page size also returns to page 0", () => {
  const onPageThree = { ...initialDonorRosterState(), page: 3 };
  const next = applyDonorRosterFilterChange(onPageThree, { size: 50 });
  assert.equal(next.page, 0);
  assert.equal(next.size, 50);
});

test("going to a page does not disturb any filter", () => {
  const filtered = { ...initialDonorRosterState(), q: "Aminata", eligibility: "ELIGIBLE" };
  const next = goToDonorRosterPage(filtered, 2);
  assert.equal(next.page, 2);
  assert.equal(next.q, "Aminata");
  assert.equal(next.eligibility, "ELIGIBLE");
});

test("the default state builds a query with only page, size and sort", () => {
  const params = buildDonorRosterParams(initialDonorRosterState());
  assert.deepEqual(params, { page: 0, size: 20, sort: "lastDonation" });
});

test("a blank search box is left out of the query entirely", () => {
  const params = buildDonorRosterParams({ ...initialDonorRosterState(), q: "   " });
  assert.deepEqual(params, { page: 0, size: 20, sort: "lastDonation" });
});

test("every active filter and the trimmed search text appear in the query", () => {
  const params = buildDonorRosterParams({
    page: 1, size: 50, q: "  Kamara  ", bloodType: "O-", eligibility: "IN_RECOVERY", sort: "name",
  });
  assert.deepEqual(params, {
    page: 1, size: 50, sort: "name", q: "Kamara", bloodType: "O-", eligibility: "IN_RECOVERY",
  });
});

test("no filter active is recognised correctly", () => {
  assert.equal(hasActiveDonorRosterFilters(initialDonorRosterState()), false);
});

test("a search term, a blood-group filter, or an eligibility filter each count as active", () => {
  assert.equal(hasActiveDonorRosterFilters({ ...initialDonorRosterState(), q: "Kamara" }), true);
  assert.equal(hasActiveDonorRosterFilters({ ...initialDonorRosterState(), bloodType: "O-" }), true);
  assert.equal(hasActiveDonorRosterFilters({ ...initialDonorRosterState(), eligibility: "ELIGIBLE" }), true);
});

test("a whitespace-only search does not count as an active filter", () => {
  assert.equal(hasActiveDonorRosterFilters({ ...initialDonorRosterState(), q: "   " }), false);
});

test("a genuinely empty roster says so, with no mention of filters", () => {
  const message = describeDonorRosterEmptyState(0, initialDonorRosterState());
  assert.equal(message, "No donors have donated at this blood bank yet.");
});

test("an empty page caused by a filter says so instead", () => {
  const message = describeDonorRosterEmptyState(0, { ...initialDonorRosterState(), q: "Nobody" });
  assert.equal(message, "No donors match your filters.");
});

test("a non-empty page has no empty-state message", () => {
  assert.equal(describeDonorRosterEmptyState(5, initialDonorRosterState()), null);
});

test("an eligible donor gets the plain green badge", () => {
  const badge = describeDonorEligibility("ELIGIBLE", "12 May 2026");
  assert.deepEqual(badge, { text: "Eligible", tone: "green" });
});

test("a donor in recovery gets the amber badge naming the date", () => {
  const badge = describeDonorEligibility("IN_RECOVERY", "12 May 2026");
  assert.deepEqual(badge, { text: "Eligible from 12 May 2026", tone: "amber" });
});
