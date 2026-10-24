import test from "node:test";
import assert from "node:assert/strict";

import {
  NOTE_MAX_LENGTH, WRITE_OFF_REASONS, batchSourceLabel, daysUntilExpiryText, describeBatchState, expiredWarning,
  needsWriteOff, refreshesInventory, unitsText, validateWriteOff, writeOffDoneText, writeOffPayload,
  writeOffReasonLabel,
} from "./batchStock.js";

const valid = { units: "3", reason: "EXPIRED", note: "" };

test("each batch state has a plain label and its colour", () => {
  assert.deepEqual(describeBatchState("USABLE"), { label: "Usable", tone: "green" });
  assert.deepEqual(describeBatchState("EXPIRING_SOON"), { label: "Expiring soon", tone: "amber" });
  assert.deepEqual(describeBatchState("EXPIRED"), { label: "Expired", tone: "red" });
});

test("only an expired batch that still holds units needs writing off", () => {
  assert.equal(needsWriteOff({ state: "EXPIRED", unitsRemaining: 2 }), true);
  assert.equal(needsWriteOff({ state: "EXPIRED", unitsRemaining: 0 }), false);
  assert.equal(needsWriteOff({ state: "EXPIRING_SOON", unitsRemaining: 5 }), false);
});

test("days until expiry read naturally either side of the expiry day", () => {
  assert.equal(daysUntilExpiryText(7), "Expires in 7 days");
  assert.equal(daysUntilExpiryText(1), "Expires tomorrow");
  assert.equal(daysUntilExpiryText(0), "Expires today");
  assert.equal(daysUntilExpiryText(-1), "Expired yesterday");
  assert.equal(daysUntilExpiryText(-12), "Expired 12 days ago");
});

test("sources and reasons have readable labels; the five reasons match the server's names", () => {
  assert.equal(batchSourceLabel("DONATION"), "From a donation");
  assert.equal(batchSourceLabel("MANUAL"), "Added by staff");
  assert.match(batchSourceLabel("MIGRATED"), /check the bag label/);
  assert.deepEqual(WRITE_OFF_REASONS.map((r) => r.value),
    ["EXPIRED", "DAMAGED", "FAILED_TESTING", "DATA_ENTRY_ERROR", "OTHER"]);
  assert.equal(writeOffReasonLabel("FAILED_TESTING"), "Failed testing");
  assert.equal(writeOffReasonLabel("DATA_ENTRY_ERROR"), "Data entry error");
});

test("a complete form passes", () => {
  assert.deepEqual(validateWriteOff(valid, 5), {});
  assert.deepEqual(validateWriteOff({ ...valid, units: "5" }, 5), {});
});

test("units must be a whole number from 1 to the units left", () => {
  assert.equal(validateWriteOff({ ...valid, units: "" }, 5).units, "Enter how many units to write off");
  assert.equal(validateWriteOff({ ...valid, units: "2.5" }, 5).units, "Enter a whole number of units");
  assert.equal(validateWriteOff({ ...valid, units: "0" }, 5).units, "Must write off at least 1 unit");
  assert.equal(validateWriteOff({ ...valid, units: "6" }, 5).units, "This batch has only 5 units left");
  assert.equal(validateWriteOff({ ...valid, units: "2" }, 1).units, "This batch has only 1 unit left");
});

test("a reason must be one of the five", () => {
  assert.equal(validateWriteOff({ ...valid, reason: "" }, 5).reason, "Choose a reason");
  assert.equal(validateWriteOff({ ...valid, reason: "LOST" }, 5).reason, "Choose a reason");
});

test("Other needs a note; spaces alone are not a note", () => {
  assert.equal(validateWriteOff({ ...valid, reason: "OTHER", note: "   " }, 5).note,
    "A note is required when the reason is Other");
  assert.deepEqual(validateWriteOff({ ...valid, reason: "OTHER", note: "Fridge alarm" }, 5), {});
});

test("a note may be 500 characters after trimming, not 501", () => {
  assert.equal(NOTE_MAX_LENGTH, 500);
  assert.deepEqual(validateWriteOff({ ...valid, note: `  ${"a".repeat(500)}  ` }, 5), {});
  assert.equal(validateWriteOff({ ...valid, note: "a".repeat(501) }, 5).note,
    "A note must be at most 500 characters");
});

test("the request body has numeric units and no blank note", () => {
  assert.deepEqual(writeOffPayload({ units: " 3 ", reason: "DAMAGED", note: "  " }),
    { units: 3, reason: "DAMAGED", note: null });
  assert.deepEqual(writeOffPayload({ units: "1", reason: "OTHER", note: " Dropped " }),
    { units: 1, reason: "OTHER", note: "Dropped" });
});

test("the success message says what is left, or that the batch is now empty", () => {
  assert.equal(writeOffDoneText(3, 7), "Written off 3 units. 7 units are left in this batch.");
  assert.equal(writeOffDoneText(1, 1), "Written off 1 unit. 1 unit is left in this batch.");
  assert.match(writeOffDoneText(4, 0), /now empty/);
  assert.equal(unitsText(1), "1 unit");
});

test("the expired-batches warning agrees in number, and only staff are asked to write off", () => {
  assert.equal(expiredWarning(1, true), "1 expired batch still holds units. It cannot be issued — please write it off.");
  assert.equal(expiredWarning(2, true), "2 expired batches still hold units. They cannot be issued — please write them off.");
  assert.equal(expiredWarning(2, false), "2 expired batches still hold units. They cannot be issued.");
});

test("stock and expiry notices refresh the Inventory page; others do not", () => {
  for (const type of ["LOW_STOCK_ALERT", "DONATION_APPEAL_ACCEPTED", "BATCH_EXPIRY_REMINDER", "BATCH_EXPIRED"]) {
    assert.equal(refreshesInventory(type), true, type);
  }
  assert.equal(refreshesInventory("NEW_REQUEST"), false);
});
