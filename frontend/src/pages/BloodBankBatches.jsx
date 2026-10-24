import React, { useCallback, useEffect, useRef, useState } from "react";
import { getBatches, getBatchHistory, writeOffBatch } from "../api/inventory.js";
import { formatDateTime, formatFullDate } from "../lib/dates.js";
import {
  NOTE_MAX_LENGTH, WRITE_OFF_REASONS, batchSourceLabel, daysUntilExpiryText, describeBatchState, expiredWarning,
  needsWriteOff, unitsText, validateWriteOff, writeOffDoneText, writeOffPayload, writeOffReasonLabel,
} from "../lib/batchStock.js";

/* BloodLink — one blood group's batches (Business Rule 24), opened from the Inventory table: the list,
   one batch's history, and the write-off form. Rendered inside .bi-root, so it uses that page's tokens. */

/**
 * The dialog. canChangeStock shows the write-off form (bank staff only, Business Rule 8);
 * refreshKey changes when the page re-reads stock, so the list re-reads too.
 */
export default function BatchDialog({ bloodBankId, group, canChangeStock, refreshKey, onClose, onStockChanged }) {
  const { bloodType } = group;
  const [batches, setBatches] = useState(null);
  const [listError, setListError] = useState(null);
  const [view, setView] = useState({ name: "list" });
  const [notice, setNotice] = useState(null);
  const [busy, setBusy] = useState(false);
  const closeRef = useRef(null);

  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  const loadBatches = useCallback(async () => {
    try {
      const list = await getBatches(bloodBankId, bloodType);
      if (!mountedRef.current) return;
      setBatches(list);
      setListError(null);
    } catch (err) {
      if (mountedRef.current) setListError(err.message);
    }
  }, [bloodBankId, bloodType]);

  useEffect(() => { loadBatches(); }, [loadBatches, refreshKey]);

  // Escape steps back to the list first, then closes; never while a write-off is being sent.
  useEffect(() => {
    closeRef.current?.focus();
    const onKeyDown = (event) => {
      if (event.key !== "Escape" || busy) return;
      if (view.name === "list") onClose();
      else setView({ name: "list" });
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose, busy, view.name]);

  function open(name, batchId) {
    setNotice(null);
    setView({ name, batchId });
  }

  /** After a write-off: back to the list with a receipt, and both the list and the page totals re-read. */
  function handleWrittenOff(response) {
    setNotice(writeOffDoneText(response.writeOff.units, response.batch.unitsRemaining));
    setView({ name: "list" });
    loadBatches();
    onStockChanged();
  }

  const selected = batches?.find((b) => b.id === view.batchId) ?? null;
  const toWriteOff = (batches ?? []).filter(needsWriteOff);

  return (
    <div className="bi-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget && !busy) onClose(); }}>
      <style>{BATCH_CSS}</style>
      <div className="bi-modal bt-modal" role="dialog" aria-modal="true" aria-labelledby="bt-title">
        <div className="bt-head">
          <div>
            <div className="bi-modal-title" id="bt-title">{bloodType} batches</div>
            <div className="bt-sub">
              {unitsText(group.unitsAvailable)} usable · {group.expiringSoonUnits} expiring soon ·{" "}
              <span className={group.expiredUnits > 0 ? "bt-red" : ""}>{group.expiredUnits} expired</span>
            </div>
          </div>
          <button type="button" className="bt-close" ref={closeRef} onClick={onClose} disabled={busy}
                  aria-label="Close">Close</button>
        </div>

        {view.name === "list" && (
          <>
            {notice && (
              <div className="bi-notice" role="status">
                {notice}
                <button type="button" onClick={() => setNotice(null)}>Dismiss</button>
              </div>
            )}
            {listError && (
              <div className="bi-modal-alert" role="alert">
                {listError} <button type="button" className="bt-link" onClick={loadBatches}>Try again</button>
              </div>
            )}
            {!listError && batches === null && <div className="bt-empty">Loading batches…</div>}
            {batches !== null && batches.length === 0 && (
              <div className="bt-empty">No {bloodType} batches with units left.</div>
            )}
            {toWriteOff.length > 0 && (
              <div className="bt-warn" role="note">
                {expiredWarning(toWriteOff.length, canChangeStock)}
              </div>
            )}
            <div className="bt-list">
              {(batches ?? []).map((batch) => (
                <BatchCard
                  key={batch.id} batch={batch} canChangeStock={canChangeStock}
                  onHistory={() => open("history", batch.id)} onWriteOff={() => open("writeoff", batch.id)}
                />
              ))}
            </div>
          </>
        )}

        {view.name === "history" && (
          <BatchHistory
            bloodBankId={bloodBankId} batchId={view.batchId} canChangeStock={canChangeStock}
            onBack={() => setView({ name: "list" })} onWriteOff={() => open("writeoff", view.batchId)}
          />
        )}

        {view.name === "writeoff" && (
          selected && selected.unitsRemaining > 0 ? (
            <WriteOffForm
              bloodBankId={bloodBankId} batch={selected} onBusy={setBusy}
              onBack={() => setView({ name: "list" })} onDone={handleWrittenOff}
              onStale={() => { loadBatches(); onStockChanged(); }}
            />
          ) : (
            <>
              <div className="bt-empty">This batch has no units left to write off.</div>
              <button type="button" className="bi-modal-cancel" onClick={() => setView({ name: "list" })}>
                Back to batches
              </button>
            </>
          )
        )}
      </div>
    </div>
  );
}

/** One batch in the list: units, expiry, state and source, with its actions. Expired with units = red. */
function BatchCard({ batch, canChangeStock, onHistory, onWriteOff }) {
  const state = describeBatchState(batch.state);
  return (
    <div className={`bt-batch${needsWriteOff(batch) ? " is-expired" : ""}`}>
      <div>
        <div className="bt-big">{unitsText(batch.unitsRemaining)} left</div>
        <div className="bt-muted">of {batch.unitsReceived} received</div>
      </div>
      <div>
        <div className="bt-strong">Expiry {formatFullDate(batch.expiryDate)}</div>
        <div className={batch.state === "EXPIRED" ? "bt-red" : "bt-muted"}>{daysUntilExpiryText(batch.daysUntilExpiry)}</div>
      </div>
      <div>
        <span className={`bt-badge is-${state.tone}`}>{state.label}</span>
        <div className="bt-muted bt-source">{batchSourceLabel(batch.source)}</div>
      </div>
      <div className="bt-actions">
        <button type="button" className="bt-btn" onClick={onHistory}>History</button>
        {canChangeStock && (
          <button type="button" className="bt-btn is-red" onClick={onWriteOff}>Write off</button>
        )}
      </div>
    </div>
  );
}

/** Where one batch's units went: issued to hospital requests, and written off. */
function BatchHistory({ bloodBankId, batchId, canChangeStock, onBack, onWriteOff }) {
  const [history, setHistory] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    getBatchHistory(bloodBankId, batchId)
      .then((data) => { if (!cancelled) setHistory(data); })
      .catch((err) => { if (!cancelled) setError(err.message); });
    return () => { cancelled = true; };
  }, [bloodBankId, batchId]);

  const back = <button type="button" className="bt-link bt-back" onClick={onBack}>← Back to batches</button>;
  if (error) return <>{back}<div className="bi-modal-alert" role="alert">{error}</div></>;
  if (!history) return <>{back}<div className="bt-empty">Loading history…</div></>;

  const { batch, issues, writeOffs } = history;
  const state = describeBatchState(batch.state);
  return (
    <>
      {back}
      <div className="bt-facts">
        <Fact label="Received">{unitsText(batch.unitsReceived)} on {formatDateTime(batch.receivedAt)}</Fact>
        <Fact label="Left now">{unitsText(batch.unitsRemaining)}</Fact>
        <Fact label="Expiry">
          {formatFullDate(batch.expiryDate)} <span className="bt-muted">({daysUntilExpiryText(batch.daysUntilExpiry)})</span>
        </Fact>
        <Fact label="State"><span className={`bt-badge is-${state.tone}`}>{state.label}</span></Fact>
        <Fact label="Source">{batchSourceLabel(batch.source)}</Fact>
        <Fact label="Issued / written off">
          {unitsText(history.unitsIssued)} issued · {unitsText(history.unitsWrittenOff)} written off
        </Fact>
      </div>

      <div className="bt-section">Issued to hospitals</div>
      {issues.length === 0 ? <div className="bt-muted">None of this batch has been issued.</div> : (
        <ul className="bt-events">
          {issues.map((issue) => (
            <li key={issue.contributionId}>
              <strong>{unitsText(issue.units)}</strong> to {issue.hospitalName}
              <div className="bt-muted">{formatDateTime(issue.contributedAt)}</div>
            </li>
          ))}
        </ul>
      )}

      <div className="bt-section">Written off</div>
      {writeOffs.length === 0 ? <div className="bt-muted">Nothing has been written off from this batch.</div> : (
        <ul className="bt-events">
          {writeOffs.map((w) => (
            <li key={w.id}>
              <strong>{unitsText(w.units)}</strong> · {writeOffReasonLabel(w.reason)}
              {w.note && <div className="bt-note">“{w.note}”</div>}
              <div className="bt-muted">By {w.writtenOffByName} · {formatDateTime(w.writtenOffAt)}</div>
            </li>
          ))}
        </ul>
      )}

      {canChangeStock && batch.unitsRemaining > 0 && (
        <div className="bi-modal-actions">
          <button type="button" className="bi-modal-submit" onClick={onWriteOff}>Write off units</button>
        </div>
      )}
    </>
  );
}

function Fact({ label, children }) {
  return (
    <div className="bt-fact">
      <div className="bt-fact-label">{label}</div>
      <div>{children}</div>
    </div>
  );
}

/**
 * Units, reason and note, then a confirmation step, since a write-off cannot be undone.
 * An expired batch starts filled in (all its units, reason Expired); 400s land under their field.
 */
function WriteOffForm({ bloodBankId, batch, onBusy, onBack, onDone, onStale }) {
  const expired = batch.state === "EXPIRED";
  const [form, setForm] = useState({
    units: expired ? String(batch.unitsRemaining) : "", reason: expired ? "EXPIRED" : "", note: "",
  });
  const [step, setStep] = useState("form");
  const [fieldErrors, setFieldErrors] = useState({});
  const [alert, setAlert] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const set = (field) => (event) => setForm((current) => ({ ...current, [field]: event.target.value }));
  const reason = WRITE_OFF_REASONS.find((r) => r.value === form.reason);
  const noteLength = form.note.trim().length;

  function handleContinue(event) {
    event.preventDefault();
    const errors = validateWriteOff(form, batch.unitsRemaining);
    setFieldErrors(errors);
    setAlert(null);
    if (Object.keys(errors).length === 0) setStep("confirm");
  }

  async function handleConfirm() {
    setSubmitting(true);
    onBusy(true);
    try {
      const response = await writeOffBatch(bloodBankId, batch.id, writeOffPayload(form));
      onDone(response);
    } catch (err) {
      setStep("form");
      const errors = err.fieldErrors ?? {};
      setFieldErrors(errors);
      // A 409 means the batch changed meanwhile (e.g. "only 4 units left"), so re-read it.
      setAlert(Object.keys(errors).length > 0 ? null : err.message);
      if (err.status === 409) onStale();
    } finally {
      setSubmitting(false);
      onBusy(false);
    }
  }

  const batchLine = `${batch.bloodType} batch, expiry ${formatFullDate(batch.expiryDate)} · ${unitsText(batch.unitsRemaining)} left`;

  if (step === "confirm") {
    const units = Number(form.units);
    return (
      <>
        <div className="bt-section">Write off {unitsText(units)} of {batch.bloodType}?</div>
        <div className="bi-confirm-fact">
          <div className="bi-confirm-count">{batchLine}</div>
          <div className="bi-confirm-note">
            After this: {unitsText(batch.unitsRemaining - units)} left. Reason: {reason?.label}.
            {form.note.trim() && <> Note: “{form.note.trim()}”</>}
          </div>
        </div>
        <div className="bt-warn" role="note">
          This cannot be undone. The units leave stock for good, and the write-off is recorded under your name.
        </div>
        <div className="bi-modal-actions">
          <button type="button" className="bi-modal-cancel" onClick={() => setStep("form")} disabled={submitting}>
            Go back
          </button>
          <button type="button" className="bi-modal-submit" onClick={handleConfirm} disabled={submitting}>
            {submitting ? "Writing off…" : "Yes, write off"}
          </button>
        </div>
      </>
    );
  }

  return (
    <form onSubmit={handleContinue} noValidate>
      <button type="button" className="bt-link bt-back" onClick={onBack}>← Back to batches</button>
      <div className="bt-section">Write off units</div>
      <div className="bi-confirm-fact"><div className="bi-confirm-count">{batchLine}</div></div>

      {alert && <div className="bi-modal-alert" role="alert">{alert}</div>}

      <div className="bi-field">
        <label htmlFor="wo-units">Units to write off (1 to {batch.unitsRemaining})</label>
        <input id="wo-units" type="number" min="1" max={batch.unitsRemaining} step="1"
               value={form.units} onChange={set("units")} aria-invalid={Boolean(fieldErrors.units)} />
        {fieldErrors.units && <span className="bi-field-err">{fieldErrors.units}</span>}
      </div>

      <div className="bi-field">
        <label htmlFor="wo-reason">Reason</label>
        <select id="wo-reason" value={form.reason} onChange={set("reason")} aria-invalid={Boolean(fieldErrors.reason)}>
          <option value="">Choose a reason…</option>
          {WRITE_OFF_REASONS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
        </select>
        {reason && !fieldErrors.reason && <span className="bt-muted">{reason.hint}</span>}
        {fieldErrors.reason && <span className="bi-field-err">{fieldErrors.reason}</span>}
      </div>

      <div className="bi-field">
        <label htmlFor="wo-note">Note {form.reason === "OTHER" ? "(required for Other)" : "(optional)"}</label>
        <textarea id="wo-note" className="bt-textarea" rows={3} maxLength={NOTE_MAX_LENGTH}
                  value={form.note} onChange={set("note")} aria-invalid={Boolean(fieldErrors.note)} />
        <span className={`bt-counter${noteLength > NOTE_MAX_LENGTH ? " bt-red" : ""}`}>
          {noteLength} / {NOTE_MAX_LENGTH}
        </span>
        {fieldErrors.note && <span className="bi-field-err">{fieldErrors.note}</span>}
      </div>

      <div className="bi-modal-actions">
        <button type="button" className="bi-modal-cancel" onClick={onBack}>Cancel</button>
        <button type="submit" className="bi-modal-submit">Continue</button>
      </div>
    </form>
  );
}

const BATCH_CSS = `
  .bi-modal.bt-modal { width: min(760px, calc(100vw - 32px)); max-height: calc(100vh - 48px); overflow-y: auto; }
  .bt-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: clamp(10px, 1.7vh, 16px); }
  .bt-sub { font-size: clamp(10.5px, 1.5vh, 13px); color: var(--ink-muted); }
  .bt-close {
    flex: 0 0 auto; background: #FFF; border: 1.5px solid var(--line); border-radius: 8px; cursor: pointer;
    font-family: inherit; font-weight: 700; font-size: clamp(10.5px, 1.5vh, 13px); color: #3D3D3D; padding: 7px 12px;
  }
  .bt-close:focus-visible, .bt-btn:focus-visible, .bt-link:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }
  .bt-list { display: flex; flex-direction: column; gap: 8px; }
  .bt-batch {
    display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr) minmax(0, 1.2fr) auto;
    align-items: center; gap: 12px; padding: clamp(9px, 1.4vh, 12px) 12px;
    border: 1.5px solid var(--line); border-radius: 8px; font-size: clamp(10.5px, 1.5vh, 13px);
  }
  .bt-batch.is-expired { border-color: rgba(200,16,46,.4); border-left: 5px solid var(--red); background: rgba(200,16,46,.05); }
  .bt-big { font-size: clamp(12.5px, 1.85vh, 15px); font-weight: 800; letter-spacing: -.01em; }
  .bt-strong { font-weight: 700; }
  .bt-muted { color: var(--ink-muted); font-size: clamp(9.5px, 1.35vh, 12px); }
  .bt-red { color: var(--red); font-weight: 700; }
  .bt-source { margin-top: 4px; }
  .bt-badge { display: inline-block; font-size: clamp(8.5px, 1.18vh, 11px); font-weight: 700; padding: 3px 10px; border-radius: 20px; color: #FFF; }
  .bt-badge.is-green { background: var(--green); } .bt-badge.is-amber { background: var(--amber); } .bt-badge.is-red { background: var(--red); }
  .bt-actions { display: flex; gap: 6px; justify-content: flex-end; }
  .bt-btn {
    background: transparent; color: var(--navy); border: 1.5px solid rgba(28,58,94,.35); border-radius: 6px;
    cursor: pointer; font-family: inherit; font-weight: 700; font-size: clamp(9.5px, 1.32vh, 12px);
    padding: clamp(5px, .8vh, 7px) 10px; white-space: nowrap;
  }
  .bt-btn:hover { background: rgba(28,58,94,.06); }
  .bt-btn.is-red { color: var(--red); border-color: rgba(200,16,46,.35); }
  .bt-btn.is-red:hover { background: rgba(200,16,46,.07); }
  .bt-warn {
    font-size: clamp(10px, 1.42vh, 12.5px); font-weight: 600; line-height: 1.45; color: #8E0B21;
    background: rgba(200,16,46,.07); border-left: 3px solid var(--red); border-radius: 6px;
    padding: clamp(7px, 1.1vh, 10px) 12px; margin-bottom: clamp(9px, 1.5vh, 14px);
  }
  .bt-empty { color: var(--ink-muted); font-size: clamp(10.5px, 1.5vh, 13px); padding: 12px 0; }
  .bt-link {
    background: none; border: none; padding: 0; cursor: pointer; font-family: inherit;
    font-weight: 700; font-size: inherit; color: var(--navy); text-decoration: underline;
  }
  .bt-back { display: inline-block; margin-bottom: 10px; font-size: clamp(10.5px, 1.5vh, 13px); }
  .bt-section { font-size: clamp(12px, 1.8vh, 15px); font-weight: 800; margin: clamp(10px, 1.6vh, 14px) 0 6px; }
  .bt-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 16px; font-size: clamp(10.5px, 1.5vh, 13px); }
  .bt-fact-label { font-size: clamp(8.5px, 1.22vh, 11px); font-weight: 700; color: var(--ink-muted); text-transform: uppercase; letter-spacing: .05em; }
  .bt-events { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; font-size: clamp(10.5px, 1.5vh, 13px); }
  .bt-events li { border-bottom: 1px solid var(--line); padding-bottom: 6px; }
  .bt-note { font-style: italic; margin-top: 2px; overflow-wrap: anywhere; }
  .bt-textarea {
    width: 100%; font-family: inherit; font-size: clamp(11.5px, 1.6vh, 14px); color: var(--ink); resize: vertical;
    padding: 9px 12px; border: 1.5px solid var(--line); border-radius: 8px; background: #FBFBFC;
  }
  .bt-textarea:focus { outline: none; border-color: var(--navy); background: #FFF; }
  .bt-counter { align-self: flex-end; color: var(--ink-muted); font-size: clamp(9px, 1.25vh, 11px); }
  @media (max-width: 640px) {
    .bt-batch { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
    .bt-actions { grid-column: 1 / -1; justify-content: flex-start; }
    .bt-actions .bt-btn { flex: 1 1 auto; padding: 9px 10px; }
    .bt-facts { grid-template-columns: minmax(0, 1fr); }
  }
`;
