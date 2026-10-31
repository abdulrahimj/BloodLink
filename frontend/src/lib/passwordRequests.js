/* BloodLink — "Forgot password?" (Business Rule 33): the Sign in form's wording, and the rules and wording of the
   Admin Console's Password requests page. The server enforces every rule again. */

/** Shown after every request, whatever the number: the server never says whether it is registered. */
export const REQUEST_SENT_MESSAGE =
  "If this number is registered, an administrator will contact you. Keep your phone nearby.";

/** The notification type that opens the Password requests page from the bell. */
export const PASSWORD_REQUEST_NOTIFICATION = "PASSWORD_RESET_REQUESTED";

/** The note "Mark done" may carry, as the server allows. */
export const MAX_NOTE_LENGTH = 200;

/** Shown on the signed-in administrator's own request instead of Reset password. */
export const OWN_REQUEST_NOTE = "This is your own request. Another administrator must reset your password.";

/** A tel: link for a phone number as stored or configured: digits and a leading + only. */
export function telHref(phone) {
  const trimmed = String(phone ?? "").trim();
  const digits = trimmed.replace(/[^\d]/g, "");
  return digits ? `tel:${trimmed.startsWith("+") ? "+" : ""}${digits}` : null;
}

/** The phone field's own check before sending, the same as sign-in's: only that something was typed. */
export function phoneFieldProblem(phone) {
  return String(phone ?? "").trim() ? null : "Enter your phone number.";
}

/** "1 password reset request waiting." / "3 password reset requests waiting."; null when none. */
export function waitingBannerText(count) {
  if (!count || count < 1) return null;
  return `${count} password reset request${count === 1 ? "" : "s"} waiting.`;
}

/** The menu badge: hidden at zero, capped at 99+ so it cannot widen the sidebar. */
export function badgeLabel(count) {
  if (!count || count < 1) return null;
  return count > 99 ? "99+" : String(count);
}

/** How a done request ended, as the Done list words it. */
export function resolutionLabel(resolution) {
  if (resolution === "RESET") return "Password reset";
  if (resolution === "CLOSED") return "Closed";
  return "";
}

/** The Mark done note's check: optional, at most MAX_NOTE_LENGTH characters. */
export function noteProblem(note) {
  return String(note ?? "").length > MAX_NOTE_LENGTH
    ? `Keep the note to ${MAX_NOTE_LENGTH} characters or fewer.`
    : null;
}

/**
 * What a row offers: Call and Mark done on any waiting request; Reset password only on someone else's, since
 * nobody resets their own password here (Business Rule 31). A done row offers nothing.
 */
export function requestActions(request, currentUserId) {
  const waiting = request?.status === "WAITING";
  const own = request?.account?.id === currentUserId;
  return {
    canCall: waiting && Boolean(telHref(request?.account?.phone)),
    canReset: waiting && !own,
    canMarkDone: waiting,
    ownRequest: waiting && own,
  };
}
