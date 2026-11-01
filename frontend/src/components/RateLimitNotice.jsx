import React from "react";
import { ADMIN_RESET_LINK, TRY_AGAIN_MESSAGE, waitMessage } from "../lib/rateLimitCountdown.js";

// Inline so the two auth pages, each with its own style block, need no copy of it.
const SCREEN_READER_ONLY = {
  position: "absolute", width: 1, height: 1, overflow: "hidden", clip: "rect(0 0 0 0)", whiteSpace: "nowrap",
};

/**
 * The countdown banner. Only the always-present status line is announced; the ticking banner has no
 * live role, so a screen reader hears the start and the end. onTryNow is Sign in's, the one per-number limit.
 */
export default function RateLimitNotice({ countdown, onTryNow }) {
  return (
    <>
      <div role="status" aria-live="polite" style={SCREEN_READER_ONLY}>{countdown.announcement}</div>
      {countdown.waiting && <div className="au-alert">{waitMessage(countdown.secondsLeft)}</div>}
      {countdown.waiting && onTryNow && (
        <button type="button" className="au-link au-try-now" onClick={onTryNow}>{ADMIN_RESET_LINK}</button>
      )}
      {countdown.finished && <div className="au-alert au-alert--ok">{TRY_AGAIN_MESSAGE}</div>}
    </>
  );
}
