import test from "node:test";
import assert from "node:assert/strict";

import { appealButtonLabel, appealButtonText } from "./appealStatus.js";

test("a recent appeal with an acceptance shows the response state", () => {
  const item = { bloodType: "O+", appealAvailability: "COOLING_DOWN", appealAccepted: true };

  assert.equal(appealButtonText(item), "Donor Responded");
  assert.equal(appealButtonLabel(item), "A donor accepted your appeal for O+");
});

test("a recent appeal without an acceptance retains its sent state", () => {
  const item = { bloodType: "O+", appealAvailability: "COOLING_DOWN", appealAccepted: false };

  assert.equal(appealButtonText(item), "Appeal Sent.");
  assert.match(appealButtonLabel(item), /sent recently/);
});
