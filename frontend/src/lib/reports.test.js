import { test } from "node:test";
import assert from "node:assert/strict";

import {
  toIsoDay, defaultReportRange, validateReportRange, describeRange, formatRate,
  barPercent, maxCount, describeStockStatus, requestStatusLabel, urgencyLabel,
  isReportEmpty, countNoun, shareOf,
} from "./reports.js";

test("the default range is the last six calendar months, in UTC, like the server", () => {
  assert.deepEqual(defaultReportRange(new Date("2026-10-15T12:00:00Z")), { from: "2026-05-01", to: "2026-10-15" });
  // Crosses a year boundary.
  assert.deepEqual(defaultReportRange(new Date("2026-02-03T00:00:00Z")), { from: "2025-09-01", to: "2026-02-03" });
  // Late evening UTC is still that UTC day.
  assert.equal(toIsoDay(new Date("2026-10-15T23:59:59Z")), "2026-10-15");
});

test("a range must have both ends, run forwards and span at most five years", () => {
  assert.equal(validateReportRange({ from: "2026-01-01", to: "2026-06-30" }), null);
  assert.equal(validateReportRange({ from: "2026-06-30", to: "2026-06-30" }), null);   // one day
  assert.equal(validateReportRange({ from: "", to: "2026-06-30" }).field, "from");
  assert.equal(validateReportRange({ from: "2026-01-01", to: "" }).field, "to");
  assert.equal(validateReportRange({ from: "2026-07-01", to: "2026-06-30" }).field, "from");
  assert.equal(validateReportRange({ from: "2020-01-01", to: "2025-01-01" }), null);   // exactly five years
  assert.equal(validateReportRange({ from: "2020-01-01", to: "2025-01-02" }).field, "to");
});

test("a rate with nothing decided reads No data, never 0% or NaN", () => {
  assert.equal(formatRate(null), "No data");
  assert.equal(formatRate(undefined), "No data");
  assert.equal(formatRate(NaN), "No data");
  assert.equal(formatRate(0), "0.0%");
  assert.equal(formatRate(0.75), "75.0%");
  assert.equal(formatRate(2 / 3), "66.7%");
  assert.equal(formatRate(1), "100.0%");
});

test("bars are sized against the largest value and an all-zero chart draws nothing", () => {
  assert.equal(barPercent(5, 10), 50);
  assert.equal(barPercent(10, 10), 100);
  assert.equal(barPercent(0, 10), 0);
  assert.equal(barPercent(0, 0), 0);
  assert.equal(barPercent(3, 0), 0);
  assert.equal(maxCount([{ count: 2 }, { count: 9 }, { count: 4 }]), 9);
  assert.equal(maxCount([]), 0);
  assert.equal(maxCount([{ units: 7 }], "units"), 7);
});

test("statuses and urgencies are worded as the rest of the app words them", () => {
  assert.deepEqual(describeStockStatus("CRITICAL"), { label: "Critical", tone: "critical" });
  assert.deepEqual(describeStockStatus("LOW"), { label: "Low", tone: "low" });
  assert.deepEqual(describeStockStatus("SUFFICIENT"), { label: "Sufficient", tone: "sufficient" });
  assert.equal(requestStatusLabel("PARTIALLY_FULFILLED"), "Partly filled");
  assert.equal(requestStatusLabel("CANCELLED"), "Cancelled");
  assert.equal(urgencyLabel("EMERGENCY"), "Emergency");
});

test("a section is empty when it has nothing to count", () => {
  assert.equal(isReportEmpty("requests", { total: 0 }), true);
  assert.equal(isReportEmpty("requests", { total: 3 }), false);
  assert.equal(isReportEmpty("donations", { total: 0 }), true);
  assert.equal(isReportEmpty("donors", { total: 0 }), true);
  assert.equal(isReportEmpty("stock", { banks: [], totalUnits: 0 }), true);
  // A bank with no stock at all is news, not "no data".
  assert.equal(isReportEmpty("stock", { banks: [{ bankName: "New Bank" }], totalUnits: 0 }), false);
  assert.equal(isReportEmpty("requests", null), true);
});

test("small wording helpers", () => {
  assert.equal(countNoun(1, "donor"), "1 donor");
  assert.equal(countNoun(1234, "unit"), "1,234 units");
  assert.equal(countNoun(0, "request"), "0 requests");
  assert.equal(shareOf(1, 4), "25%");
  assert.equal(shareOf(0, 0), "—");
  assert.equal(describeRange({ from: "2026-05-01", to: "2026-10-15" }), "1 May 2026 – 15 October 2026");
});
