import React, { useCallback, useEffect, useState } from "react";
import { getInventory } from "../api/inventory.js";
import { getBankDashboard } from "../api/bloodBanks.js";
import { actOnRequest, getRequests } from "../api/requests.js";
import { formatHeaderDate, formatRelativeTime, initials } from "../lib/dates.js";

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
   Approve and Reject on a pending request, Fulfil on one this bank has
   approved. Fulfilling moves units out of this bank's stock, so the whole
   screen is reloaded afterwards — the inventory table and the "Available
   Units" card both change with it.
   ================================================================ */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };
const ICON_W = { fill: "none", stroke: "#FFF", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

/** SUFFICIENT/LOW/CRITICAL -> Sufficient/Low/Critical, matching the CSS class
 *  suffixes and badge text the mockup already defines. */
function titleCase(status) {
  return status.charAt(0) + status.slice(1).toLowerCase();
}

export default function BloodBankDashboard({ bloodBankId, user, onSignOut, onNavigate }) {
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
   * Drives one step of Business Rule 5's lifecycle from the panel.
   *
   * bloodBankId is passed through for the ADMIN case, where the backend needs to
   * be told which bank is acting; for BLOOD_BANK_STAFF it is ignored server-side
   * in favour of their own assigned bank, so sending it is harmless.
   *
   * A 409 here is an ordinary outcome, not a bug — the request was already taken
   * by the other bank, or the stock moved since approval — so the message is
   * shown against the row and the screen is reloaded to show the real state.
   */
  async function handleAction(requestId, action) {
    setActingOn(requestId);
    setActionError(null);
    try {
      await actOnRequest(requestId, action, bloodBankId);
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
        .bb-bell { position: relative; display: grid; place-items: center; }
        .bb-bell svg { width: clamp(16px, 2.3vh, 20px); height: clamp(16px, 2.3vh, 20px); }
        .bb-bell::after { content:''; position:absolute; top:0; right:0; width:7px; height:7px; border-radius:50%; background:var(--red); border:1.5px solid var(--page-bg); }
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
        .bb-b-approved { background: var(--green); }
        .bb-b-fulfilled { background: var(--navy); }
        /* Additive: REJECTED has no BloodRequest write path yet (a later phase), but
           the badge needs somewhere to go the day one exists — grouped with the
           other "stopped" state, Critical, rather than left undefined. */
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
        /* Fulfil is the step that actually moves units, so it carries the navy
           "settled" colour the Fulfilled badge already uses on this screen. */
        .bb-act--go { background: var(--navy); }
        .bb-act--go:hover { background: var(--navy-soft); }

        /* Wiring states — additive, same tokens, for real async data. */
        .bb-loading, .bb-error { flex: 1 1 auto; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; text-align: center; }
        .bb-error-msg { color: var(--red); font-weight: 600; font-size: clamp(11px, 1.6vh, 14px); }
        .bb-empty { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); padding: clamp(8px, 1.4vh, 14px) 2px; }
        .bb-item-error { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--red); font-weight: 600; line-height: 1.35; white-space: normal; }

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
          <button type="button" className="is-active"><GridIcon /> Dashboard</button>
          <button type="button" onClick={() => onNavigate("inventory")}><DropletOutlineIcon /> Blood Inventory</button>
          <button type="button"><TruckIcon /> Blood Logistics</button>
          <button type="button"><UsersIcon /> Donors</button>
          <button type="button"><ChartIcon /> Report</button>
          <button type="button"><GearIcon /> Settings</button>
        </nav>

        <button type="button" className="bb-logout" onClick={onSignOut}><LogoutIcon /> Logout</button>
      </aside>

      <main className="bb-main">
        <div className="bb-top">
          <div>
            <div className="bb-title">{dashboard?.bloodBankName ?? "Blood Bank Dashboard"}</div>
            <div className="bb-sub">Stock and request overview</div>
          </div>
          <div className="bb-top-right">
            <div className="bb-date"><CalendarIcon /> Today, {formatHeaderDate(today)}</div>
            <div className="bb-bell"><BellIcon /></div>
            <div className="bb-avatar">{initials(user.fullName)}</div>
          </div>
        </div>

        {loading && (
          <div className="bb-loading"><div className="bb-sub">Loading dashboard…</div></div>
        )}

        {!loading && error && (
          <div className="bb-error"><div className="bb-error-msg">{error}</div></div>
        )}

        {!loading && !error && dashboard && (
          <>
            <div className="bb-stats">
              <Stat tone="red" icon={<DropletIcon />} figure={dashboard.totalUnits} label="Available Units" note="Across all groups" />
              <Stat tone="green" icon={<GiftIcon />} figure={dashboard.donationsThisMonth} label="Donations" note="This month" />
              <Stat tone="amber" icon={<HourglassIcon />} figure={dashboard.pendingRequestsCount} label="Pending Requests" note="Needs approval" />
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
                  {requests.map((r) => {
                    const busy = actingOn === r.id;
                    // Only the bank that approved a request may fulfil it, so the
                    // button is not offered to anyone else — the backend refuses it
                    // regardless, but a button that always fails is worse than none.
                    const canFulfil = r.status === "APPROVED" && r.fulfilledByBloodBankId === bloodBankId;

                    return (
                      <div className="bb-item" key={r.id}>
                        <div className="bb-item-grp">{r.bloodType}</div>
                        <div className="bb-item-body">
                          <div className="bb-item-main">{r.unitsRequested} Unit{r.unitsRequested > 1 ? "s" : ""} of {r.bloodType} · {titleCase(r.urgencyLevel)}</div>
                          <div className="bb-item-sub">
                            {r.hospitalName} · {formatRelativeTime(r.createdAt)}
                            {r.patientReference ? ` · ${r.patientReference}` : ""}
                          </div>
                          {actionError?.requestId === r.id && (
                            <div className="bb-item-error">{actionError.message}</div>
                          )}
                        </div>
                        <span className={`bb-badge bb-b-${r.status.toLowerCase()}`}>{titleCase(r.status)}</span>

                        {r.status === "PENDING" && (
                          <>
                            <button type="button" className="bb-act" disabled={busy}
                                    onClick={() => handleAction(r.id, "APPROVE")}>
                              {busy ? "…" : "Approve"}
                            </button>
                            <button type="button" className="bb-act bb-act--ghost" disabled={busy}
                                    onClick={() => handleAction(r.id, "REJECT")}>
                              Reject
                            </button>
                          </>
                        )}

                        {canFulfil && (
                          <button type="button" className="bb-act bb-act--go" disabled={busy}
                                  onClick={() => handleAction(r.id, "FULFIL")}>
                            {busy ? "…" : "Fulfil"}
                          </button>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          </>
        )}
      </main>
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
function BellIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0"/></svg>); }
function DropletIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z"/></svg>); }
function GiftIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><rect x="3" y="9" width="18" height="12" rx="2"/><path d="M3 13h18M12 9v12M12 9S9.5 3 7 5.5 12 9 12 9zM12 9s2.5-6 5-3.5S12 9 12 9z"/></svg>); }
function HourglassIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><path d="M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"/></svg>); }
function UsersFillIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><circle cx="9" cy="8" r="3.4"/><circle cx="17" cy="9" r="2.7"/><path d="M2 20c0-3.6 3.1-5.8 7-5.8s7 2.2 7 5.8z"/><path d="M16.5 14.4c3 .3 5.5 2.3 5.5 5.6h-5.2c0-2-.4-3.9-.3-5.6z"/></svg>); }
