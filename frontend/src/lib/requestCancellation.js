/* BloodLink — cancelling a blood request (Business Rule 35): the box's checks and the wording both portals show.
   The checks mirror RequestService's and save a request; the server still decides. */

/** The size of blood_requests.cancellation_note (V32). */
export const CANCELLATION_NOTE_MAX = 200;

/** The five reasons, worded as the server words them in the notification and the audit entry. */
export const CANCELLATION_REASONS = [
  { value: "NO_LONGER_NEEDED", label: "No longer needed" },
  { value: "PATIENT_TRANSFERRED", label: "Patient transferred" },
  { value: "OBTAINED_ELSEWHERE", label: "Obtained elsewhere" },
  { value: "ENTERED_BY_MISTAKE", label: "Entered by mistake" },
  { value: "OTHER", label: "Other" },
];

export function cancellationReasonLabel(reason) {
  return CANCELLATION_REASONS.find((option) => option.value === reason)?.label ?? reason;
}

/** Only an open request can be cancelled: pending or partly supplied. */
export function canCancelRequest(request) {
  return request.status === "PENDING" || request.status === "PARTIALLY_FULFILLED";
}

/** Why the box can't be sent, as { field, message }, or null; the same two fields the server refuses on. */
export function cancellationProblem({ reason, note }) {
  if (!reason) return { field: "reason", message: "Choose a reason." };
  const clean = (note ?? "").trim();
  if (reason === "OTHER" && !clean) {
    return { field: "note", message: "Add a note explaining why this request is being cancelled." };
  }
  if (clean.length > CANCELLATION_NOTE_MAX) {
    return { field: "note", message: `A note must be at most ${CANCELLATION_NOTE_MAX} characters.` };
  }
  return null;
}

/** "Cancel request #3F2A9C1B? Blood banks will stop working on it. This cannot be undone." */
export function cancelQuestion(request) {
  return `Cancel request #${request.reference}? Blood banks will stop working on it. This cannot be undone.`;
}

/** What happens to units already here, for a partly supplied request; null when nothing has arrived. */
export function suppliedUnitsNotice(request) {
  if (request.unitsContributed === 0) return null;
  const remaining = request.unitsRequested - request.unitsContributed;
  return `${request.unitsContributed} of ${request.unitsRequested} units already supplied stay supplied. `
    + `Only the remaining ${remaining} ${remaining === 1 ? "unit is" : "units are"} cancelled.`;
}

/** "Cancelled - 3 of 5 units supplied", or plain "Cancelled" when none arrived. */
export function cancelledSummary(request) {
  return request.unitsContributed > 0
    ? `Cancelled - ${request.unitsContributed} of ${request.unitsRequested} units supplied`
    : "Cancelled";
}

/** "Reason: Other - Ward closed", the note only when this viewer was sent one. */
export function cancelledReasonLine(request) {
  const reason = `Reason: ${cancellationReasonLabel(request.cancellationReason)}`;
  return request.cancellationNote ? `${reason} - ${request.cancellationNote}` : reason;
}
