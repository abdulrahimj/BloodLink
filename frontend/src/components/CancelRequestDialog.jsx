import React, { useEffect, useRef, useState } from "react";
import { cancelBloodRequest } from "../api/requests.js";
import {
  CANCELLATION_NOTE_MAX,
  CANCELLATION_REASONS,
  cancelQuestion,
  cancellationProblem,
  suppliedUnitsNotice,
} from "../lib/requestCancellation.js";

/* BloodLink — the Hospital Portal's "Cancel request" box (Business Rule 35): reason, note, confirmation.
   Declares no colours of its own, inheriting the portal's CSS variables. */

const FOCUSABLE = "button:not(:disabled), select, textarea";

/**
 * onCancelled(updated) runs after the server accepts; onRefused(message) after a 409, as the request has just
 * closed and there is nothing left to do in this box. Escape or the scrim closes it; focus stays inside.
 */
export default function CancelRequestDialog({ request, onClose, onCancelled, onRefused }) {
  const [reason, setReason] = useState("");
  const [note, setNote] = useState("");
  const [fieldErrors, setFieldErrors] = useState({});
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef(null);
  const reasonRef = useRef(null);

  useEffect(() => { reasonRef.current?.focus(); }, []);

  useEffect(() => {
    function onKeyDown(event) {
      if (event.key === "Escape" && !busy) {
        onClose();
      } else if (event.key === "Tab") {
        const items = [...dialogRef.current.querySelectorAll(FOCUSABLE)];
        const first = items[0];
        const last = items[items.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [busy, onClose]);

  const problem = cancellationProblem({ reason, note });
  const supplied = suppliedUnitsNotice(request);

  async function handleConfirm() {
    setBusy(true);
    setError(null);
    setFieldErrors({});
    try {
      onCancelled(await cancelBloodRequest(request.id, { reason, note: note.trim() || null }));
    } catch (err) {
      if (err.status === 409) {
        onRefused(err.message);
        return;
      }
      setFieldErrors(err.fieldErrors ?? {});
      setError(err.message);
      setBusy(false);
    }
  }

  return (
    <div
      className="cr-scrim"
      onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onClose(); }}
    >
      <style>{`
        .cr-scrim {
          position: fixed; inset: 0; z-index: 50;
          background: rgba(17, 17, 17, .45);
          display: grid; place-items: center; padding: 16px; overflow-y: auto;
        }
        .cr-box {
          background: var(--card-bg); border-radius: var(--radius);
          box-shadow: 0 12px 40px rgba(0,0,0,.22);
          width: min(440px, 100%); padding: 20px;
          display: flex; flex-direction: column; gap: 12px;
        }
        .cr-title { font-size: 17px; font-weight: 800; letter-spacing: -.02em; }
        .cr-sub { font-size: 13px; color: var(--ink-muted); margin-top: 2px; }
        .cr-field { display: flex; flex-direction: column; gap: 4px; }
        .cr-label { font-size: 12px; font-weight: 600; color: #444; }
        .cr-input {
          font-family: inherit; font-size: 14px; font-weight: 500; color: var(--ink);
          padding: 9px 12px; width: 100%; resize: vertical;
          border: 1.5px solid var(--line); border-radius: 7px; background: #FBFBFC;
        }
        .cr-input:focus { outline: none; border-color: var(--navy); background: #FFF; }
        .cr-count { font-size: 11px; color: var(--ink-muted); text-align: right; }
        .cr-count--over { color: var(--red); font-weight: 700; }
        .cr-field-error { font-size: 12px; font-weight: 600; color: var(--red); line-height: 1.35; }
        .cr-info { font-size: 13px; line-height: 1.45; color: #3A3A3A; }
        .cr-warn {
          font-size: 13px; line-height: 1.45; font-weight: 600; color: #8A5510; background: #FBF1E3;
          border-left: 3px solid var(--amber); border-radius: 6px; padding: 9px 12px;
        }
        .cr-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 9px; }
        .cr-actions button {
          font-family: inherit; font-weight: 700; font-size: 13.5px; cursor: pointer;
          padding: 10px 16px; border-radius: 7px; border: 1.5px solid transparent;
        }
        .cr-keep { background: #FFF; border-color: var(--line) !important; color: #3A3A3A; }
        .cr-keep:hover { background: #F6F7F8; }
        .cr-confirm { background: var(--red); color: #FFF; }
        .cr-confirm:hover { background: var(--red-dark); }
        .cr-confirm:disabled { background: #C9CDD3; cursor: not-allowed; }
        .cr-actions button:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }
      `}</style>

      <div
        className="cr-box" ref={dialogRef}
        role="dialog" aria-modal="true" aria-labelledby="cr-title" aria-describedby="cr-question"
      >
        <div>
          <div className="cr-title" id="cr-title">Cancel request</div>
          <div className="cr-sub">
            #{request.reference} · {request.bloodType} · {request.unitsRequested} unit{request.unitsRequested > 1 ? "s" : ""}
          </div>
        </div>

        <div className="cr-field">
          <label className="cr-label" htmlFor="cr-reason">Reason</label>
          <select
            id="cr-reason" className="cr-input" ref={reasonRef} value={reason}
            onChange={(event) => setReason(event.target.value)}
          >
            <option value="">Choose a reason…</option>
            {CANCELLATION_REASONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
          {fieldErrors.reason && <div className="cr-field-error">{fieldErrors.reason}</div>}
        </div>

        <div className="cr-field">
          <label className="cr-label" htmlFor="cr-note">Note {reason === "OTHER" ? "(required)" : "(optional)"}</label>
          <textarea
            id="cr-note" className="cr-input" rows={2} value={note}
            onChange={(event) => setNote(event.target.value)}
          />
          <div className={`cr-count${note.trim().length > CANCELLATION_NOTE_MAX ? " cr-count--over" : ""}`}>
            {note.trim().length}/{CANCELLATION_NOTE_MAX}
          </div>
          {fieldErrors.note && <div className="cr-field-error">{fieldErrors.note}</div>}
        </div>

        {supplied && <div className="cr-info">{supplied}</div>}
        <div className="cr-warn" id="cr-question">{cancelQuestion(request)}</div>
        {error && <div className="cr-field-error" role="alert">{error}</div>}

        <div className="cr-actions">
          <button type="button" className="cr-keep" disabled={busy} onClick={onClose}>Keep request</button>
          <button type="button" className="cr-confirm" disabled={busy || problem !== null} onClick={handleConfirm}>
            {busy ? "Cancelling…" : "Cancel request"}
          </button>
        </div>
      </div>
    </div>
  );
}
