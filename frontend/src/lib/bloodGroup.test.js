import { test } from "node:test";
import assert from "node:assert/strict";

import {
  BLOOD_GROUP_CHANGE_UNCONFIRMED, BLOOD_GROUP_NOTE_MAX, bloodGroupEditProblems, describeBloodGroupEditQuestion,
  describeDonationGroupChangeQuestion, describeDonationGroupCheck, describeDonationRecorded,
  describeDonorPortalBloodGroup, isUnconfirmedBloodGroupChange, needsBloodGroupChangeConfirmation,
} from "./bloodGroup.js";

// ---------- Record Donation ----------

test("an empty profile says the tested group will be saved, and needs no confirmation", () => {
  assert.deepEqual(describeDonationGroupCheck(null, "B+"), {
    tone: "info", text: "This donor's blood group will be saved to their profile.",
  });
  assert.equal(needsBloodGroupChangeConfirmation(null, "B+"), false);
});

test("a tested group that differs warns before saving and needs confirmation", () => {
  const check = describeDonationGroupCheck("A+", "B+");
  assert.equal(check.tone, "warn");
  assert.match(check.text, /^This donor's profile says A\+, but the tested group is B\+\./);
  assert.equal(needsBloodGroupChangeConfirmation("A+", "B+"), true);
  assert.equal(
    describeDonationGroupChangeQuestion("Rahim Barrie", "A+", "B+"),
    "Change Rahim Barrie's blood group from A+ to B+ and record this donation?",
  );
});

test("the same group, or no tested group chosen yet, shows nothing and needs no confirmation", () => {
  assert.equal(describeDonationGroupCheck("O+", "O+"), null);
  assert.equal(describeDonationGroupCheck("O+", ""), null);
  assert.equal(describeDonationGroupCheck(null, ""), null);
  assert.equal(needsBloodGroupChangeConfirmation("O+", "O+"), false);
  assert.equal(needsBloodGroupChangeConfirmation("O+", ""), false);
});

test("only the server's coded 409 counts as an unconfirmed change", () => {
  assert.equal(isUnconfirmedBloodGroupChange({ status: 409, code: BLOOD_GROUP_CHANGE_UNCONFIRMED }), true);
  // A 409 for a donor in recovery carries no code and must not be mistaken for it.
  assert.equal(isUnconfirmedBloodGroupChange({ status: 409, code: null }), false);
  assert.equal(isUnconfirmedBloodGroupChange(null), false);
});

test("the success notice mentions the profile only when it changed", () => {
  const receipt = { unitsDonated: 1, bloodType: "B+", donorName: "Rahim Barrie", stockAfter: 12 };
  assert.equal(describeDonationRecorded({ ...receipt, profileBloodTypeUpdated: false }),
    "Donation recorded — 1 unit of B+ from Rahim Barrie. B+ stock is now 12 units.");
  assert.equal(describeDonationRecorded({ ...receipt, unitsDonated: 2, profileBloodTypeUpdated: true }),
    "Donation recorded — 2 units of B+ from Rahim Barrie. B+ stock is now 12 units. "
      + "Their profile blood group is now B+.");
});

// ---------- Edit blood group ----------

test("the edit form needs a group, a different group and a note", () => {
  assert.deepEqual(bloodGroupEditProblems({ currentBloodType: "A+", bloodType: "", note: "" }), {
    bloodType: "Choose the tested blood group",
    note: "Add a note saying how or where the test was done",
  });
  assert.deepEqual(bloodGroupEditProblems({ currentBloodType: "A+", bloodType: "A+", note: "Retested" }), {
    bloodType: "Their blood group is already A+",
  });
  assert.deepEqual(bloodGroupEditProblems({ currentBloodType: "A+", bloodType: "B+", note: "   " }), {
    note: "Add a note saying how or where the test was done",
  });
});

test("the note may be 500 characters after trimming, not 501", () => {
  const atLimit = "x".repeat(BLOOD_GROUP_NOTE_MAX);
  assert.deepEqual(bloodGroupEditProblems({ currentBloodType: null, bloodType: "B+", note: `  ${atLimit}  ` }), {});
  assert.deepEqual(bloodGroupEditProblems({ currentBloodType: null, bloodType: "B+", note: `${atLimit}x` }), {
    note: "The note must be 500 characters or fewer",
  });
});

test("the confirmation asks to change a recorded group, or to set a missing one", () => {
  assert.equal(describeBloodGroupEditQuestion("Fatmata Kamara", "A+", "B+"),
    "Change Fatmata Kamara's blood group from A+ to B+?");
  assert.equal(describeBloodGroupEditQuestion("Fatmata Kamara", null, "B+"), "Set Fatmata Kamara's blood group to B+?");
});

// ---------- Donor Portal ----------

test("the donor portal explains a missing group and shows a recorded one as it is", () => {
  assert.deepEqual(describeDonorPortalBloodGroup(null), {
    figure: "Not recorded",
    notice: "Your blood group isn't recorded yet. Visit a blood bank to be tested, "
      + "and it will be added after your first donation.",
  });
  assert.deepEqual(describeDonorPortalBloodGroup("O-"), { figure: "O-", notice: null });
});
