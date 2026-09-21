import { test } from "node:test";
import assert from "node:assert/strict";

import { describeAppealReach } from "./appealReach.js";

test("no donors registered at all says so, and never claims everyone is eligible", () => {
  const { heading, note, nobodyToReach } = describeAppealReach("AB-", 0, 0);

  assert.equal(nobodyToReach, true);
  assert.equal(heading, "No AB- donors registered");
  assert.match(note, /No donors of this blood type are registered yet/);
  // The contradiction this test exists to prevent: "every donor is eligible"
  // alongside "nobody to appeal to".
  assert.doesNotMatch(note, /currently eligible/);
});

test("donors who are all in recovery keep the message naming how many", () => {
  const { heading, note, nobodyToReach } = describeAppealReach("O+", 0, 3);

  assert.equal(nobodyToReach, true);
  assert.equal(heading, "No eligible O+ donors");
  assert.match(note, /3 donors of this group are within the 90-day recovery window/);
  assert.match(note, /nobody to appeal to right now/);
  assert.doesNotMatch(note, /registered yet/);
});

test("a single donor in recovery is singular", () => {
  const { note } = describeAppealReach("O+", 0, 1);

  assert.match(note, /1 donor of this group is within/);
});

test("some eligible and some recovering names the filter", () => {
  const { heading, note, nobodyToReach } = describeAppealReach("A+", 4, 2);

  assert.equal(nobodyToReach, false);
  assert.equal(heading, "4 donors will be notified");
  assert.match(note, /2 donors of this group are within the 90-day recovery window/);
  assert.doesNotMatch(note, /nobody to appeal to/);
});

test("everyone eligible says so", () => {
  const { heading, note, nobodyToReach } = describeAppealReach("B-", 1, 0);

  assert.equal(nobodyToReach, false);
  assert.equal(heading, "1 donor will be notified");
  assert.equal(note, "Every B- donor on the register is currently eligible.");
});
