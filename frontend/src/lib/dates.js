/* BloodLink — shared date formatting for the Blood Bank portal screens,
   so the Dashboard and Inventory format dates identically. */

const MONTH_NAMES = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];

/** Parses "YYYY-MM-DD" as a local calendar date, since new Date("YYYY-MM-DD") would shift a day west of UTC. */
export function parseIsoDate(isoDate) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new Date(year, month - 1, day);
}

/** "28 Aug 2026, 09:40" — matches the mockup's "Last Updated" column exactly. */
export function formatDateTime(instantIso) {
  const d = new Date(instantIso);
  const day = d.getDate();
  const month = MONTH_NAMES[d.getMonth()].slice(0, 3);
  const year = d.getFullYear();
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  return `${day} ${month} ${year}, ${hh}:${mm}`;
}

/** "September 5 2026" — matches both mockups' header date, computed for real. */
export function formatHeaderDate(date) {
  return `${MONTH_NAMES[date.getMonth()]} ${date.getDate()} ${date.getFullYear()}`;
}

/**
 * "8 mins ago", "3 hrs ago", "Yesterday", or a plain date once relative wording stops helping.
 */
export function formatRelativeTime(instantIso) {
  const then = new Date(instantIso);
  const diffMs = Date.now() - then.getTime();
  const minutes = Math.floor(diffMs / 60000);

  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes} min${minutes === 1 ? "" : "s"} ago`;

  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hr${hours === 1 ? "" : "s"} ago`;

  const days = Math.floor(hours / 24);
  if (days === 1) return "Yesterday";
  if (days < 7) return `${days} days ago`;

  return formatFullDate(instantIso.slice(0, 10));
}

/** "12 May 2026" — shared full-date form (donor portal keeps its own local copy). */
export function formatFullDate(isoDate) {
  const d = parseIsoDate(isoDate);
  return `${d.getDate()} ${MONTH_NAMES[d.getMonth()]} ${d.getFullYear()}`;
}

/** "RB" from "Rahim Barrie" — first letter of up to the first two words. */
export function initials(fullName) {
  return fullName
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}
