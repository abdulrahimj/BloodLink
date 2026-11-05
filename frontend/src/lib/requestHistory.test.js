import { test } from "node:test";
import assert from "node:assert/strict";

import {
  HISTORY_PAGE_SIZE,
  initialHistoryState,
  applyHistoryFilterChange,
  goToHistoryPage,
  presetRange,
  customRangeProblem,
  buildHistoryParams,
  describeHistoryEmptyState,
  outcomeBadge,
  suppliedBy,
  unitsAndGroup,
  closingExplanation,
} from "./requestHistory.js";

const TODAY = new Date("2026-11-04T15:30:00Z");

function request(overrides = {}) {
  return {
    status: "FULFILLED", unitsRequested: 5, unitsContributed: 5, bloodType: "O+",
    contributions: [], cancellationReason: null, cancellationNote: null, ...overrides,
  };
}

test("starts on page 0 with every filter empty and all time", () => {
  assert.deepEqual(initialHistoryState(),
    { page: 0, status: "", preset: "all", from: "", to: "", bloodType: "", hospitalId: "" });
});

test("a filter change returns to page 0; going to a page keeps the filters", () => {
  const onPage3 = { ...initialHistoryState(), page: 3, status: "REJECTED" };
  assert.equal(applyHistoryFilterChange(onPage3, { bloodType: "O-" }).page, 0);
  const moved = goToHistoryPage(onPage3, 1);
  assert.equal(moved.page, 1);
  assert.equal(moved.status, "REJECTED");
});

test("presets count back from today, both ends included", () => {
  assert.deepEqual(presetRange("7", TODAY), { from: "2026-10-29", to: "2026-11-04" });
  assert.deepEqual(presetRange("30", TODAY), { from: "2026-10-06", to: "2026-11-04" });
  assert.deepEqual(presetRange("90", TODAY), { from: "2026-08-07", to: "2026-11-04" });
  assert.deepEqual(presetRange("all", TODAY), { from: "", to: "" });
});

test("a backwards custom range is refused before it is sent", () => {
  assert.equal(customRangeProblem({ preset: "custom", from: "2026-11-05", to: "2026-11-01" }),
    "The start date must be on or before the end date.");
  assert.equal(customRangeProblem({ preset: "custom", from: "2026-11-01", to: "2026-11-01" }), null);
  assert.equal(customRangeProblem({ preset: "custom", from: "2026-11-01", to: "" }), null);
  assert.equal(customRangeProblem({ preset: "7", from: "2026-11-05", to: "2026-11-01" }), null);
});

test("the default query is page and size only", () => {
  assert.deepEqual(buildHistoryParams(initialHistoryState(), TODAY, "hospital"), { page: 0, size: HISTORY_PAGE_SIZE });
});

test("every filter appears in the query, a preset as from and to", () => {
  const state = { page: 2, status: "CANCELLED", preset: "7", from: "", to: "", bloodType: "AB-", hospitalId: "h1" };
  assert.deepEqual(buildHistoryParams(state, TODAY, "bank"), {
    page: 2, size: 20, status: "CANCELLED", bloodType: "AB-", hospitalId: "h1", from: "2026-10-29", to: "2026-11-04",
  });
});

test("a hospital never sends hospitalId, and a custom range sends only the ends given", () => {
  const state = { ...initialHistoryState(), preset: "custom", from: "2026-09-01", hospitalId: "h1" };
  assert.deepEqual(buildHistoryParams(state, TODAY, "hospital"), { page: 0, size: 20, from: "2026-09-01" });
});

test("the empty message tells an empty history from filters matching nothing", () => {
  assert.equal(describeHistoryEmptyState(3, initialHistoryState()), null);
  assert.match(describeHistoryEmptyState(0, initialHistoryState()), /^No finished requests yet\./);
  assert.equal(describeHistoryEmptyState(0, { ...initialHistoryState(), preset: "30" }),
    "No finished requests match these filters.");
});

test("a bank's badge says what it gave or that it declined", () => {
  assert.deepEqual(outcomeBadge({ request: request(), unitsFromYourBank: 3, declinedByYourBank: false }, "bank"),
    { text: "Fulfilled · 3 from us", tone: "fulfilled" });
  assert.equal(outcomeBadge({ request: request(), unitsFromYourBank: 0, declinedByYourBank: true }, "bank").text,
    "Fulfilled · we declined");
  assert.equal(outcomeBadge({ request: request(), unitsFromYourBank: null, declinedByYourBank: null }, "hospital").text,
    "Fulfilled");
});

test("cancelled and rejected badges", () => {
  const partly = request({ status: "CANCELLED", unitsContributed: 2 });
  assert.deepEqual(outcomeBadge({ request: partly }, "hospital"), { text: "Cancelled · 2 of 5 supplied", tone: "cancelled" });
  assert.equal(outcomeBadge({ request: request({ status: "CANCELLED", unitsContributed: 0 }) }, "bank").text,
    "Cancelled · none supplied");
  assert.deepEqual(outcomeBadge({ request: request({ status: "REJECTED", unitsContributed: 0 }) }, "bank"),
    { text: "Rejected", tone: "rejected" });
});

test("supplied by lists each bank once with its total", () => {
  const contributions = [
    { bloodBankName: "Makeni Regional Blood Bank", unitsContributed: 1 },
    { bloodBankName: "Holy Spirit Blood Bank", unitsContributed: 1 },
    { bloodBankName: "Makeni Regional Blood Bank", unitsContributed: 2 },
  ];
  assert.equal(suppliedBy(request({ contributions })), "Makeni Regional Blood Bank (3), Holy Spirit Blood Bank (1)");
  assert.equal(suppliedBy(request()), "None");
});

test("units and group, singular and plural", () => {
  assert.equal(unitsAndGroup(request()), "5 units O+");
  assert.equal(unitsAndGroup(request({ unitsRequested: 1, bloodType: "AB-" })), "1 unit AB-");
});

test("the closing explanation; a bank, sent no note, sees the reason alone", () => {
  assert.equal(closingExplanation(request()), "All 5 units were supplied.");
  assert.equal(closingExplanation(request({ unitsRequested: 1 })), "All 1 unit was supplied.");
  assert.equal(closingExplanation(request({ status: "REJECTED" })), "Every blood bank declined this request.");
  const forBank = request({ status: "CANCELLED", cancellationReason: "OTHER", cancellationNote: null });
  assert.equal(closingExplanation(forBank), "Reason: Other");
  const forHospital = { ...forBank, cancellationNote: "Ward closed" };
  assert.equal(closingExplanation(forHospital), "Reason: Other - Ward closed");
});
