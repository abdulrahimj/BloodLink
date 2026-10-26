/* BloodLink — wording for the "Send appeal" confirmation, kept as plain functions so npm test can check it. */

/** "1 donor" / "12 donors". */
export function donorCount(n) {
  return `${n} donor${n === 1 ? "" : "s"}`;
}

/**
 * The confirmation dialog's two lines from GET /api/appeals/reach, telling apart nobody registered,
 * everyone recovering, some recovering and everyone eligible. Returns { heading, note, nobodyToReach }.
 */
export function describeAppealReach(bloodType, eligible, inRecovery) {
  const nobodyToReach = eligible === 0;
  const registered = eligible + inRecovery;

  if (registered === 0) {
    return {
      heading: `No active ${bloodType} donors`,
      note: "No active donors of this blood type right now. There is nobody to appeal to.",
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
