import React, { useCallback, useEffect, useRef, useState } from "react";
import { getInventory } from "../api/inventory.js";
import { getBankDashboard } from "../api/bloodBanks.js";
import { actOnRequest, getRequests } from "../api/requests.js";
import { formatHeaderDate, formatRelativeTime, initials } from "../lib/dates.js";
import NotificationBell from "../components/NotificationBell.jsx";
import ProfileSettings from "../components/ProfileSettings.jsx";
import { useNotifications } from "../realtime/useNotifications.js";

/* ================================================================
   BloodLink — Blood Bank Dashboard (home view)

   Covers the blood bank / lab staff use cases:
     • Monitor stock levels across all eight blood groups
     • Review and act on incoming hospital requests
     • Track donations recorded at this facility

   Stock figures are the Makeni Regional share of the citywide
   total shown on the Hospital Portal, so the two screens agree.

   Wired to GET /api/inventory/{bloodBankId}, GET
   /api/blood-banks/{bloodBankId}/dashboard, and — for the requests panel —
   GET /api/requests plus PATCH /api/requests/{id}. Layout, styling and
   structure are the mockup's, unchanged; the STOCK/REQUESTS fixtures are
   replaced with fetched data, and a loading/error pair was added using the
   same idiom as the Donor Portal (tokens already in this file, nothing new).

   The panel is the blood bank half of Figure 3.5, and its buttons are real:
   Contribute and Decline on any request that still needs units. Contributing
   moves units out of this bank's stock immediately, so the whole screen is
   reloaded afterwards — the inventory table and the "Available Units" card
   both change with it.

   Since Business Rule 20 the panel is cooperative rather than competitive: a
   request stays here while any units are outstanding, however many other
   banks have already given some, and this bank can contribute again. Each row
   shows how far along the request is ("2 of 4 units received"), because the
   only number that decides what to do next is how many are still missing.

   Decline goes through a confirmation step first. It is the one action here
   the operator cannot take back: the request leaves this bank's queue for
   good (Business Rule 11), and a bank that has declined cannot later
   contribute to the same request. That consequence is invisible from the
   button, so it is spelled out before the click is submitted.

   The screen is also live. A WebSocket session (see realtime/useNotifications)
   feeds the bell in the header, and two of the notification types it carries
   change what the rest of the page should be showing: a NEW_REQUEST means a
   row belongs in the requests panel, and a LOW_STOCK_ALERT means a figure in
   the inventory table has moved. Both trigger the same full reload the action
   buttons already use, so the panels are refreshed from the API rather than
   patched from the notification's wording — the alert is what says something
   changed, never what the new state is.
   ================================================================ */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };
const ICON_W = { fill: "none", stroke: "#FFF", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

/** SUFFICIENT/LOW/CRITICAL -> Sufficient/Low/Critical, matching the CSS class
 *  suffixes and badge text the mockup already defines. */
function titleCase(status) {
  return status.charAt(0) + status.slice(1).toLowerCase();
}

export default function BloodBankDashboard({ bloodBankId, user, onSignOut, onNavigate, activeView, onUserUpdated }) {
  // BloodBankPortal shows this page for both "dashboard" and "settings"; on the
  // latter, the main area is the shared Profile Settings screen (Business Rule 16).
  const onSettings = activeView === "settings";
  const [stock, setStock] = useState([]);
  const [dashboard, setDashboard] = useState(null);
  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [today] = useState(() => new Date());

  // Which request is mid-action, and the last action failure — both keyed to one
  // row so a refusal ("not enough O- in stock") appears against the request it
  // belongs to instead of as a page-level error.
  const [actingOn, setActingOn] = useState(null);
  const [actionError, setActionError] = useState(null);

  // The request awaiting a confirmed Reject, or null when the dialog is closed.
  // The whole request is held rather than just its id so the dialog can name what
  // is about to be turned down without looking it back up.
  const [confirmingReject, setConfirmingReject] = useState(null);
  const cancelRejectRef = useRef(null);

  /**
   * Loads the three calls behind this screen together. Extracted from the effect
   * so an action can reuse it: approving or fulfilling changes the stat cards,
   * and fulfilling changes the inventory table too, so the honest refresh is the
   * whole screen rather than patching the one row that was clicked.
   */
  const load = useCallback(async () => {
    const [inventory, dashboardData, requestList] = await Promise.all([
      getInventory(bloodBankId),
      getBankDashboard(bloodBankId),
      getRequests(bloodBankId),
    ]);
    return { inventory, dashboardData, requestList };
  }, [bloodBankId]);

  /**
   * Reloads and applies, without touching the page-level loading or error state.
   *
   * Used by the live notification handler below, where the reload is something the
   * screen did on its own rather than something the operator asked for: blanking a
   * populated dashboard to "Loading…" because a notification arrived, or replacing
   * it with an error banner because one background refresh failed, would both be
   * far more disruptive than briefly showing figures a few seconds out of date.
   */
  const refresh = useCallback(async () => {
    try {
      const { inventory, dashboardData, requestList } = await load();
      setStock(inventory);
      setDashboard(dashboardData);
      setRequests(requestList);
    } catch {
      // Left as it was; the next action or notification will try again.
    }
  }, [load]);

  // The header bell, plus the live trigger for the refresh above. Notifications
  // about this bank's own stock and about newly raised hospital requests are the
  // two that make what is on screen out of date; a request status change is this
  // bank's own action echoed back, and the panel has already been reloaded by the
  // click that caused it.
  const { notifications, unreadCount, connected, markAllRead } = useNotifications({
    onNotification: (notification) => {
      // REQUEST_PROGRESS is the Business Rule 20 one: another bank has contributed
      // to a request this bank is also looking at, so the units outstanding on that
      // row — and the cap on its own units box — have just changed. Reloading is
      // what stops two banks each setting out to cover the same shrinking gap.
      if (notification.type === "NEW_REQUEST" || notification.type === "LOW_STOCK_ALERT"
          || notification.type === "REQUEST_PROGRESS") {
        refresh();
      }
    },
  });

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    load()
      .then(({ inventory, dashboardData, requestList }) => {
        if (cancelled) return;
        setStock(inventory);
        setDashboard(dashboardData);
        setRequests(requestList);
      })
      .catch((err) => { if (!cancelled) setError(err.message); })
      .finally(() => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
  }, [load]);

  /**
   * While the reject dialog is open: Escape closes it, and focus moves to Cancel.
   *
   * Cancel rather than the confirm button deliberately — this is a destructive,
   * irreversible action, so the keyboard's default landing spot should be the way
   * out of it, not the way through. Escape is wired at the document because the
   * dialog is not a native <dialog> element and would otherwise only hear the key
   * while something inside it happens to hold focus.
   */
  useEffect(() => {
    if (!confirmingReject) return;

    cancelRejectRef.current?.focus();
    const onKeyDown = (event) => {
      if (event.key === "Escape") setConfirmingReject(null);
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [confirmingReject]);

  /**
   * Contributes units to a request, or declines it (Business Rule 20).
   *
   * bloodBankId is passed through for the ADMIN case, where the backend needs to
   * be told which bank is acting; for BLOOD_BANK_STAFF it is ignored server-side
   * in favour of their own assigned bank, so sending it is harmless.
   *
   * A 409 here is an ordinary outcome, not a bug — another bank completed the
   * request a moment ago, or the stock moved since the panel was drawn — so the
   * message is shown against the row and the screen is reloaded to show the real
   * state.
   */
  async function handleAction(requestId, action, units = null) {
    setActingOn(requestId);
    setActionError(null);
    try {
      await actOnRequest(requestId, action, bloodBankId, units);
      const { inventory, dashboardData, requestList } = await load();
      setStock(inventory);
      setDashboard(dashboardData);
      setRequests(requestList);
    } catch (err) {
      setActionError({ requestId, message: err.message });
      // Re-read regardless: a refusal usually means this screen's copy of the
      // request is stale, and leaving the old buttons up invites a second failure.
      try {
        const { inventory, dashboardData, requestList } = await load();
        setStock(inventory);
        setDashboard(dashboardData);
        setRequests(requestList);
      } catch {
        // The action's own message is the useful one; a failed refresh on top of
        // it would only replace it with something less specific.
      }
    } finally {
      setActingOn(null);
    }
  }

  /**
   * Confirmed Reject. The dialog closes first so the panel is visible while the
   * request goes out — the row's own busy state and any refusal message belong
   * behind it, not stacked under a dialog the operator has already finished with.
   */
  function handleConfirmReject() {
    const requestId = confirmingReject.id;
    setConfirmingReject(null);
    handleAction(requestId, "REJECT");
  }

  return (
    <div className="bb-root">
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');

        .bb-root {
          --navy:       #1C3A5E;
          --navy-soft:  #2A4E77;
          --red:        #C8102E;
          --red-dark:   #A50E26;
          --green:      #1E7F45;
          --amber:      #D98A1F;
          --page-bg:    #F1F2F4;
          --card-bg:    #FFFFFF;
          --ink:        #111111;
          --ink-muted:  #7A7A7A;
          --line:       #E6E8EB;
          --radius:     10px;
          --shadow:     0 2px 10px rgba(0,0,0,0.07);

          font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
          color: var(--ink);
          background: var(--page-bg);
          height: 100vh; min-height: 680px; overflow: hidden;
          display: flex;
          -webkit-font-smoothing: antialiased;
        }
        .bb-root *, .bb-root *::before, .bb-root *::after { box-sizing: border-box; }

        /* ===== SIDEBAR ===== */
        .bb-side {
          flex: 0 0 auto; width: clamp(190px, 17vw, 250px);
          background: var(--navy); display: flex; flex-direction: column;
          padding: clamp(14px, 2.4vh, 26px) clamp(10px, 1vw, 16px);
          gap: clamp(10px, 1.6vh, 18px);
        }
        .bb-brand { display: flex; align-items: center; gap: 9px; padding: 0 6px; }
        .bb-brand-name { font-size: clamp(14px, 2vh, 17px); font-weight: 800; letter-spacing: -.02em; line-height: 1; }
        .bb-brand-tag { font-size: clamp(4.6px, .72vh, 6px); letter-spacing: .14em; color: rgba(255,255,255,.7); margin-top: 3px; font-weight: 600; }
        .bb-role { margin: clamp(2px, .8vh, 8px) 6px 0; font-size: clamp(8px, 1.1vh, 10px); font-weight: 700; letter-spacing: .1em; color: rgba(255,255,255,.5); }

        .bb-nav { display: flex; flex-direction: column; gap: clamp(2px, .5vh, 5px); }
        .bb-nav button {
          display: flex; align-items: center; gap: 11px; width: 100%; text-align: left;
          background: transparent; border: none; cursor: pointer;
          color: rgba(255,255,255,.86); font-family: inherit;
          font-size: clamp(11.5px, 1.72vh, 15px); font-weight: 600;
          padding: clamp(7px, 1.24vh, 12px) 12px; border-radius: 8px;
          transition: background .15s ease, color .15s ease;
        }
        .bb-nav button svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); flex: 0 0 auto; }
        .bb-nav button:hover { background: rgba(255,255,255,.08); color: #FFF; }
        .bb-nav button.is-active { background: var(--navy-soft); color: #FFF; }
        .bb-nav button:focus-visible, .bb-logout:focus-visible { outline: 2px solid #FFF; outline-offset: 2px; }

        .bb-logout {
          margin-top: auto; display: flex; align-items: center; gap: 11px;
          background: rgba(255,255,255,.07); border: none; cursor: pointer; color: #FFF;
          font-family: inherit; font-weight: 600; font-size: clamp(11.5px, 1.72vh, 15px);
          padding: clamp(8px, 1.3vh, 13px) 14px; border-radius: 8px;
        }
        .bb-logout:hover { background: rgba(255,255,255,.14); }
        .bb-logout svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); }

        /* ===== MAIN ===== */
        .bb-main {
          flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column;
          padding: clamp(12px, 2.2vh, 24px) clamp(14px, 1.6vw, 28px);
          gap: clamp(9px, 1.6vh, 18px); overflow: hidden;
        }
        .bb-top { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
        .bb-title { font-size: clamp(17px, 2.9vh, 26px); font-weight: 800; letter-spacing: -.025em; }
        .bb-sub { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); margin-top: 2px; }
        .bb-top-right { display: flex; align-items: center; gap: clamp(10px, 1vw, 16px); }
        .bb-date { display: flex; align-items: center; gap: 8px; font-size: clamp(10px, 1.5vh, 13px); font-weight: 600; color: #3A3A3A; }
        .bb-date svg { width: clamp(14px, 2vh, 17px); height: clamp(14px, 2vh, 17px); }
        /* The bell moved to <NotificationBell>, which brings its own styles and
           inherits the tokens declared above. The mockup's .bb-bell rules were a
           fixed red dot that was always on; a count that is sometimes zero cannot
           be drawn with a ::after pseudo-element, and leaving them here would mean
           two sets of bell styles with only one bell. */
        .bb-avatar {
          width: clamp(28px, 4.2vh, 38px); height: clamp(28px, 4.2vh, 38px);
          border-radius: 50%; background: var(--navy); display: grid; place-items: center;
          color: #FFF; font-weight: 700; font-size: clamp(10px, 1.5vh, 13px);
          border: 2px solid #FFF; box-shadow: var(--shadow);
        }

        /* ===== STATS ===== */
        .bb-stats { flex: 0 0 auto; display: grid; grid-template-columns: repeat(4, 1fr); gap: clamp(9px, 1.1vw, 18px); }
        .bb-stat {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(9px, 1.5vh, 16px) clamp(11px, 1.1vw, 18px);
          display: flex; align-items: center; justify-content: space-between; gap: 10px;
        }
        .bb-stat-figure { font-size: clamp(18px, 3vh, 28px); font-weight: 800; line-height: 1.05; letter-spacing: -.02em; }
        .bb-stat-label { font-size: clamp(10.5px, 1.6vh, 14px); font-weight: 700; margin-top: 1px; }
        .bb-stat-note { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); margin-top: 2px; }
        .bb-stat-icon { flex: 0 0 auto; width: clamp(30px, 4.6vh, 44px); height: clamp(30px, 4.6vh, 44px); border-radius: 50%; display: grid; place-items: center; }
        .bb-stat-icon svg { width: 52%; height: 52%; }
        .bb-i-red { background: var(--red); } .bb-i-green { background: var(--green); }
        .bb-i-amber { background: var(--amber); } .bb-i-navy { background: var(--navy); }

        /* ===== PANELS ===== */
        .bb-panels { flex: 1 1 auto; min-height: 0; display: grid; grid-template-columns: 1fr 1.15fr; gap: clamp(9px, 1.1vw, 18px); }
        .bb-panel {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(11px, 1.7vh, 18px) clamp(12px, 1.2vw, 20px);
          display: flex; flex-direction: column; min-height: 0;
        }
        .bb-panel-head { flex: 0 0 auto; display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: clamp(7px, 1.2vh, 13px); }
        .bb-panel-title { font-size: clamp(12px, 1.85vh, 16px); font-weight: 700; letter-spacing: -.01em; }
        .bb-panel-link { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: var(--red); cursor: pointer; }

        /* stock table */
        .bb-table { width: 100%; border-collapse: collapse; }
        .bb-table th {
          text-align: left; font-size: clamp(8.5px, 1.22vh, 11px); font-weight: 700; color: var(--ink-muted);
          text-transform: uppercase; letter-spacing: .05em;
          padding: 0 6px clamp(5px, .9vh, 9px); border-bottom: 1.5px solid var(--line);
        }
        .bb-table td { font-size: clamp(10px, 1.48vh, 13px); padding: clamp(5px, 1vh, 10px) 6px; border-bottom: 1px solid var(--line); }
        .bb-table tr:last-child td { border-bottom: none; }
        .bb-grp { font-weight: 800; letter-spacing: -.02em; }
        .bb-bar { height: 6px; border-radius: 4px; background: #EEF0F2; overflow: hidden; min-width: 46px; }
        .bb-bar span { display: block; height: 100%; border-radius: 4px; }

        .bb-badge { font-size: clamp(8.5px, 1.18vh, 11px); font-weight: 700; padding: 3px clamp(7px, .7vw, 11px); border-radius: 20px; color: #FFF; white-space: nowrap; }
        .bb-b-sufficient { background: var(--green); }
        .bb-b-low { background: var(--amber); }
        .bb-b-critical { background: var(--red); }
        .bb-b-pending { background: var(--amber); }
        /* Part way there: green, the "something good has happened" tone, but the
           row keeps its Contribute button — this is progress, not completion. */
        .bb-b-partially-fulfilled { background: var(--green); }
        .bb-b-fulfilled { background: var(--navy); }
        .bb-b-rejected { background: var(--red); }

        /* request list */
        /* overflow-y: auto rather than the mockup's hidden — the fixture was a
           fixed five rows, but a real bank's feed keeps growing and requests
           below the fold would otherwise be unreachable, not just unseen. */
        .bb-list { flex: 1 1 auto; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: clamp(4px, .8vh, 9px); }
        .bb-item { display: flex; align-items: center; gap: clamp(8px, .8vw, 12px); padding: clamp(5px, 1vh, 10px) clamp(7px, .7vw, 11px); border: 1px solid var(--line); border-radius: 8px; }
        .bb-item-grp {
          flex: 0 0 auto; width: clamp(28px, 4.2vh, 38px); height: clamp(28px, 4.2vh, 38px);
          border-radius: 50%; background: #FBE9EC; color: var(--red);
          display: grid; place-items: center; font-weight: 800; font-size: clamp(9.5px, 1.4vh, 12.5px); letter-spacing: -.02em;
        }
        .bb-item-body { flex: 1 1 auto; min-width: 0; }
        .bb-item-main { font-size: clamp(10.5px, 1.55vh, 13.5px); font-weight: 700; }
        .bb-item-sub { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); margin-top: 1px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .bb-act {
          flex: 0 0 auto; background: var(--red); color: #FFF; border: none; border-radius: 6px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(9px, 1.28vh, 11.5px);
          padding: clamp(5px, .85vh, 8px) clamp(8px, .8vw, 13px);
        }
        .bb-act:hover { background: var(--red-dark); }
        .bb-act:disabled { background: #C9CDD3; cursor: not-allowed; }
        /* Reject is destructive but not the primary move, so it is outlined
           rather than filled — the mockup's single red button stays the one the
           operator's eye lands on first. */
        .bb-act--ghost { background: transparent; color: var(--ink-muted); border: 1.5px solid var(--line); }
        .bb-act--ghost:hover { background: #F6F7F8; color: var(--ink); border-color: #C9CDD3; }
        .bb-act--ghost:disabled { background: transparent; color: #C9CDD3; }
        /* Contribute is the step that actually moves units, so it carries the navy
           "settled" colour the Fulfilled badge already uses on this screen. */
        .bb-act--go { background: var(--navy); }
        .bb-act--go:hover { background: var(--navy-soft); }

        /* How many units this bank is giving. Narrow on purpose: the number is
           never more than two digits, and the row already has three controls. */
        .bb-units { flex: 0 0 auto; display: flex; flex-direction: column; gap: 2px; }
        .bb-units-label { font-size: clamp(7.5px, 1.05vh, 9.5px); font-weight: 700; color: var(--ink-muted); text-transform: uppercase; letter-spacing: .05em; }
        .bb-units input {
          width: clamp(44px, 4vw, 58px); font-family: inherit;
          font-size: clamp(10px, 1.45vh, 13px); font-weight: 700; text-align: center;
          padding: clamp(4px, .7vh, 7px) 4px;
          border: 1.5px solid var(--line); border-radius: 6px; background: #FFF; color: var(--ink);
        }
        .bb-units input:focus { outline: none; border-color: var(--navy); }
        .bb-units input:disabled { background: #F6F7F8; color: var(--ink-muted); cursor: not-allowed; }

        /* Progress line under a request that has received some units. Green, the
           same "units have arrived" tone as the Partially fulfilled badge. */
        .bb-item-progress { font-size: clamp(8.5px, 1.2vh, 11px); font-weight: 600; color: var(--green); margin-top: 2px; }
        .bb-item-ours { color: var(--ink-muted); font-weight: 500; }

        /* Wiring states — additive, same tokens, for real async data. */
        .bb-loading, .bb-error { flex: 1 1 auto; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; text-align: center; }
        .bb-error-msg { color: var(--red); font-weight: 600; font-size: clamp(11px, 1.6vh, 14px); }
        .bb-empty { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); padding: clamp(8px, 1.4vh, 14px) 2px; }
        .bb-item-error { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--red); font-weight: 600; line-height: 1.35; white-space: normal; }

        /* ===== REJECT CONFIRMATION ===== */
        /* Fixed to the viewport, not the panel: .bb-list scrolls and .bb-root is
           overflow:hidden, so anything absolutely positioned inside the panel
           would be clipped or would scroll away with the row it belongs to. */
        .bb-scrim {
          position: fixed; inset: 0; z-index: 50;
          background: rgba(17, 17, 17, .45);
          display: grid; place-items: center;
          padding: 20px;
        }
        .bb-dialog {
          background: var(--card-bg); border-radius: var(--radius);
          box-shadow: 0 12px 40px rgba(0,0,0,.22);
          width: min(440px, 100%);
          padding: clamp(16px, 2.4vh, 24px) clamp(16px, 1.6vw, 26px);
          display: flex; flex-direction: column; gap: clamp(8px, 1.2vh, 13px);
        }
        .bb-dialog-head { display: flex; align-items: center; gap: 11px; }
        .bb-dialog-icon {
          flex: 0 0 auto; width: 36px; height: 36px; border-radius: 50%;
          background: var(--red); display: grid; place-items: center;
        }
        .bb-dialog-icon svg { width: 52%; height: 52%; }
        .bb-dialog-title { font-size: clamp(14px, 2vh, 17px); font-weight: 800; letter-spacing: -.02em; }
        .bb-dialog-body { font-size: clamp(11px, 1.55vh, 13.5px); line-height: 1.5; color: #3A3A3A; }
        /* The plain "cannot be undone" confirmation — given the same amber the
           "needs attention" states already use on this screen. */
        .bb-dialog-warn {
          font-size: clamp(10.5px, 1.5vh, 13px); line-height: 1.5; font-weight: 600;
          color: #8A5510; background: #FBF1E3;
          border-left: 3px solid var(--amber); border-radius: 6px;
          padding: clamp(8px, 1.2vh, 12px) clamp(10px, 1vw, 14px);
        }
        /* The stronger warning for an Emergency request this bank could actually
           cover — red rather than amber, the same escalation the stock badges on
           this screen already use between Low and Critical. */
        .bb-dialog-warn--critical {
          color: var(--red-dark); background: #FBE7EA;
          border-left-color: var(--red);
        }
        .bb-dialog-actions { display: flex; justify-content: flex-end; gap: 9px; margin-top: clamp(2px, .6vh, 6px); }
        .bb-dialog-actions button {
          font-family: inherit; font-weight: 700; cursor: pointer;
          font-size: clamp(11px, 1.55vh, 13.5px);
          padding: clamp(8px, 1.15vh, 11px) clamp(13px, 1.3vw, 20px);
          border-radius: 7px; border: 1.5px solid transparent;
        }
        .bb-dialog-cancel { background: #FFF; border-color: var(--line); color: #3A3A3A; }
        .bb-dialog-cancel:hover { background: #F6F7F8; border-color: #C9CDD3; }
        .bb-dialog-confirm { background: var(--red); color: #FFF; }
        .bb-dialog-confirm:hover { background: var(--red-dark); }
        .bb-dialog-actions button:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }

        @media (prefers-reduced-motion: reduce) { .bb-root * { transition: none !important; } }
      `}</style>

      <aside className="bb-side">
        <div className="bb-brand">
          <svg width="28" height="32" viewBox="0 0 34 38" aria-hidden="true">
            <path d="M17 2s13 14 13 22a13 13 0 0 1-26 0C4 16 17 2 17 2z" fill="#C8102E" />
            <path d="M7 24h5l2.5-6 3.5 12 3-8 2 2h5" fill="none" stroke="#FFF" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <div>
            <div className="bb-brand-name">
              <span style={{ color: "#C8102E" }}>Blood</span><span style={{ color: "#FFFFFF" }}>Link</span>
            </div>
            <div className="bb-brand-tag">GIVE BLOOD · SAVE LIVES</div>
          </div>
        </div>

        <div className="bb-role">{user.role === "ADMIN" ? "ADMINISTRATOR" : "BLOOD BANK STAFF"}</div>

        <nav className="bb-nav">
          <button
            type="button" className={onSettings ? "" : "is-active"}
            aria-current={onSettings ? undefined : "page"}
            onClick={() => onNavigate("dashboard")}
          >
            <GridIcon /> Dashboard
          </button>
          <button type="button" onClick={() => onNavigate("inventory")}><DropletOutlineIcon /> Blood Inventory</button>
          <button type="button"><TruckIcon /> Blood Logistics</button>
          <button type="button"><UsersIcon /> Donors</button>
          <button type="button"><ChartIcon /> Report</button>
          <button
            type="button" className={onSettings ? "is-active" : ""}
            aria-current={onSettings ? "page" : undefined}
            onClick={() => onNavigate("settings")}
          >
            <GearIcon /> Settings
          </button>
        </nav>

        <button type="button" className="bb-logout" onClick={onSignOut}><LogoutIcon /> Logout</button>
      </aside>

      <main className="bb-main">
        <div className="bb-top">
          <div>
            <div className="bb-title">{onSettings ? "Profile Settings" : (dashboard?.bloodBankName ?? "Blood Bank Dashboard")}</div>
            <div className="bb-sub">{onSettings ? "Your name, email and password" : "Stock and request overview"}</div>
          </div>
          <div className="bb-top-right">
            <div className="bb-date"><CalendarIcon /> Today, {formatHeaderDate(today)}</div>
            <NotificationBell
              notifications={notifications}
              unreadCount={unreadCount}
              connected={connected}
              onMarkAllRead={markAllRead}
            />
            <div className="bb-avatar">{initials(user.fullName)}</div>
          </div>
        </div>

        {onSettings && <ProfileSettings onUserUpdated={onUserUpdated} />}

        {!onSettings && loading && (
          <div className="bb-loading"><div className="bb-sub">Loading dashboard…</div></div>
        )}

        {!onSettings && !loading && error && (
          <div className="bb-error"><div className="bb-error-msg">{error}</div></div>
        )}

        {!onSettings && !loading && !error && dashboard && (
          <>
            <div className="bb-stats">
              <Stat tone="red" icon={<DropletIcon />} figure={dashboard.totalUnits} label="Available Units" note="Across all groups" />
              <Stat tone="green" icon={<GiftIcon />} figure={dashboard.donationsThisMonth} label="Donations" note="This month" />
              <Stat tone="amber" icon={<HourglassIcon />} figure={dashboard.openRequestsCount} label="Open Requests" note="Units still needed" />
              <Stat tone="navy" icon={<UsersFillIcon />} figure={dashboard.registeredDonorsCount} label="Registered Donors" note="Linked to this bank" />
            </div>

            <div className="bb-panels">
              <div className="bb-panel">
                <div className="bb-panel-head">
                  <div className="bb-panel-title">Blood Inventory</div>
                  <div className="bb-panel-link" onClick={() => onNavigate("inventory")}>Manage stock</div>
                </div>
                <table className="bb-table">
                  <thead>
                    <tr>
                      <th style={{ width: "16%" }}>Group</th>
                      <th style={{ width: "18%" }}>Units</th>
                      <th style={{ width: "36%" }}>Level</th>
                      <th style={{ width: "30%" }}>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {stock.map((s) => (
                      <tr key={s.bloodType}>
                        <td className="bb-grp">{s.bloodType}</td>
                        <td>{s.unitsAvailable}</td>
                        <td>
                          <div className="bb-bar">
                            <span style={{ width: `${Math.min(100, (s.unitsAvailable / 120) * 100)}%`, background: stateColor(s.status) }} />
                          </div>
                        </td>
                        <td><span className={`bb-badge bb-b-${s.status.toLowerCase()}`}>{titleCase(s.status)}</span></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="bb-panel">
                <div className="bb-panel-head">
                  <div className="bb-panel-title">Incoming Hospital Requests</div>
                  <div className="bb-panel-link">View All</div>
                </div>
                <div className="bb-list">
                  {requests.length === 0 && (
                    <div className="bb-empty">No requests yet.</div>
                  )}
                  {requests.map((r) => (
                    <IncomingRequest
                      key={r.id}
                      request={r}
                      bloodBankId={bloodBankId}
                      stock={stock}
                      busy={actingOn === r.id}
                      error={actionError?.requestId === r.id ? actionError.message : null}
                      onContribute={(units) => handleAction(r.id, "CONTRIBUTE", units)}
                      onDecline={() => setConfirmingReject(r)}
                    />
                  ))}
                </div>
              </div>
            </div>
          </>
        )}
      </main>

      {confirmingReject && (
        <RejectConfirmation
          request={confirmingReject}
          stock={stock}
          cancelRef={cancelRejectRef}
          onCancel={() => setConfirmingReject(null)}
          onConfirm={handleConfirmReject}
        />
      )}
    </div>
  );
}

/**
 * One row of "Incoming Hospital Requests", with everything this bank can do about
 * it — Business Rule 20.
 *
 * The row shows progress rather than a yes/no state, because a request can now be
 * part way there: "2 of 4 units received" with the contributing banks named. A bank
 * that has already given some units still sees the request while any remain
 * outstanding, and can give again — which is why the Contribute control stays put
 * after a contribution instead of turning into a "done" state.
 *
 * The units box is capped at whichever is smaller, what the request still needs or
 * what this bank actually holds of that group, and starts at that number: the
 * common case is "give what I can", and the operator lowers it if they want to keep
 * some back. The cap is a courtesy — the server caps against the outstanding figure
 * itself, and refuses more units than the bank holds — but a box that lets someone
 * type 40 when 2 are wanted invites a refusal it could have prevented.
 *
 * Stock is read from the same `stock` rows the table on the left of this screen is
 * drawn from, never a second lookup, so the number the box allows and the number
 * the operator can see are always the same number.
 */
function IncomingRequest({ request: r, bloodBankId, stock, busy, error, onContribute, onDecline }) {
  const outstanding = r.unitsOutstanding;
  const inStock = stock.find((s) => s.bloodType === r.bloodType)?.unitsAvailable ?? 0;
  const mostWeCanGive = Math.min(outstanding, inStock);
  const open = r.status === "PENDING" || r.status === "PARTIALLY_FULFILLED";

  const [units, setUnits] = useState(mostWeCanGive);

  // Both figures move under the operator: another bank contributing shrinks
  // `outstanding`, and this bank's own contribution shrinks its stock. Whenever the
  // ceiling changes, the box follows it rather than keeping a number that is no
  // longer allowed.
  useEffect(() => { setUnits(mostWeCanGive); }, [mostWeCanGive]);

  const ourUnits = r.contributions
    .filter((c) => c.bloodBankId === bloodBankId)
    .reduce((total, c) => total + c.unitsContributed, 0);

  return (
    <div className="bb-item">
      <div className="bb-item-grp">{r.bloodType}</div>
      <div className="bb-item-body">
        <div className="bb-item-main">
          {r.unitsRequested} Unit{r.unitsRequested > 1 ? "s" : ""} of {r.bloodType} · {titleCase(r.urgencyLevel)}
        </div>
        <div className="bb-item-sub">
          {r.hospitalName} · {formatRelativeTime(r.createdAt)}
          {r.patientReference ? ` · ${r.patientReference}` : ""}
        </div>
        {/* Progress, shown as soon as anything has been given — and only then, so
            an untouched request is not cluttered with "0 of 4". */}
        {r.unitsContributed > 0 && (
          <div className="bb-item-progress">
            {r.unitsContributed} of {r.unitsRequested} units received
            {outstanding > 0 ? ` · ${outstanding} still needed` : " · complete"}
            {` · from ${[...new Set(r.contributions.map((c) => c.bloodBankName))].join(", ")}`}
            {ourUnits > 0 && <span className="bb-item-ours"> · {ourUnits} from us</span>}
          </div>
        )}
        {open && inStock === 0 && (
          <div className="bb-item-sub">No {r.bloodType} in stock here — this request needs another bank.</div>
        )}
        {error && <div className="bb-item-error">{error}</div>}
      </div>

      <span className={`bb-badge bb-b-${r.status.toLowerCase().replace(/_/g, "-")}`}>
        {titleCase(r.status).replace(/_/g, " ")}
      </span>

      {open && (
        <>
          <label className="bb-units">
            <span className="bb-units-label">Units</span>
            <input
              type="number" min={1} max={mostWeCanGive} value={units}
              disabled={busy || mostWeCanGive === 0}
              onChange={(e) => setUnits(e.target.value === "" ? "" : Number(e.target.value))}
            />
          </label>
          <button
            type="button" className="bb-act bb-act--go"
            disabled={busy || mostWeCanGive === 0 || !units || units < 1}
            onClick={() => onContribute(Math.min(Number(units), mostWeCanGive))}
          >
            {busy ? "…" : "Contribute"}
          </button>
          {/* Confirmed first — declining drops the request out of this bank's
              queue for good, which the button alone cannot convey. */}
          <button type="button" className="bb-act bb-act--ghost" disabled={busy} onClick={onDecline}>
            Decline
          </button>
        </>
      )}
    </div>
  );
}

/**
 * Asks before a Decline is submitted — confirmed rather than submitted straight
 * from the button because the operator cannot undo their own decline, and the
 * request vanishing from their queue is a surprising thing to trigger with a
 * stray click (Business Rule 11: a decline is per bank, so what actually
 * happens elsewhere depends on what the other bank does, not on this click
 * alone — not this bank's own staff's decision to make, so the dialog no
 * longer says anything about it).
 *
 * The warning shown is one of two, decided from what this bank can already see
 * about the request and its own stock, not a separate lookup:
 *   • Ordinary case (Routine or Urgent, or an Emergency this bank genuinely
 *     cannot cover) — a plain confirmation with no mention of risk this bank
 *     cannot actually change by acting differently.
 *   • Emergency AND this bank currently holds enough units of the requested
 *     group to fulfil it — a stronger warning naming that specifically, because
 *     here declining is a choice not to help with a request this bank could
 *     have covered, for a patient the EMERGENCY level says cannot wait.
 * "Enough units" reads the same unitsAvailable figure the Inventory screen and
 * dashboard table already show (passed in as `stock`), not a second calculation
 * that could quietly drift from what the operator sees on the rest of the page.
 *
 * Deliberately a styled dialog rather than window.confirm — the browser's own
 * prompt cannot carry the warning with any emphasis, names the page rather than
 * the request, and looks nothing like the rest of the system in a screenshot.
 *
 * Clicking the scrim cancels, matching Escape. The guard on event.target keeps a
 * click that starts inside the dialog and drifts onto the backdrop (selecting the
 * warning text, say) from being read as a dismissal.
 */
function RejectConfirmation({ request, stock, cancelRef, onCancel, onConfirm }) {
  const units = `${request.unitsRequested} unit${request.unitsRequested > 1 ? "s" : ""}`;

  // The same row the Inventory table and this dashboard's own stock table read
  // unitsAvailable from — never recomputed, just looked up. Judged against the
  // units still outstanding rather than the whole request (Business Rule 20):
  // what this bank is declining is the remainder, which another bank may already
  // have shrunk, and a bank that can cover what is left is turning down a request
  // it could finish.
  const matchingStock = stock.find((s) => s.bloodType === request.bloodType);
  const bankCanCoverIt = (matchingStock?.unitsAvailable ?? 0) >= request.unitsOutstanding;
  const isUnhelpedEmergency = request.urgencyLevel === "EMERGENCY" && bankCanCoverIt;

  return (
    <div
      className="bb-scrim"
      onMouseDown={(event) => { if (event.target === event.currentTarget) onCancel(); }}
    >
      <div
        className="bb-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="bb-reject-title"
        aria-describedby="bb-reject-body"
      >
        <div className="bb-dialog-head">
          <div className="bb-dialog-icon"><AlertIcon /></div>
          <div className="bb-dialog-title" id="bb-reject-title">Decline this request?</div>
        </div>

        <div className="bb-dialog-body" id="bb-reject-body">
          {request.hospitalName} asked for <strong>{units} of {request.bloodType}</strong>
          {request.patientReference ? <> for {request.patientReference}</> : null}
          {" "}({titleCase(request.urgencyLevel).toLowerCase()}).
          {request.unitsContributed > 0 && (
            <> {request.unitsContributed} of those units have already been received from other banks,
              leaving <strong>{request.unitsOutstanding}</strong> outstanding.</>
          )}
        </div>

        {isUnhelpedEmergency ? (
          <div className="bb-dialog-warn bb-dialog-warn--critical">
            This is an <strong>Emergency</strong> request, and your bank currently has enough
            units of <strong>{request.bloodType}</strong> to cover what is still outstanding.
            Declining could delay life-saving treatment for the patient. Are you sure?
          </div>
        ) : (
          <div className="bb-dialog-warn">
            Are you sure? This request leaves your queue for good, and your bank will not be able
            to contribute to it afterwards.
          </div>
        )}

        <div className="bb-dialog-actions">
          <button type="button" className="bb-dialog-cancel" ref={cancelRef} onClick={onCancel}>
            Keep request
          </button>
          <button type="button" className="bb-dialog-confirm" onClick={onConfirm}>
            Decline request
          </button>
        </div>
      </div>
    </div>
  );
}

function stateColor(status) {
  if (status === "SUFFICIENT") return "#1E7F45";
  if (status === "LOW") return "#D98A1F";
  return "#C8102E";
}

function Stat({ tone, icon, figure, label, note }) {
  return (
    <div className="bb-stat">
      <div>
        <div className="bb-stat-figure">{figure}</div>
        <div className="bb-stat-label">{label}</div>
        <div className="bb-stat-note">{note}</div>
      </div>
      <div className={`bb-stat-icon bb-i-${tone}`}>{icon}</div>
    </div>
  );
}

function GridIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>); }
function DropletOutlineIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z"/></svg>); }
function TruckIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M2 7h11v10H2zM13 10h4.5l3.5 3.5V17h-8"/><circle cx="6.5" cy="18.5" r="1.8"/><circle cx="17" cy="18.5" r="1.8"/></svg>); }
function UsersIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><circle cx="9" cy="8" r="3.5"/><path d="M2 20c0-3.6 3.1-5.6 7-5.6s7 2 7 5.6"/><path d="M17 8.5a3 3 0 1 0 0-.2M18 14.6c2.4.5 4 2.4 4 5.4"/></svg>); }
function ChartIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/></svg>); }
function GearIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><circle cx="12" cy="12" r="3.4"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9 17 7M7 17l-2.1 2.1"/></svg>); }
function LogoutIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/></svg>); }
function CalendarIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>); }
function DropletIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z"/></svg>); }
function GiftIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><rect x="3" y="9" width="18" height="12" rx="2"/><path d="M3 13h18M12 9v12M12 9S9.5 3 7 5.5 12 9 12 9zM12 9s2.5-6 5-3.5S12 9 12 9z"/></svg>); }
function HourglassIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><path d="M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"/></svg>); }
function UsersFillIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><circle cx="9" cy="8" r="3.4"/><circle cx="17" cy="9" r="2.7"/><path d="M2 20c0-3.6 3.1-5.8 7-5.8s7 2.2 7 5.8z"/><path d="M16.5 14.4c3 .3 5.5 2.3 5.5 5.6h-5.2c0-2-.4-3.9-.3-5.6z"/></svg>); }
/* The reject dialog's badge — the same warning triangle the Hospital Portal
   already uses for its "Emergency Cases" card, so the two screens agree. */
function AlertIcon() { return (<svg viewBox="0 0 24 24" fill="none" stroke="#FFF" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 3 2 20h20L12 3z"/><path d="M12 10v4M12 17.5v.01"/></svg>); }
