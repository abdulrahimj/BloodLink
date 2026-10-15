import React, { useCallback, useEffect, useState } from "react";
import {
  getStockReport, getRequestReport, getDonationReport, getDonorReport, downloadReportCsv,
} from "../api/admin.js";
import {
  defaultReportRange, validateReportRange, describeRange, formatRate, barPercent, maxCount,
  describeStockStatus, requestStatusLabel, urgencyLabel, isReportEmpty, countNoun, shareOf,
} from "../lib/reports.js";

/* ================================================================
   BloodLink — Admin Console › Reports (Business Rule 23)

   A read-only, citywide summary in four sections — Stock, Requests, Donations,
   Donors — each with its own "Download CSV". Distinct from the Audit Log, which
   records what administrators did; this page reports the state of the system.

   • Every figure is counted by the database (GET /api/admin/reports/summary/…);
     nothing here adds anything up. lib/reports.js only words and draws it.
   • Aggregates only. No donor's name, phone, email or address is fetched, so
     none can appear — on the page or in a CSV.
   • Each section loads on its own, with its own loading, empty and error state,
     so one slow or failed section never blanks the others.
   • The date range applies to Requests and Donations only; changing it refetches
     those two and leaves Stock and Donors (both "as of today") alone.
   • Plain CSS bars, no chart library: the page is a set of short ranked lists,
     which bars draw clearly and print well.

   Rendered inside AdminConsole's .ad-root, so it uses that file's tokens and its
   panel, table, badge and button classes; only what is new here is styled below,
   under an `ar-` prefix.
   ================================================================ */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

/**
 * Loads one section and keeps its own { status, data, error }. `load` is re-run
 * whenever it changes (the dated sections pass one that closes over the range), and
 * `reload` runs it again for a Try Again button. A response that arrives after a
 * newer request was started is dropped, so a quick second range never shows the
 * first range's figures.
 */
function useSection(load) {
  const [state, setState] = useState({ status: "loading", data: null, error: null });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setState((previous) => ({ ...previous, status: "loading", error: null }));
    load()
      .then((data) => { if (!cancelled) setState({ status: "ready", data, error: null }); })
      .catch((err) => { if (!cancelled) setState({ status: "error", data: null, error: err.message }); });
    return () => { cancelled = true; };
  }, [load, attempt]);

  return [state, () => setAttempt((n) => n + 1)];
}

/** Hands a downloaded Blob to the browser as a file. */
function saveFile({ blob, fileName }) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoked on the next tick: some browsers start the save asynchronously.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

export default function AdminReports() {
  // `range` is what the dated sections show; `draft` is what the inputs hold until
  // Apply, so typing a year digit by digit never fires a request per keystroke.
  const [range, setRange] = useState(() => defaultReportRange());
  const [draft, setDraft] = useState(range);
  const [rangeError, setRangeError] = useState(null);

  const loadStock = useCallback(() => getStockReport(), []);
  const loadDonors = useCallback(() => getDonorReport(), []);
  const loadRequests = useCallback(() => getRequestReport(range), [range]);
  const loadDonations = useCallback(() => getDonationReport(range), [range]);

  const [stock, reloadStock] = useSection(loadStock);
  const [requests, reloadRequests] = useSection(loadRequests);
  const [donations, reloadDonations] = useSection(loadDonations);
  const [donors, reloadDonors] = useSection(loadDonors);

  function applyRange(event) {
    event.preventDefault();
    const problem = validateReportRange(draft);
    setRangeError(problem);
    if (!problem && (draft.from !== range.from || draft.to !== range.to)) setRange(draft);
  }

  function resetRange() {
    const fresh = defaultReportRange();
    setDraft(fresh);
    setRangeError(null);
    setRange(fresh);
  }

  return (
    <div className="ar-scroll">
      <style>{`
        .ar-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: clamp(10px, 1.6vh, 18px); padding: 2px 4px 12px 2px; }
        /* Each section keeps its natural height and the page scrolls. .ad-panel is
           built to shrink into a fixed-height dashboard (min-height: 0), which here
           would squash every section into the window instead. */
        .ar-scroll > * { flex: 0 0 auto; }

        /* range bar */
        .ar-range { flex-direction: row !important; flex-wrap: wrap; align-items: flex-end; gap: clamp(10px, 1.2vw, 18px); }
        .ar-field { display: flex; flex-direction: column; gap: 4px; }
        .ar-field span { font-size: clamp(9px, 1.25vh, 11px); font-weight: 700; color: var(--ink-muted); text-transform: uppercase; letter-spacing: .05em; }
        .ar-field input {
          font-family: inherit; font-size: clamp(11px, 1.55vh, 13.5px); font-weight: 600; color: var(--ink);
          border: 1.5px solid var(--line); border-radius: 7px; padding: clamp(5px, .8vh, 8px) 10px; background: #FFF;
        }
        .ar-field input:focus { outline: none; border-color: var(--navy); }
        .ar-field input.is-invalid { border-color: var(--red); }
        .ar-range-note { font-size: clamp(9.5px, 1.35vh, 12px); color: var(--ink-muted); flex: 1 1 220px; line-height: 1.45; }
        .ar-range-error { flex-basis: 100%; font-size: clamp(9.5px, 1.35vh, 12px); color: var(--red); font-weight: 600; }

        /* section shell */
        .ar-section { gap: clamp(8px, 1.2vh, 14px); }
        .ar-section .ad-panel-head { margin-bottom: 0; align-items: center; flex-wrap: wrap; }
        .ar-heading { display: flex; flex-direction: column; gap: 2px; }
        .ar-sub { font-size: clamp(9.5px, 1.35vh, 12px); color: var(--ink-muted); }
        .ar-csv { display: inline-flex; align-items: center; gap: 6px; }
        .ar-csv svg { width: 13px; height: 13px; }
        .ar-csv-error { font-size: clamp(9px, 1.25vh, 11px); color: var(--red); font-weight: 600; }
        .ar-state { padding: clamp(14px, 2.4vh, 26px) 0; text-align: center; display: flex; flex-direction: column; align-items: center; gap: 8px; }

        /* headline figures */
        .ar-figures { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: clamp(8px, .9vw, 14px); }
        .ar-figure { border: 1px solid var(--line); border-radius: 8px; padding: clamp(8px, 1.2vh, 12px) clamp(10px, 1vw, 14px); border-left: 4px solid var(--navy); }
        .ar-figure.is-red { border-left-color: var(--red); }
        .ar-figure.is-green { border-left-color: var(--green); }
        .ar-figure.is-amber { border-left-color: var(--amber); }
        .ar-figure-value { font-size: clamp(17px, 2.7vh, 25px); font-weight: 800; letter-spacing: -.02em; line-height: 1.1; }
        .ar-figure-value.is-nodata { color: var(--ink-muted); font-size: clamp(14px, 2.1vh, 19px); }
        .ar-figure-label { font-size: clamp(10px, 1.45vh, 12.5px); font-weight: 700; margin-top: 3px; }
        .ar-figure-note { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); margin-top: 2px; line-height: 1.35; }

        /* blocks of bars / tables side by side */
        .ar-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(270px, 1fr)); gap: clamp(12px, 1.4vw, 24px); }
        .ar-block-title { font-size: clamp(10px, 1.42vh, 12.5px); font-weight: 700; color: #3A3A3A; margin-bottom: clamp(5px, .8vh, 9px); }

        /* horizontal bars */
        .ar-bars { display: flex; flex-direction: column; gap: clamp(4px, .7vh, 7px); }
        .ar-bar { display: grid; grid-template-columns: minmax(64px, 30%) 1fr auto; align-items: center; gap: 9px; font-size: clamp(10px, 1.42vh, 12.5px); }
        /* Bank names are long ("Makeni Regional Blood Bank"): their bars give the
           label more room, so the name is read whole rather than cut short. */
        .ar-bars--names .ar-bar { grid-template-columns: minmax(120px, 52%) 1fr auto; }
        .ar-bar-label { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .ar-bar-track { height: clamp(9px, 1.35vh, 12px); background: #F1F2F4; border-radius: 4px; overflow: hidden; }
        .ar-bar-fill { height: 100%; background: var(--navy); border-radius: 4px; min-width: 0; }
        .ar-bar-fill.is-critical { background: var(--red); }
        .ar-bar-fill.is-low { background: var(--amber); }
        .ar-bar-fill.is-sufficient { background: var(--green); }
        .ar-bar-fill.is-red { background: var(--red); }
        .ar-bar-fill.is-amber { background: var(--amber); }
        .ar-bar-fill.is-green { background: var(--green); }
        .ar-bar-value { font-weight: 700; min-width: 2.5em; text-align: right; display: flex; align-items: center; gap: 7px; justify-content: flex-end; }

        /* status badges, toned like the inventory pages */
        .ar-badge { font-size: clamp(8px, 1.12vh, 10px); font-weight: 700; padding: 2px 8px; border-radius: 20px; color: #FFF; min-width: 64px; text-align: center; }
        .ar-badge.is-critical { background: var(--red); }
        .ar-badge.is-low { background: var(--amber); }
        .ar-badge.is-sufficient { background: var(--green); }

        /* month columns */
        .ar-months { display: flex; align-items: flex-end; gap: clamp(4px, .6vw, 10px); height: clamp(120px, 20vh, 180px); padding-top: 16px; border-bottom: 1.5px solid var(--line); overflow-x: auto; }
        .ar-month { flex: 1 1 0; min-width: 34px; height: 100%; display: flex; flex-direction: column; justify-content: flex-end; align-items: center; gap: 4px; }
        .ar-month-count { font-size: clamp(9px, 1.25vh, 11px); font-weight: 700; }
        .ar-month-col { width: min(70%, 42px); background: var(--red); border-radius: 4px 4px 0 0; }
        .ar-month-labels { display: flex; gap: clamp(4px, .6vw, 10px); margin-top: 5px; }
        .ar-month-labels span { flex: 1 1 0; min-width: 34px; text-align: center; font-size: clamp(8.5px, 1.2vh, 10.5px); color: var(--ink-muted); font-weight: 600; white-space: nowrap; }

        /* eligible / recovery split */
        .ar-split { display: flex; height: clamp(14px, 2vh, 18px); border-radius: 6px; overflow: hidden; background: #F1F2F4; }
        .ar-split > div { height: 100%; }
        .ar-split-legend { display: flex; flex-wrap: wrap; gap: clamp(10px, 1.2vw, 18px); margin-top: 7px; font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; }
        .ar-split-legend i { display: inline-block; width: 9px; height: 9px; border-radius: 2px; margin-right: 6px; vertical-align: -1px; }

        .ar-table td.ar-num, .ar-table th.ar-num { text-align: right; }
        .ar-table td { white-space: nowrap; }
        .ar-zero { color: var(--ink-muted); }
      `}</style>

      {/* ---------- date range ---------- */}
      <form className="ad-panel ar-range" onSubmit={applyRange} noValidate>
        <label className="ar-field">
          <span>From</span>
          <input
            type="date" value={draft.from} max={draft.to || undefined}
            className={rangeError?.field === "from" ? "is-invalid" : ""}
            onChange={(e) => setDraft({ ...draft, from: e.target.value })}
            aria-invalid={rangeError?.field === "from"}
          />
        </label>
        <label className="ar-field">
          <span>To</span>
          <input
            type="date" value={draft.to} min={draft.from || undefined}
            className={rangeError?.field === "to" ? "is-invalid" : ""}
            onChange={(e) => setDraft({ ...draft, to: e.target.value })}
            aria-invalid={rangeError?.field === "to"}
          />
        </label>
        <button type="submit" className="ad-new">Apply</button>
        <button type="button" className="ad-act ad-act--edit" onClick={resetRange}>Last 6 months</button>
        <div className="ar-range-note">
          The dates apply to <strong>Requests</strong> and <strong>Donations</strong>.
          Stock and Donors always show today. Dates are in UTC (Sierra Leone time).
        </div>
        {rangeError && <div className="ar-range-error" role="alert">{rangeError.message}</div>}
      </form>

      <Section
        section="stock" title="Stock overview" sub="All blood banks, as of today"
        state={stock} onRetry={reloadStock} emptyText="No blood banks yet."
      >
        {(data) => <StockBody data={data} />}
      </Section>

      <Section
        section="requests" title="Hospital requests" range={range}
        sub={`Raised between ${describeRange(range)}`}
        state={requests} onRetry={reloadRequests} emptyText="No data for this period."
      >
        {(data) => <RequestsBody data={data} />}
      </Section>

      <Section
        section="donations" title="Donations" range={range}
        sub={`Recorded between ${describeRange(range)}`}
        state={donations} onRetry={reloadDonations} emptyText="No data for this period."
      >
        {(data) => <DonationsBody data={data} />}
      </Section>

      <Section
        section="donors" title="Donors" sub="Registered donors and who can donate today — counts only, no personal details"
        state={donors} onRetry={reloadDonors} emptyText="No donors registered yet."
      >
        {(data) => <DonorsBody data={data} />}
      </Section>
    </div>
  );
}

/**
 * One report section: heading, its Download CSV button, and whichever of loading /
 * error / empty / content applies. The CSV is fetched from the server, for the
 * same range the section is showing, so the file always matches the page.
 */
function Section({ section, title, sub, range, state, onRetry, emptyText, children }) {
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState(null);

  async function download() {
    setDownloading(true);
    setDownloadError(null);
    try {
      saveFile(await downloadReportCsv(section, range));
    } catch (err) {
      setDownloadError(err.message);
    } finally {
      setDownloading(false);
    }
  }

  return (
    <section className="ad-panel ar-section" aria-labelledby={`ar-${section}`}>
      <div className="ad-panel-head">
        <div className="ar-heading">
          <div className="ad-panel-title" id={`ar-${section}`}>{title}</div>
          <div className="ar-sub">{sub}</div>
        </div>
        <div className="ad-panel-head-right">
          {downloadError && <span className="ar-csv-error" role="alert">{downloadError}</span>}
          <button
            type="button" className="ad-act ad-act--edit ar-csv"
            onClick={download} disabled={downloading || state.status === "loading"}
          >
            <svg viewBox="0 0 24 24" {...ICON} aria-hidden="true"><path d="M12 3v12M7 10l5 5 5-5M5 21h14" /></svg>
            {downloading ? "Preparing…" : "Download CSV"}
          </button>
        </div>
      </div>

      {state.status === "loading" && <div className="ar-state ad-sub">Loading…</div>}
      {state.status === "error" && (
        <div className="ar-state">
          <div className="ad-error-msg">{state.error}</div>
          <button type="button" className="ad-retry" onClick={onRetry}>Try again</button>
        </div>
      )}
      {state.status === "ready" && isReportEmpty(section, state.data) && (
        <div className="ar-state ad-empty">{emptyText}</div>
      )}
      {state.status === "ready" && !isReportEmpty(section, state.data) && children(state.data)}
    </section>
  );
}

/** A headline number with a label and an optional note; `tone` colours its edge. */
function Figure({ value, label, note, tone = "", noData = false }) {
  return (
    <div className={`ar-figure ${tone ? `is-${tone}` : ""}`}>
      <div className={`ar-figure-value ${noData ? "is-nodata" : ""}`}>{value}</div>
      <div className="ar-figure-label">{label}</div>
      {note && <div className="ar-figure-note">{note}</div>}
    </div>
  );
}

/** A ranked list of labelled horizontal bars, all scaled against the largest.
 *  `names` widens the label column for long labels such as bank names. */
function Bars({ rows, label = (r) => r.label, value = (r) => r.count, tone = () => "", extra, names = false }) {
  const max = rows.reduce((m, r) => Math.max(m, value(r)), 0);
  return (
    <div className={`ar-bars ${names ? "ar-bars--names" : ""}`}>
      {rows.map((row) => (
        <div className="ar-bar" key={label(row)}>
          <span className="ar-bar-label" title={label(row)}>{label(row)}</span>
          <div className="ar-bar-track">
            <div className={`ar-bar-fill ${tone(row) ? `is-${tone(row)}` : ""}`} style={{ width: `${barPercent(value(row), max)}%` }} />
          </div>
          <span className={`ar-bar-value ${value(row) === 0 ? "ar-zero" : ""}`}>
            {value(row).toLocaleString("en-GB")}
            {extra?.(row)}
          </span>
        </div>
      ))}
    </div>
  );
}

// ---------- section bodies ----------

function StockBody({ data }) {
  const criticalGroups = data.groups.filter((g) => g.status === "CRITICAL").length;
  return (
    <>
      <div className="ar-figures">
        <Figure value={data.totalUnits.toLocaleString("en-GB")} label="Total units" note="Every blood bank, every group" />
        <Figure
          value={data.expiringSoonUnits.toLocaleString("en-GB")} tone="amber"
          label={`Expiring within ${data.expiringSoonWindowDays} days`}
          note={data.pastExpiryUnits > 0
            ? `Includes ${countNoun(data.pastExpiryUnits, "unit")} already past expiry`
            : "None already past expiry"}
        />
        <Figure
          value={criticalGroups} tone={criticalGroups > 0 ? "red" : "green"}
          label="Critical groups citywide" note="Under 15 units across all banks"
        />
      </div>

      <div className="ar-grid">
        <div>
          <div className="ar-block-title">Units per blood group, all banks</div>
          <Bars
            rows={data.groups} label={(g) => g.bloodType} value={(g) => g.units}
            tone={(g) => describeStockStatus(g.status).tone}
            extra={(g) => {
              const status = describeStockStatus(g.status);
              return <span className={`ar-badge is-${status.tone}`}>{status.label}</span>;
            }}
          />
        </div>
        <div>
          <div className="ar-block-title">Per blood bank</div>
          <div className="ad-table-scroll">
            <table className="ad-table ar-table">
              <thead>
                <tr>
                  <th>Blood bank</th>
                  <th className="ar-num">Total units</th>
                  <th className="ar-num">Critical groups</th>
                  <th className="ar-num">Low groups</th>
                </tr>
              </thead>
              <tbody>
                {data.banks.map((bank) => (
                  <tr key={bank.bankName}>
                    <td title={bank.bankName}><strong>{bank.bankName}</strong></td>
                    <td className="ar-num">{bank.totalUnits.toLocaleString("en-GB")}</td>
                    <td className={`ar-num ${bank.criticalGroups === 0 ? "ar-zero" : ""}`}>{bank.criticalGroups} of 8</td>
                    <td className={`ar-num ${bank.lowGroups === 0 ? "ar-zero" : ""}`}>{bank.lowGroups} of 8</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </>
  );
}

function RequestsBody({ data }) {
  const statusTone = { PENDING: "amber", PARTIALLY_FULFILLED: "", FULFILLED: "green", REJECTED: "red" };
  const urgencyTone = { ROUTINE: "", URGENT: "amber", EMERGENCY: "red" };
  return (
    <>
      <div className="ar-figures">
        <Figure value={data.total.toLocaleString("en-GB")} label="Requests raised" />
        <Figure
          value={formatRate(data.fulfilmentRate)} noData={data.fulfilmentRate == null} tone="green"
          label="Fulfilment rate"
          note={data.decided === 0
            ? "None fulfilled or rejected yet in this period"
            : `Fulfilled out of ${countNoun(data.decided, "decided request")}`}
        />
        <Figure
          value={data.filledByMoreThanOneBank.toLocaleString("en-GB")}
          label="Filled by more than one bank" note="Received units from two or more blood banks"
        />
      </div>
      <div className="ar-grid">
        <div>
          <div className="ar-block-title">By status</div>
          <Bars rows={data.byStatus} label={(r) => requestStatusLabel(r.label)} tone={(r) => statusTone[r.label]} />
        </div>
        <div>
          <div className="ar-block-title">By urgency</div>
          <Bars rows={data.byUrgency} label={(r) => urgencyLabel(r.label)} tone={(r) => urgencyTone[r.label]} />
        </div>
        <div>
          <div className="ar-block-title">By blood group</div>
          <Bars rows={data.byBloodType} tone={() => "red"} />
        </div>
      </div>
    </>
  );
}

function DonationsBody({ data }) {
  const monthMax = maxCount(data.byMonth);
  return (
    <>
      <div className="ar-figures">
        <Figure value={data.total.toLocaleString("en-GB")} label="Donations recorded" tone="red" />
      </div>
      <div>
        <div className="ar-block-title">Per month (UTC)</div>
        <div className="ar-months">
          {data.byMonth.map((m) => (
            <div className="ar-month" key={m.month}>
              <span className={`ar-month-count ${m.count === 0 ? "ar-zero" : ""}`}>{m.count}</span>
              <div className="ar-month-col" style={{ height: `${barPercent(m.count, monthMax)}%` }} />
            </div>
          ))}
        </div>
        <div className="ar-month-labels">
          {data.byMonth.map((m) => <span key={m.month}>{m.label}</span>)}
        </div>
      </div>
      <div className="ar-grid">
        <div>
          <div className="ar-block-title">Per blood bank</div>
          <Bars rows={data.byBank} names />
        </div>
        <div>
          <div className="ar-block-title">Per blood group (as tested)</div>
          <Bars rows={data.byBloodType} tone={() => "red"} />
        </div>
      </div>
    </>
  );
}

function DonorsBody({ data }) {
  return (
    <>
      <div className="ar-figures">
        <Figure value={data.total.toLocaleString("en-GB")} label="Registered donors" />
        <Figure
          value={data.eligible.toLocaleString("en-GB")} tone="green" label="Eligible to donate now"
          note={`${shareOf(data.eligible, data.total)} of donors`}
        />
        <Figure
          value={data.inRecovery.toLocaleString("en-GB")} tone="amber" label="In 90-day recovery"
          note={`${shareOf(data.inRecovery, data.total)} of donors`}
        />
      </div>
      <div className="ar-grid">
        <div>
          <div className="ar-block-title">Eligible now vs in recovery</div>
          <div className="ar-split" role="img" aria-label={`${data.eligible} eligible, ${data.inRecovery} in recovery`}>
            <div style={{ width: `${barPercent(data.eligible, data.total)}%`, background: "var(--green)" }} />
            <div style={{ width: `${barPercent(data.inRecovery, data.total)}%`, background: "var(--amber)" }} />
          </div>
          <div className="ar-split-legend">
            <span><i style={{ background: "var(--green)" }} />Eligible · {data.eligible}</span>
            <span><i style={{ background: "var(--amber)" }} />In recovery · {data.inRecovery}</span>
          </div>
        </div>
        <div>
          <div className="ar-block-title">Donors by blood group</div>
          <Bars rows={data.byBloodType.filter((r) => r.label !== "Unknown" || r.count > 0)} tone={() => "red"} />
        </div>
      </div>
    </>
  );
}
