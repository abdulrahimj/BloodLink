import { test } from "node:test";
import assert from "node:assert/strict";

import { describeShowingRange } from "./pagination.js";

// The other functions in pagination.js are covered by auditLogPaging.test.js, which
// imports them under their original Audit Log names.

test("the first page shows rows 1 to size", () => {
  assert.equal(describeShowingRange(0, 13, 58), "Showing 1-13 of 58");
});

test("a middle page shows the next block", () => {
  assert.equal(describeShowingRange(1, 13, 58), "Showing 14-26 of 58");
});

test("the last page is clamped to the total, never past it", () => {
  assert.equal(describeShowingRange(4, 13, 58), "Showing 53-58 of 58");
});

test("a result that fits on one page shows all of it", () => {
  assert.equal(describeShowingRange(0, 13, 5), "Showing 1-5 of 5");
});

test("an empty result shows zero, not 1-0", () => {
  assert.equal(describeShowingRange(0, 13, 0), "Showing 0 of 0");
});

test("a page past the end shows zero rather than an impossible range", () => {
  assert.equal(describeShowingRange(9, 13, 58), "Showing 0 of 58");
});
