/* ================================================================
   BloodLink — Admin Reports page logic (Business Rule 23)

   Pure functions behind AdminReports.jsx, kept here so `npm test` can check them.
   None of them counts anything: every figure arrives already counted by the
   database. What lives here is how those figures are worded and drawn — the
   default date range, the range check that saves a round trip, "No data" in place
   of a meaningless percentage, a bar's width, a status badge's wording.

   The server stays the authority on the range (it refuses a bad one with a 400
   naming the field); checking it here as well only spares the administrator a
   request that is certain to be refused.
   ================================================================ */

import { formatFullDate } from "./dates.js";

/** The longest range the server accepts — ReportRange.MAX_YEARS on the backend. */
export const MAX_RANGE_YEARS = 5;

/** A Date's calendar day in UTC as "YYYY-MM-DD" — the server's own day (UTC). */
export function toIsoDay(date) {
  return date.toISOString().slice(0, 10);
}

/**
 * The range the page opens with, matching the server's own default: the 1st of
 * the month five months back, up to today — the last six calendar months, the same
 * six the Dashboard's trend chart shows. In UTC, like the server.
 */
export function defaultReportRange(today = new Date()) {
  const start = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() - 5, 1));
  return { from: toIsoDay(start), to: toIsoDay(today) };
}

/**
 * Why a range cannot be sent, as { field, message }, or null when it can. The same
 * three refusals the server makes — missing, backwards, longer than five years —
 * on the same fields ("from" for backwards, "to" for too long).
 */
export function validateReportRange({ from, to }) {
  if (!from) return { field: "from", message: "Choose a start date." };
  if (!to) return { field: "to", message: "Choose an end date." };
  if (from > to) return { field: "from", message: "The start date must be on or before the end date." };

  const [year, month, day] = from.split("-").map(Number);
  const limit = toIsoDay(new Date(Date.UTC(year + MAX_RANGE_YEARS, month - 1, day)));
  if (to > limit) {
    return { field: "to", message: `The date range cannot be longer than ${MAX_RANGE_YEARS} years.` };
  }
  return null;
}

/** "1 May 2026 – 15 October 2026", for the section notes and headings. */
export function describeRange({ from, to }) {
  return `${formatFullDate(from)} – ${formatFullDate(to)}`;
}

/** "75.0%", or "No data" when nothing in the range was decided (the server sends
 *  null rather than dividing by zero) — never "0%" or "NaN%". */
export function formatRate(rate) {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return "No data";
  return `${(rate * 100).toFixed(1)}%`;
}

/** A bar's width as a percentage of the largest value in its chart. Zero when the
 *  largest is zero, so an empty chart draws no bars instead of NaN-wide ones. */
export function barPercent(value, max) {
  if (!max || max <= 0 || !value || value <= 0) return 0;
  return Math.min(100, (value / max) * 100);
}

/** The largest count in a list of rows ({ count }), or 0 for none. */
export function maxCount(rows, key = "count") {
  return rows.reduce((max, row) => Math.max(max, row[key] ?? 0), 0);
}

/** Business Rule 3's status, as the inventory pages word and colour it. The
 *  server decides the status; this only names it. */
const STOCK_STATUS = {
  CRITICAL: { label: "Critical", tone: "critical" },
  LOW: { label: "Low", tone: "low" },
  SUFFICIENT: { label: "Sufficient", tone: "sufficient" },
};

export function describeStockStatus(status) {
  return STOCK_STATUS[status] ?? { label: status, tone: "" };
}

/** How each request status and urgency reads on the page. */
const REQUEST_STATUS_LABEL = {
  PENDING: "Pending",
  PARTIALLY_FULFILLED: "Partly filled",
  FULFILLED: "Fulfilled",
  REJECTED: "Rejected",
};

const URGENCY_LABEL = { ROUTINE: "Routine", URGENT: "Urgent", EMERGENCY: "Emergency" };

export function requestStatusLabel(status) {
  return REQUEST_STATUS_LABEL[status] ?? status;
}

export function urgencyLabel(urgency) {
  return URGENCY_LABEL[urgency] ?? urgency;
}

/**
 * Whether a section has nothing to show, so it says "No data for this period"
 * rather than drawing a page of zeros. Stock is never empty while a bank exists:
 * a bank with no stock is itself the news (eight Critical groups).
 */
export function isReportEmpty(section, report) {
  if (!report) return true;
  switch (section) {
    case "stock": return report.banks.length === 0 && report.totalUnits === 0;
    case "requests": return report.total === 0;
    case "donations": return report.total === 0;
    case "donors": return report.total === 0;
    default: return false;
  }
}

/** "1 donor", "3 donors" — and the same for any noun with a plain "s" plural. */
export function countNoun(n, noun) {
  return `${n.toLocaleString("en-GB")} ${noun}${n === 1 ? "" : "s"}`;
}

/** A share of a total as a whole percentage ("62%"), or "—" when the total is 0. */
export function shareOf(part, total) {
  if (!total) return "—";
  return `${Math.round((part / total) * 100)}%`;
}
