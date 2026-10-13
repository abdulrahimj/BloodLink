import { test } from "node:test";
import assert from "node:assert/strict";

import {
  canGoToPreviousPage,
  canGoToNextPage,
  clampPageToAvailable,
  isAuditLogEmpty,
  describeAuditLogPage,
} from "./auditLogPaging.js";

test("Previous is disabled on the first page and enabled afterwards", () => {
  assert.equal(canGoToPreviousPage(0), false);
  assert.equal(canGoToPreviousPage(1), true);
  assert.equal(canGoToPreviousPage(5), true);
});

test("Next is disabled on the last page and enabled before it", () => {
  assert.equal(canGoToNextPage(2, 3), false); // page index 2 is the last of 3 pages
  assert.equal(canGoToNextPage(1, 3), true);
  assert.equal(canGoToNextPage(0, 0), false); // an empty log has no next page either
});

test("a stale page number is clamped down to the new last page", () => {
  assert.equal(clampPageToAvailable(5, 3), 2); // was on page 6 of what is now 3 pages
  assert.equal(clampPageToAvailable(1, 3), 1); // still valid, left alone
  assert.equal(clampPageToAvailable(-1, 3), 0); // never goes negative
});

test("clamping against zero total pages lands on page 0", () => {
  assert.equal(clampPageToAvailable(4, 0), 0);
});

test("an empty log is recognised by zero total elements", () => {
  assert.equal(isAuditLogEmpty(0), true);
  assert.equal(isAuditLogEmpty(1), false);
});

test("the page label is one-based and never claims a zeroth page", () => {
  assert.equal(describeAuditLogPage(0, 3), "Page 1 of 3");
  assert.equal(describeAuditLogPage(2, 3), "Page 3 of 3");
  // An empty log reports zero total pages from the server; the label still
  // describes a real page rather than "Page 1 of 0".
  assert.equal(describeAuditLogPage(0, 0), "Page 1 of 1");
});
