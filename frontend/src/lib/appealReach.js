/* ================================================================
   BloodLink — wording for the "Send appeal" confirmation

   Kept as plain functions in their own file, rather than inline in the dialog,
   so the wording can be tested without a browser: `npm test` runs
   appealReach.test.js with Node's built-in test runner.
   ================================================================ */

/** "1 donor" / "12 donors". */
export function donorCount(n) {
  return `${n} donor${n === 1 ? "" : "s"}`;
}

/**
 * What an appeal would reach, as the two lines the confirmation dialog shows.
 *
 * `eligible` and `inRecovery` come from GET /api/appeals/reach. Together they
 * separate four situations that need four different sentences:
 *
 *   • Nobody registered at all (both zero). The register has no donors of this
 *     group — a recruitment problem. Saying "every donor is currently eligible"
 *     here, as this once did, contradicts itself: if everyone were eligible there
 *     would be someone to appeal to.
 *   • Donors exist but every one is inside the 90-day recovery window (eligible
 *     zero, recovery above zero). The right answer is to wait, not to recruit, and
 *     the message says how many are recovering.
 *   • Some eligible and some recovering — the filter is named, so a small number
 *     reads as "recovering" rather than "missing".
 *   • Everyone eligible — nothing was held back.
 *
 * @returns {{ heading: string, note: string, nobodyToReach: boolean }}
 */
export function describeAppealReach(bloodType, eligible, inRecovery) {
  const nobodyToReach = eligible === 0;
  const registered = eligible + inRecovery;

  if (registered === 0) {
    return {
      heading: `No ${bloodType} donors registered`,
      note: "No donors of this blood type are registered yet. There is nobody to appeal to.",
      nobodyToReach,
    };
  }

  const heading = nobodyToReach
    ? `No eligible ${bloodType} donors`
    : `${donorCount(eligible)} will be notified`;

  const note = inRecovery > 0
    ? `${donorCount(inRecovery)} of this group ${inRecovery === 1 ? "is" : "are"} `
      + "within the 90-day recovery window and will not be contacted."
      + (nobodyToReach ? " There is nobody to appeal to right now." : "")
    : `Every ${bloodType} donor on the register is currently eligible.`;

  return { heading, note, nobodyToReach };
}
