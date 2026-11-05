import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getRequestHistory } from "../api/requests.js";
import { getHospitals } from "../api/hospitals.js";
import { formatDateTime, formatFullDate } from "../lib/dates.js";
import {
  canGoToPreviousPage, canGoToNextPage, clampPageToAvailable, describePage, describeShowingRange,
} from "../lib/pagination.js";
import {
  DATE_PRESETS, HISTORY_PAGE_SIZE, HISTORY_STATUSES, applyHistoryFilterChange, buildHistoryParams, closingExplanation,
  customRangeProblem, describeHistoryEmptyState, goToHistoryPage, initialHistoryState, outcomeBadge, suppliedBy,
  unitsAndGroup,
} from "../lib/requestHistory.js";

/* BloodLink — Request history (Business Rule 36): finished requests, newest closed first, 20 a page, with filters and
   a details box. Shared by the Blood Bank and Hospital portals; side is "bank" or "hospital". */

const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-"];

function titleCase(value) {
  return value.charAt(0) + value.slice(1).toLowerCase();
}

export default function RequestHistoryPanel({ side }) {
  const isBank = side === "bank";
  const [filters, setFilters] = useState(initialHistoryState());
  const [pageData, setPageData] = useState(null);
  const [error, setError] = useState(null);
  const [hospitals, setHospitals] = useState([]);
  const [selected, setSelected] = useState(null);
  const [today] = useState(() => new Date());

  const rangeProblem = customRangeProblem(filters);
  const closeDetails = useCallback(() => setSelected(null), []);

  // Banks filter by hospital, so they need the list; a failure only leaves that filter empty.
  useEffect(() => {
    if (!isBank) return undefined;
    let cancelled = false;
    getHospitals().then((list) => { if (!cancelled) setHospitals(list); }).catch(() => {});
    return () => { cancelled = true; };
  }, [isBank]);

  // One read per change of filters or page; an answer arriving after a newer request started is dropped.
  useEffect(() => {
    if (rangeProblem) return undefined;
    let cancelled = false;
    setError(null);
    getRequestHistory(buildHistoryParams(filters, today, side))
      .then((data) => { if (!cancelled) setPageData(data); })
      .catch((err) => { if (!cancelled) setError(err.message); });
    return () => { cancelled = true; };
  }, [filters, today, side, rangeProblem]);

  // A page number past the end (the list shrank) is pulled back to the last page.
  useEffect(() => {
    if (!pageData) return;
    const clamped = clampPageToAvailable(filters.page, pageData.totalPages);
    if (clamped !== filters.page) setFilters((current) => goToHistoryPage(current, clamped));
  }, [pageData, filters.page]);

  function update(changes) {
    setFilters((current) => applyHistoryFilterChange(current, changes));
  }

  const rows = pageData?.content ?? [];
  const totalElements = pageData?.totalElements ?? 0;
  const totalPages = pageData?.totalPages ?? 0;
  const emptyMessage = pageData ? describeHistoryEmptyState(totalElements, filters) : null;

  return (
    <div className="rh-wrap">
      <style>{`
        .rh-wrap { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; gap: clamp(9px, 1.4vh, 16px); }
        .rh-controls { flex: 0 0 auto; display: flex; align-items: flex-end; gap: clamp(8px, 1vw, 14px); flex-wrap: wrap; }
        .rh-field { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
        .rh-field-label { font-size: clamp(9px, 1.25vh, 11px); font-weight: 700; color: var(--ink-muted);
          text-transform: uppercase; letter-spacing: .05em; }
        .rh-select, .rh-date {
          font-family: inherit; font-size: clamp(10.5px, 1.5vh, 13px); font-weight: 600;
          padding: clamp(6px, 1vh, 9px) 10px; border: 1.5px solid var(--line); border-radius: 7px;
          background: #FFF; color: var(--ink); min-width: 0;
        }
        .rh-select:focus, .rh-date:focus { outline: none; border-color: var(--navy); }
        .rh-range-error { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: var(--red); }

        .rh-panel {
          flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column;
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(11px, 1.7vh, 18px) clamp(12px, 1.2vw, 20px);
        }
        .rh-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
        .rh-row {
          display: grid; align-items: center; gap: 4px clamp(8px, .9vw, 14px);
          grid-template-columns: minmax(78px, .7fr) minmax(0, 1.7fr) minmax(0, .9fr) minmax(0, 1.5fr) minmax(0, .8fr) minmax(0, .9fr);
          grid-template-areas: "ref who group outcome urgency closed";
          padding: clamp(6px, 1vh, 10px) 8px; border-bottom: 1px solid var(--line);
          font-size: clamp(10px, 1.48vh, 13px);
        }
        .rh-head { font-size: clamp(8.5px, 1.22vh, 11px); font-weight: 700; color: var(--ink-muted);
          text-transform: uppercase; letter-spacing: .05em; position: sticky; top: 0; background: var(--card-bg);
          border-bottom: 1.5px solid var(--line); z-index: 1; }
        button.rh-row {
          width: 100%; text-align: left; background: transparent; border: none; border-bottom: 1px solid var(--line);
          font-family: inherit; color: inherit; cursor: pointer;
        }
        button.rh-row:hover { background: #F6F7F8; }
        button.rh-row:focus-visible { outline: 2px solid var(--navy); outline-offset: -2px; }
        .rh-ref { grid-area: ref; font-weight: 700; color: var(--navy); }
        .rh-who { grid-area: who; overflow-wrap: anywhere; }
        .rh-group { grid-area: group; font-weight: 800; letter-spacing: -.01em; }
        .rh-outcome { grid-area: outcome; }
        .rh-urgency { grid-area: urgency; font-weight: 700; }
        .rh-closed { grid-area: closed; color: var(--ink-muted); }
        .rh-u-emergency { color: var(--red); }
        .rh-u-urgent { color: #A8690F; }
        .rh-u-routine { color: var(--green); }
        .rh-badge { display: inline-block; font-size: clamp(8.5px, 1.18vh, 11px); font-weight: 700; color: #FFF;
          padding: 3px clamp(7px, .7vw, 11px); border-radius: 20px; line-height: 1.35; }
        .rh-b-fulfilled { background: var(--navy); }
        .rh-b-cancelled { background: #5F6570; }
        .rh-b-rejected { background: var(--red); }
        .rh-empty { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); padding: clamp(10px, 1.6vh, 16px) 4px; }
        .rh-error { color: var(--red); font-weight: 600; font-size: clamp(11px, 1.6vh, 14px); padding: 12px 4px; }

        .rh-pager { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap;
          gap: clamp(8px, 1.2vw, 16px); margin-top: clamp(8px, 1.2vh, 14px); padding-top: clamp(7px, 1.1vh, 12px);
          border-top: 1px solid var(--line); }
        .rh-pager-count, .rh-pager-label { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: #3A3A3A; }
        .rh-pager-nav { display: flex; align-items: center; gap: clamp(7px, .9vw, 12px); }
        .rh-btn { background: transparent; border: 1.5px solid rgba(28,58,94,.35); color: var(--navy); border-radius: 6px;
          cursor: pointer; font-family: inherit; font-weight: 700; font-size: clamp(9px, 1.28vh, 11.5px);
          padding: clamp(4px, .7vh, 7px) clamp(8px, .8vw, 12px); }
        .rh-btn:hover { background: rgba(28,58,94,.07); }
        .rh-btn:disabled { border-color: var(--line); color: var(--ink-muted); cursor: not-allowed; background: transparent; }

        .rh-scrim { position: fixed; inset: 0; z-index: 40; background: rgba(17,17,17,.45);
          display: flex; align-items: center; justify-content: center; padding: 16px; }
        .rh-dialog { background: var(--card-bg); border-radius: var(--radius); box-shadow: 0 12px 40px rgba(0,0,0,.25);
          width: min(480px, 100%); max-height: calc(100dvh - 32px); overflow-y: auto;
          padding: clamp(16px, 2.6vh, 24px); display: flex; flex-direction: column; gap: 12px; color: var(--ink); }
        .rh-dialog-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
        .rh-dialog-title { font-size: clamp(15px, 2.2vh, 19px); font-weight: 800; letter-spacing: -.02em; }
        .rh-dialog-sub { font-size: clamp(10.5px, 1.5vh, 13px); color: var(--ink-muted); margin-top: 3px; }
        .rh-close { background: transparent; border: none; cursor: pointer; color: var(--ink-muted);
          font-size: 22px; line-height: 1; padding: 2px 6px; }
        .rh-close:focus-visible { outline: 2px solid var(--navy); }
        .rh-facts { display: grid; grid-template-columns: auto 1fr; gap: 6px 14px; font-size: clamp(10.5px, 1.5vh, 13px); margin: 0; }
        .rh-facts dt { font-weight: 700; color: #3A3A3A; }
        .rh-facts dd { margin: 0; overflow-wrap: anywhere; }
        .rh-section-title { font-size: clamp(10px, 1.4vh, 12px); font-weight: 700; color: var(--ink-muted);
          text-transform: uppercase; letter-spacing: .05em; }
        .rh-gift { display: flex; justify-content: space-between; gap: 10px; flex-wrap: wrap;
          padding: 7px 0; border-bottom: 1px solid var(--line); font-size: clamp(10.5px, 1.5vh, 13px); }
        .rh-gift:last-child { border-bottom: none; }
        .rh-gift-when { color: var(--ink-muted); }

        /* Phones: rows become small cards that wrap; filters stack one per line. */
        @media (max-width: 720px) {
          .rh-head { display: none; }
          .rh-row {
            grid-template-columns: minmax(0, 1fr) auto;
            grid-template-areas: "ref closed" "who who" "group urgency" "outcome outcome";
            padding: 10px 6px;
          }
          .rh-closed { text-align: right; }
          .rh-urgency { text-align: right; }
          .rh-controls { flex-direction: column; align-items: stretch; }
          .rh-field, .rh-select, .rh-date { width: 100%; }
          .rh-pager { justify-content: center; }
          .rh-btn { min-height: 36px; }
        }
      `}</style>

      <div className="rh-controls">
        <label className="rh-field">
          <span className="rh-field-label">Outcome</span>
          <select className="rh-select" value={filters.status} onChange={(e) => update({ status: e.target.value })}>
            <option value="">All outcomes</option>
            {HISTORY_STATUSES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
          </select>
        </label>
        <label className="rh-field">
          <span className="rh-field-label">Closed</span>
          <select className="rh-select" value={filters.preset} onChange={(e) => update({ preset: e.target.value })}>
            {DATE_PRESETS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
          </select>
        </label>
        {filters.preset === "custom" && (
          <>
            <label className="rh-field">
              <span className="rh-field-label">From</span>
              <input type="date" className="rh-date" value={filters.from} onChange={(e) => update({ from: e.target.value })} />
            </label>
            <label className="rh-field">
              <span className="rh-field-label">To</span>
              <input type="date" className="rh-date" value={filters.to} onChange={(e) => update({ to: e.target.value })} />
            </label>
          </>
        )}
        <label className="rh-field">
          <span className="rh-field-label">Blood group</span>
          <select className="rh-select" value={filters.bloodType} onChange={(e) => update({ bloodType: e.target.value })}>
            <option value="">All groups</option>
            {BLOOD_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}
          </select>
        </label>
        {isBank && (
          <label className="rh-field">
            <span className="rh-field-label">Hospital</span>
            <select className="rh-select" value={filters.hospitalId} onChange={(e) => update({ hospitalId: e.target.value })}>
              <option value="">All hospitals</option>
              {hospitals.map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}
            </select>
          </label>
        )}
      </div>
      {rangeProblem && <div className="rh-range-error" role="alert">{rangeProblem}</div>}

      <div className="rh-panel">
        <div className="rh-scroll">
          <div className="rh-row rh-head" aria-hidden="true">
            <span className="rh-ref">Reference</span>
            <span className="rh-who">{isBank ? "Hospital" : "Supplied by"}</span>
            <span className="rh-group">Request</span>
            <span className="rh-outcome">Outcome</span>
            <span className="rh-urgency">Urgency</span>
            <span className="rh-closed">Closed</span>
          </div>

          {error && <div className="rh-error" role="alert">{error}</div>}
          {!error && !pageData && <div className="rh-empty">Loading request history…</div>}
          {!error && emptyMessage && <div className="rh-empty">{emptyMessage}</div>}

          {!error && rows.map((entry) => {
            const r = entry.request;
            const badge = outcomeBadge(entry, side);
            return (
              <button
                type="button" className="rh-row" key={r.id} onClick={() => setSelected(entry)}
                aria-label={`Request #${r.reference}, ${unitsAndGroup(r)}, ${badge.text}. Open details.`}
              >
                <span className="rh-ref">#{r.reference}</span>
                <span className="rh-who">{isBank ? r.hospitalName : suppliedBy(r)}</span>
                <span className="rh-group">{unitsAndGroup(r)}</span>
                <span className="rh-outcome"><span className={`rh-badge rh-b-${badge.tone}`}>{badge.text}</span></span>
                <span className={`rh-urgency rh-u-${r.urgencyLevel.toLowerCase()}`}>{titleCase(r.urgencyLevel)}</span>
                <span className="rh-closed">{r.closedAt ? formatFullDate(r.closedAt.slice(0, 10)) : "—"}</span>
              </button>
            );
          })}
        </div>

        <div className="rh-pager">
          <span className="rh-pager-count">{describeShowingRange(filters.page, HISTORY_PAGE_SIZE, totalElements)}</span>
          <div className="rh-pager-nav">
            <button type="button" className="rh-btn" disabled={!canGoToPreviousPage(filters.page)}
                    onClick={() => setFilters((current) => goToHistoryPage(current, current.page - 1))}>
              Previous
            </button>
            <span className="rh-pager-label">{describePage(filters.page, totalPages)}</span>
            <button type="button" className="rh-btn" disabled={!canGoToNextPage(filters.page, totalPages)}
                    onClick={() => setFilters((current) => goToHistoryPage(current, current.page + 1))}>
              Next
            </button>
          </div>
        </div>
      </div>

      {selected && <RequestDetails entry={selected} side={side} onClose={closeDetails} />}
    </div>
  );
}

/**
 * One finished request in full: raised and closed times, each contribution, and how it ended. A bank is never sent
 * the cancellation note or the canceller's name (Business Rule 35), so it shows neither.
 */
function RequestDetails({ entry, side, onClose }) {
  const r = entry.request;
  const badge = useMemo(() => outcomeBadge(entry, side), [entry, side]);
  const closeRef = useRef(null);

  // Escape closes; focus starts on the close button and Tab stays inside the box.
  useEffect(() => {
    closeRef.current?.focus();
    const onKeyDown = (event) => {
      if (event.key === "Escape") onClose();
      if (event.key === "Tab") {
        event.preventDefault();
        closeRef.current?.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <div className="rh-scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="rh-dialog" role="dialog" aria-modal="true" aria-labelledby="rh-dialog-title">
        <div className="rh-dialog-head">
          <div>
            <div className="rh-dialog-title" id="rh-dialog-title">Request #{r.reference}</div>
            <div className="rh-dialog-sub">
              {unitsAndGroup(r)} · {titleCase(r.urgencyLevel)}{side === "bank" ? ` · ${r.hospitalName}` : ""}
            </div>
          </div>
          <button type="button" className="rh-close" ref={closeRef} onClick={onClose} aria-label="Close">×</button>
        </div>

        <div><span className={`rh-badge rh-b-${badge.tone}`}>{badge.text}</span></div>

        <dl className="rh-facts">
          <dt>Raised</dt><dd>{formatDateTime(r.createdAt)}</dd>
          <dt>Closed</dt><dd>{r.closedAt ? formatDateTime(r.closedAt) : "—"}</dd>
          <dt>Outcome</dt><dd>{closingExplanation(r)}</dd>
          {r.status === "CANCELLED" && r.cancelledByName && <><dt>Cancelled by</dt><dd>{r.cancelledByName}</dd></>}
          {side !== "bank" && r.patientReference && <><dt>Ward / reference</dt><dd>{r.patientReference}</dd></>}
          {side === "bank" && entry.declinedByYourBank && <><dt>Your bank</dt><dd>Declined this request</dd></>}
        </dl>

        <div className="rh-section-title">Units supplied ({r.unitsContributed} of {r.unitsRequested})</div>
        {r.contributions.length === 0 ? (
          <div className="rh-empty">No units were supplied.</div>
        ) : (
          <div>
            {r.contributions.map((c, index) => (
              <div className="rh-gift" key={index}>
                <span><strong>{c.bloodBankName}</strong> · {c.unitsContributed} unit{c.unitsContributed === 1 ? "" : "s"}</span>
                <span className="rh-gift-when">{formatDateTime(c.contributedAt)}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
