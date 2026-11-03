import assert from "node:assert/strict";
import test from "node:test";

import {
  CANCELLATION_NOTE_MAX,
  CANCELLATION_REASONS,
  canCancelRequest,
  cancelQuestion,
  cancellationProblem,
  cancellationReasonLabel,
  cancelledReasonLine,
  cancelledSummary,
  suppliedUnitsNotice,
} from "./requestCancellation.js";

test("the five reasons, each with its label", () => {
  assert.deepEqual(CANCELLATION_REASONS.map((option) => option.value), [
    "NO_LONGER_NEEDED", "PATIENT_TRANSFERRED", "OBTAINED_ELSEWHERE", "ENTERED_BY_MISTAKE", "OTHER",
  ]);
  assert.equal(cancellationReasonLabel("OBTAINED_ELSEWHERE"), "Obtained elsewhere");
});

test("only an open request can be cancelled", () => {
  assert.equal(canCancelRequest({ status: "PENDING" }), true);
  assert.equal(canCancelRequest({ status: "PARTIALLY_FULFILLED" }), true);
  assert.equal(canCancelRequest({ status: "FULFILLED" }), false);
  assert.equal(canCancelRequest({ status: "REJECTED" }), false);
  assert.equal(canCancelRequest({ status: "CANCELLED" }), false);
});

test("a reason is required", () => {
  assert.equal(cancellationProblem({ reason: "", note: "" }).field, "reason");
  assert.equal(cancellationProblem({ reason: "NO_LONGER_NEEDED", note: "" }), null);
});

test("a note is required for Other, and blank does not count", () => {
  assert.equal(cancellationProblem({ reason: "OTHER", note: "   " }).field, "note");
  assert.equal(cancellationProblem({ reason: "OTHER", note: "Ward closed" }), null);
});

test("a note may be 200 characters but not 201, on any reason", () => {
  assert.equal(cancellationProblem({ reason: "OTHER", note: "x".repeat(CANCELLATION_NOTE_MAX) }), null);
  assert.equal(cancellationProblem({ reason: "NO_LONGER_NEEDED", note: "x".repeat(201) }).field, "note");
});

test("the confirmation names the request", () => {
  assert.equal(
    cancelQuestion({ reference: "3F2A9C1B" }),
    "Cancel request #3F2A9C1B? Blood banks will stop working on it. This cannot be undone.",
  );
});

test("the box explains what happens to units already supplied", () => {
  assert.equal(suppliedUnitsNotice({ unitsContributed: 0, unitsRequested: 5 }), null);
  assert.equal(
    suppliedUnitsNotice({ unitsContributed: 3, unitsRequested: 5 }),
    "3 of 5 units already supplied stay supplied. Only the remaining 2 units are cancelled.",
  );
  assert.equal(
    suppliedUnitsNotice({ unitsContributed: 4, unitsRequested: 5 }),
    "4 of 5 units already supplied stay supplied. Only the remaining 1 unit is cancelled.",
  );
});

test("a cancelled request keeps its supplied count", () => {
  assert.equal(cancelledSummary({ unitsContributed: 3, unitsRequested: 5 }), "Cancelled - 3 of 5 units supplied");
  assert.equal(cancelledSummary({ unitsContributed: 0, unitsRequested: 5 }), "Cancelled");
});

test("the reason line shows the note only when there is one", () => {
  assert.equal(cancelledReasonLine({ cancellationReason: "PATIENT_TRANSFERRED" }), "Reason: Patient transferred");
  assert.equal(
    cancelledReasonLine({ cancellationReason: "OTHER", cancellationNote: "Ward closed" }),
    "Reason: Other - Ward closed",
  );
});
