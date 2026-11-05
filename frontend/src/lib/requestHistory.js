/* BloodLink — Request history page logic (Business Rule 36): filters, date presets, query building and the wording
   both portals show, as plain functions tested by npm test. Paging arithmetic is in lib/pagination.js. */

import { cancelledReasonLine } from "./requestCancellation.js";

/** One page is 20 requests, as the server's default. */
export const HISTORY_PAGE_SIZE = 20;

/** The three finished outcomes, labelled as the badges say them. */
export const HISTORY_STATUSES = [
  { value: "FULFILLED", label: "Fulfilled" },
  { value: "REJECTED", label: "Rejected" },
  { value: "CANCELLED", label: "Cancelled" },
];

/** The closed-date choices; "custom" uses the two date boxes. */
export const DATE_PRESETS = [
  { value: "all", label: "All time" },
  { value: "7", label: "Last 7 days" },
  { value: "30", label: "Last 30 days" },
  { value: "90", label: "Last 90 days" },
  { value: "custom", label: "Custom range" },
];

/** Everything the page filters and pages by, in one object so one function can change any of it. */
export function initialHistoryState() {
  return { page: 0, status: "", preset: "all", from: "", to: "", bloodType: "", hospitalId: "" };
}

/** A filter change always goes back to page 0, since the old page no longer describes the results. */
export function applyHistoryFilterChange(state, changes) {
  return { ...state, ...changes, page: 0 };
}

/** Previous, Next and the clamp after a load change the page and nothing else. */
export function goToHistoryPage(state, page) {
  return { ...state, page };
}

/** "2026-11-04" for a Date, read in UTC (Sierra Leone's own time, and the server's). */
export function toIsoDate(date) {
  return date.toISOString().slice(0, 10);
}

/** A preset's closed-date range, both ends inclusive: "Last 7 days" is today and the six days before. */
export function presetRange(preset, today) {
  const days = Number(preset);
  if (!Number.isInteger(days) || days <= 0) return { from: "", to: "" };
  const start = new Date(today.getTime());
  start.setUTCDate(start.getUTCDate() - (days - 1));
  return { from: toIsoDate(start), to: toIsoDate(today) };
}

/** Why a custom range can't be used, or null; the server refuses the same backwards range with a 400 on from. */
export function customRangeProblem({ preset, from, to }) {
  if (preset !== "custom") return null;
  if (from && to && from > to) return "The start date must be on or before the end date.";
  return null;
}

/** The query for GET /api/requests/history; empty filters are left out, and hospitalId is sent only by a bank. */
export function buildHistoryParams(state, today, side) {
  const params = { page: state.page, size: HISTORY_PAGE_SIZE };
  if (state.status) params.status = state.status;
  if (state.bloodType) params.bloodType = state.bloodType;
  if (side === "bank" && state.hospitalId) params.hospitalId = state.hospitalId;
  const range = state.preset === "custom" ? { from: state.from, to: state.to } : presetRange(state.preset, today);
  if (range.from) params.from = range.from;
  if (range.to) params.to = range.to;
  return params;
}

/** Whether anything narrows the list, which decides the empty-state wording. */
export function hasActiveHistoryFilters(state) {
  return Boolean(state.status || state.bloodType || state.hospitalId)
    || state.preset !== "all";
}

/** "No finished requests yet." versus "No finished requests match these filters." */
export function describeHistoryEmptyState(totalElements, state) {
  if (totalElements > 0) return null;
  return hasActiveHistoryFilters(state)
    ? "No finished requests match these filters."
    : "No finished requests yet. Requests appear here once they are fulfilled, rejected or cancelled.";
}

/**
 * The outcome badge: "Fulfilled · 3 from us", "Cancelled · 2 of 5 supplied", "Rejected". The bank side adds what it
 * did; tone is the colour (navy fulfilled, grey cancelled, red rejected).
 */
export function outcomeBadge(entry, side) {
  const request = entry.request;
  if (request.status === "FULFILLED") {
    if (side !== "bank") return { text: "Fulfilled", tone: "fulfilled" };
    if (entry.unitsFromYourBank > 0) return { text: `Fulfilled · ${entry.unitsFromYourBank} from us`, tone: "fulfilled" };
    return { text: entry.declinedByYourBank ? "Fulfilled · we declined" : "Fulfilled", tone: "fulfilled" };
  }
  if (request.status === "CANCELLED") {
    const supplied = request.unitsContributed > 0
      ? `${request.unitsContributed} of ${request.unitsRequested} supplied`
      : "none supplied";
    return { text: `Cancelled · ${supplied}`, tone: "cancelled" };
  }
  return { text: "Rejected", tone: "rejected" };
}

/** "Makeni Regional Blood Bank (2), Holy Spirit Blood Bank (1)", each bank once with its total, or "None". */
export function suppliedBy(request) {
  const totals = new Map();
  request.contributions.forEach((c) => {
    totals.set(c.bloodBankName, (totals.get(c.bloodBankName) ?? 0) + c.unitsContributed);
  });
  if (totals.size === 0) return "None";
  return [...totals].map(([name, units]) => `${name} (${units})`).join(", ");
}

/** "4 units O+" / "1 unit AB-". */
export function unitsAndGroup(request) {
  return `${request.unitsRequested} unit${request.unitsRequested === 1 ? "" : "s"} ${request.bloodType}`;
}

/**
 * How the request ended, in a sentence for the details box. A bank is sent no cancellation note (Business Rule 35),
 * so cancelledReasonLine shows the reason alone there.
 */
export function closingExplanation(request) {
  if (request.status === "FULFILLED") {
    return `All ${request.unitsRequested} unit${request.unitsRequested === 1 ? " was" : "s were"} supplied.`;
  }
  if (request.status === "REJECTED") return "Every blood bank declined this request.";
  return cancelledReasonLine(request);
}
