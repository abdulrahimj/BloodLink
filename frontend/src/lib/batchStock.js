/* BloodLink — wording and checks for batch-level stock (Business Rule 24): batch states, days to expiry,
   sources, write-off reasons and the write-off form's checks, mirroring the server's rules. */

/** "1 unit" / "4 units". */
export function unitsText(count) {
  return `${count} unit${count === 1 ? "" : "s"}`;
}

/** The server's three batch states, as shown on a badge, with the badge colour. */
const BATCH_STATE = {
  USABLE: { label: "Usable", tone: "green" },
  EXPIRING_SOON: { label: "Expiring soon", tone: "amber" },
  EXPIRED: { label: "Expired", tone: "red" },
};

export function describeBatchState(state) {
  return BATCH_STATE[state] ?? { label: state, tone: "" };
}

/** An expired batch still holding units: the ones staff must write off, shown in red. */
export function needsWriteOff(batch) {
  return batch.state === "EXPIRED" && batch.unitsRemaining > 0;
}

/** The server's daysUntilExpiry in words: 0 is the last usable day, negative is expired. */
export function daysUntilExpiryText(days) {
  if (days > 1) return `Expires in ${days} days`;
  if (days === 1) return "Expires tomorrow";
  if (days === 0) return "Expires today";
  if (days === -1) return "Expired yesterday";
  return `Expired ${-days} days ago`;
}

/** Where a batch came from, in plain words. */
const BATCH_SOURCE = {
  DONATION: "From a donation",
  MANUAL: "Added by staff",
  MIGRATED: "From old records (check the bag label)",
};

export function batchSourceLabel(source) {
  return BATCH_SOURCE[source] ?? source;
}

/** The five write-off reasons, in the server's names, labelled as the audit log words them, with a hint. */
export const WRITE_OFF_REASONS = [
  { value: "EXPIRED", label: "Expired", hint: "Past its expiry date." },
  { value: "DAMAGED", label: "Damaged", hint: "For example a leaking bag or a broken cold chain." },
  { value: "FAILED_TESTING", label: "Failed testing", hint: "A screening or compatibility test failed." },
  { value: "DATA_ENTRY_ERROR", label: "Data entry error", hint: "The batch was entered wrongly. Add it again correctly." },
  { value: "OTHER", label: "Other", hint: "Please explain in the note." },
];

export function writeOffReasonLabel(reason) {
  return WRITE_OFF_REASONS.find((r) => r.value === reason)?.label ?? reason;
}

/** The server's limit on a write-off note, after trimming. */
export const NOTE_MAX_LENGTH = 500;

/**
 * The form's checks before sending, the same rules the server applies; returns { field: message },
 * empty when the form may be sent. The server still checks everything.
 */
export function validateWriteOff({ units, reason, note }, unitsRemaining) {
  const errors = {};
  const typed = String(units ?? "").trim();
  const count = Number(typed);

  if (typed === "") errors.units = "Enter how many units to write off";
  else if (!Number.isInteger(count)) errors.units = "Enter a whole number of units";
  else if (count < 1) errors.units = "Must write off at least 1 unit";
  else if (count > unitsRemaining) errors.units = `This batch has only ${unitsText(unitsRemaining)} left`;

  if (!WRITE_OFF_REASONS.some((r) => r.value === reason)) errors.reason = "Choose a reason";

  const cleanNote = (note ?? "").trim();
  if (cleanNote.length > NOTE_MAX_LENGTH) {
    errors.note = `A note must be at most ${NOTE_MAX_LENGTH} characters`;
  } else if (reason === "OTHER" && cleanNote === "") {
    errors.note = "A note is required when the reason is Other";
  }
  return errors;
}

/** The request body for POST .../write-offs: units as a number, blank note sent as none. */
export function writeOffPayload({ units, reason, note }) {
  const cleanNote = (note ?? "").trim();
  return { units: Number(String(units).trim()), reason, note: cleanNote === "" ? null : cleanNote };
}

/** The red line above a group's batches when some have expired with units left. */
export function expiredWarning(count, canWriteOff) {
  const one = count === 1;
  const subject = one ? "1 expired batch still holds units. It cannot" : `${count} expired batches still hold units. They cannot`;
  return `${subject} be issued${canWriteOff ? ` — please write ${one ? "it" : "them"} off.` : "."}`;
}

/** "Written off 3 units. 7 units are left in this batch." after a write-off succeeds. */
export function writeOffDoneText(unitsWrittenOff, unitsLeft) {
  if (unitsLeft === 0) {
    return `Written off ${unitsText(unitsWrittenOff)}. The batch is now empty, so it no longer appears in the list.`;
  }
  return `Written off ${unitsText(unitsWrittenOff)}. ${unitsText(unitsLeft)} ${unitsLeft === 1 ? "is" : "are"} left in this batch.`;
}

/** Notification types that change what the Inventory page shows, so it re-reads quietly when one arrives. */
const INVENTORY_REFRESH_TYPES = new Set([
  "LOW_STOCK_ALERT", "DONATION_APPEAL_ACCEPTED", "BATCH_EXPIRY_REMINDER", "BATCH_EXPIRED",
]);

export function refreshesInventory(notificationType) {
  return INVENTORY_REFRESH_TYPES.has(notificationType);
}
