import React, { useState } from "react";

/* ================================================================
   BloodLink — Hospital Portal

   Covers the hospital-staff use cases from Chapters One & Three:
     • Submit Blood Request  (blood group, units, urgency, ward)
     • Track Request Status  (Pending / Approved / Fulfilled / Rejected)
     • Check live blood availability across Makeni blood banks

   Self-contained: inline SVG icons, no external assets.
   Locked to 100vh so it screenshots cleanly for the dissertation.
   ================================================================ */

const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-"];

const AVAILABILITY = [
  { group: "A+", units: 179, state: "ok" },
  { group: "A-", units: 42, state: "low" },
  { group: "B+", units: 130, state: "ok" },
  { group: "B-", units: 18, state: "critical" },
  { group: "O+", units: 141, state: "ok" },
  { group: "O-", units: 9, state: "critical" },
  { group: "AB+", units: 78, state: "low" },
  { group: "AB-", units: 55, state: "ok" },
];

const REQUESTS = [
  { group: "O-", units: 2, bank: "Makeni Regional Blood Bank", when: "8 mins ago", status: "Pending" },
  { group: "A+", units: 3, bank: "Holy Spirit Blood Bank", when: "1 hr ago", status: "Approved" },
  { group: "B+", units: 1, bank: "Makeni Regional Blood Bank", when: "3 hrs ago", status: "Fulfilled" },
  { group: "AB+", units: 2, bank: "Makeni Regional Blood Bank", when: "Yesterday", status: "Fulfilled" },
  { group: "O+", units: 4, bank: "Holy Spirit Blood Bank", when: "Yesterday", status: "Rejected" },
];

export default function HospitalPortal() {
  const [group, setGroup] = useState("O-");
  const [units, setUnits] = useState(2);
  const [urgency, setUrgency] = useState("Emergency");
  const [ward, setWard] = useState("");

  return (
    <div className="hp-root">
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');

        /* ===== THEME TOKENS — same palette as the other portals ===== */
        .hp-root {
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
          height: 100vh;
          min-height: 680px;
          overflow: hidden;
          display: flex;
          -webkit-font-smoothing: antialiased;
        }
        .hp-root *, .hp-root *::before, .hp-root *::after { box-sizing: border-box; }

        /* ===== SIDEBAR ===== */
        .hp-side {
          flex: 0 0 auto;
          width: clamp(190px, 17vw, 250px);
          background: var(--navy);
          display: flex;
          flex-direction: column;
          padding: clamp(14px, 2.4vh, 26px) clamp(10px, 1vw, 16px);
          gap: clamp(10px, 1.6vh, 18px);
        }
        .hp-brand { display: flex; align-items: center; gap: 9px; padding: 0 6px; }
        .hp-brand-name { font-size: clamp(14px, 2vh, 17px); font-weight: 800; letter-spacing: -.02em; line-height: 1; }
        .hp-brand-tag { font-size: clamp(4.6px, .72vh, 6px); letter-spacing: .14em; color: rgba(255,255,255,.7); margin-top: 3px; font-weight: 600; }

        .hp-nav { display: flex; flex-direction: column; gap: clamp(2px, .5vh, 5px); margin-top: clamp(4px, 1.2vh, 12px); }
        .hp-nav button {
          display: flex; align-items: center; gap: 11px;
          width: 100%; text-align: left;
          background: transparent; border: none; cursor: pointer;
          color: rgba(255,255,255,.86);
          font-family: inherit;
          font-size: clamp(11.5px, 1.72vh, 15px);
          font-weight: 600;
          padding: clamp(7px, 1.24vh, 12px) 12px;
          border-radius: 8px;
          transition: background .15s ease, color .15s ease;
        }
        .hp-nav button svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); flex: 0 0 auto; }
        .hp-nav button:hover { background: rgba(255,255,255,.08); color: #FFF; }
        .hp-nav button.is-active { background: var(--navy-soft); color: #FFF; }
        .hp-nav button:focus-visible, .hp-logout:focus-visible { outline: 2px solid #FFF; outline-offset: 2px; }

        .hp-logout {
          margin-top: auto;
          display: flex; align-items: center; gap: 11px;
          background: rgba(255,255,255,.07);
          border: none; cursor: pointer; color: #FFF;
          font-family: inherit; font-weight: 600;
          font-size: clamp(11.5px, 1.72vh, 15px);
          padding: clamp(8px, 1.3vh, 13px) 14px;
          border-radius: 8px;
        }
        .hp-logout:hover { background: rgba(255,255,255,.14); }
        .hp-logout svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); }

        /* ===== MAIN ===== */
        .hp-main {
          flex: 1 1 auto; min-width: 0;
          display: flex; flex-direction: column;
          padding: clamp(12px, 2.2vh, 24px) clamp(14px, 1.6vw, 28px);
          gap: clamp(9px, 1.6vh, 18px);
          overflow: hidden;
        }

        .hp-top { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
        .hp-title { font-size: clamp(17px, 2.9vh, 26px); font-weight: 800; letter-spacing: -.025em; }
        .hp-sub { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); margin-top: 2px; }
        .hp-top-right { display: flex; align-items: center; gap: clamp(10px, 1vw, 16px); }
        .hp-date {
          display: flex; align-items: center; gap: 8px;
          font-size: clamp(10px, 1.5vh, 13px); font-weight: 600; color: #3A3A3A;
        }
        .hp-date svg { width: clamp(14px, 2vh, 17px); height: clamp(14px, 2vh, 17px); }
        .hp-bell { position: relative; display: grid; place-items: center; }
        .hp-bell svg { width: clamp(16px, 2.3vh, 20px); height: clamp(16px, 2.3vh, 20px); }
        .hp-bell::after {
          content: ''; position: absolute; top: 0; right: 0;
          width: 7px; height: 7px; border-radius: 50%;
          background: var(--red); border: 1.5px solid var(--page-bg);
        }
        .hp-avatar {
          width: clamp(28px, 4.2vh, 38px); height: clamp(28px, 4.2vh, 38px);
          border-radius: 50%; background: var(--navy);
          display: grid; place-items: center; color: #FFF;
          font-weight: 700; font-size: clamp(10px, 1.5vh, 13px);
          border: 2px solid #FFF; box-shadow: var(--shadow);
        }

        /* ===== STAT CARDS ===== */
        .hp-stats { flex: 0 0 auto; display: grid; grid-template-columns: repeat(4, 1fr); gap: clamp(9px, 1.1vw, 18px); }
        .hp-stat {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(9px, 1.5vh, 16px) clamp(11px, 1.1vw, 18px);
          display: flex; align-items: center; justify-content: space-between; gap: 10px;
        }
        .hp-stat-figure { font-size: clamp(18px, 3vh, 28px); font-weight: 800; line-height: 1.05; letter-spacing: -.02em; }
        .hp-stat-label { font-size: clamp(10.5px, 1.6vh, 14px); font-weight: 700; margin-top: 1px; }
        .hp-stat-note { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); margin-top: 2px; }
        .hp-stat-icon {
          flex: 0 0 auto;
          width: clamp(30px, 4.6vh, 44px); height: clamp(30px, 4.6vh, 44px);
          border-radius: 50%; display: grid; place-items: center;
        }
        .hp-stat-icon svg { width: 52%; height: 52%; }
        .hp-i-navy  { background: var(--navy); }
        .hp-i-amber { background: var(--amber); }
        .hp-i-green { background: var(--green); }
        .hp-i-red   { background: var(--red); }

        /* ===== PANELS ===== */
        .hp-panels {
          flex: 1 1 auto; min-height: 0;
          display: grid; grid-template-columns: 1fr 1.25fr;
          gap: clamp(9px, 1.1vw, 18px);
        }
        .hp-col { min-height: 0; display: flex; flex-direction: column; gap: clamp(9px, 1.1vh, 16px); }
        .hp-panel {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(11px, 1.7vh, 18px) clamp(12px, 1.2vw, 20px);
          display: flex; flex-direction: column; min-height: 0;
        }
        .hp-panel-head { flex: 0 0 auto; display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: clamp(7px, 1.2vh, 13px); }
        .hp-panel-title { font-size: clamp(12px, 1.85vh, 16px); font-weight: 700; letter-spacing: -.01em; }
        .hp-panel-link { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: var(--red); cursor: pointer; }

        /* ===== FORM ===== */
        .hp-form { display: flex; flex-direction: column; gap: clamp(7px, 1.25vh, 13px); min-height: 0; }
        .hp-field { display: flex; flex-direction: column; gap: 4px; }
        .hp-label { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: #444; }
        .hp-input, .hp-select {
          font-family: inherit;
          font-size: clamp(11px, 1.6vh, 14px);
          font-weight: 500; color: var(--ink);
          padding: clamp(7px, 1.15vh, 11px) 12px;
          border: 1.5px solid var(--line); border-radius: 7px;
          background: #FBFBFC; width: 100%;
        }
        .hp-input:focus, .hp-select:focus { outline: none; border-color: var(--navy); background: #FFF; }
        .hp-row { display: grid; grid-template-columns: 1fr 1fr; gap: clamp(8px, .8vw, 12px); }

        .hp-urgency { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
        .hp-urgency button {
          font-family: inherit; cursor: pointer;
          font-size: clamp(9.5px, 1.38vh, 12.5px); font-weight: 700;
          padding: clamp(6px, 1.1vh, 10px) 4px;
          border-radius: 7px; border: 1.5px solid var(--line);
          background: #FBFBFC; color: #555;
          transition: all .15s ease;
        }
        .hp-urgency button:hover { border-color: #C9CDD3; }
        .hp-urgency button.on-routine   { background: #EAF4EE; border-color: var(--green); color: var(--green); }
        .hp-urgency button.on-urgent    { background: #FBF1E3; border-color: var(--amber); color: #A8690F; }
        .hp-urgency button.on-emergency { background: #FBE9EC; border-color: var(--red);   color: var(--red); }

        .hp-submit {
          margin-top: auto;
          display: flex; align-items: center; justify-content: center; gap: 9px;
          background: var(--red); color: #FFF; border: none; border-radius: 8px;
          font-family: inherit; font-weight: 700;
          font-size: clamp(12px, 1.8vh, 15px);
          padding: clamp(9px, 1.5vh, 14px);
          cursor: pointer; transition: background .15s ease;
        }
        .hp-submit:hover { background: var(--red-dark); }
        .hp-submit svg { width: clamp(14px, 2vh, 17px); height: clamp(14px, 2vh, 17px); }

        /* ===== AVAILABILITY GRID ===== */
        .hp-avail { display: grid; grid-template-columns: repeat(4, 1fr); gap: clamp(6px, .7vw, 10px); }
        .hp-avail-tile {
          border: 1.5px solid var(--line); border-radius: 8px;
          padding: clamp(6px, 1.05vh, 10px) 8px;
          display: flex; flex-direction: column; gap: 1px;
          position: relative;
        }
        .hp-avail-group { font-size: clamp(12px, 1.85vh, 16px); font-weight: 800; letter-spacing: -.02em; }
        .hp-avail-units { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); font-weight: 500; }
        .hp-dot { position: absolute; top: clamp(6px, 1.05vh, 10px); right: 8px; width: 8px; height: 8px; border-radius: 50%; }
        .hp-dot--ok { background: var(--green); }
        .hp-dot--low { background: var(--amber); }
        .hp-dot--critical { background: var(--red); }

        .hp-legend { display: flex; gap: clamp(8px, 1vw, 16px); margin-top: clamp(7px, 1.15vh, 12px); flex-wrap: wrap; }
        .hp-legend span { display: flex; align-items: center; gap: 5px; font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); font-weight: 500; }
        .hp-legend i { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }

        /* ===== REQUEST LIST ===== */
        .hp-list { flex: 1 1 auto; min-height: 0; overflow: hidden; display: flex; flex-direction: column; gap: clamp(4px, .75vh, 8px); }
        .hp-item {
          display: flex; align-items: center; gap: clamp(8px, .8vw, 12px);
          padding: clamp(5px, .95vh, 9px) clamp(7px, .7vw, 11px);
          border: 1px solid var(--line); border-radius: 8px;
        }
        .hp-item-group {
          flex: 0 0 auto;
          width: clamp(28px, 4.2vh, 38px); height: clamp(28px, 4.2vh, 38px);
          border-radius: 50%; background: #FBE9EC; color: var(--red);
          display: grid; place-items: center;
          font-weight: 800; font-size: clamp(9.5px, 1.4vh, 12.5px); letter-spacing: -.02em;
        }
        .hp-item-body { flex: 1 1 auto; min-width: 0; }
        .hp-item-main { font-size: clamp(10.5px, 1.55vh, 13.5px); font-weight: 700; }
        .hp-item-sub {
          font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); margin-top: 1px;
          white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
        }
        .hp-badge {
          flex: 0 0 auto;
          font-size: clamp(8.5px, 1.18vh, 11px); font-weight: 700;
          padding: 4px clamp(7px, .7vw, 11px); border-radius: 20px; color: #FFF;
        }
        .hp-b-pending   { background: var(--amber); }
        .hp-b-approved  { background: var(--green); }
        .hp-b-fulfilled { background: var(--navy); }
        .hp-b-rejected  { background: var(--red); }

        @media (prefers-reduced-motion: reduce) { .hp-root * { transition: none !important; } }
      `}</style>

      {/* ================= SIDEBAR ================= */}
      <aside className="hp-side">
        <div className="hp-brand">
          <svg width="28" height="32" viewBox="0 0 34 38" aria-hidden="true">
            <path d="M17 2s13 14 13 22a13 13 0 0 1-26 0C4 16 17 2 17 2z" fill="#C8102E" />
            <path d="M7 24h5l2.5-6 3.5 12 3-8 2 2h5" fill="none" stroke="#FFF"
                  strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <div>
            <div className="hp-brand-name">
              <span style={{ color: "#C8102E" }}>Blood</span>
              <span style={{ color: "#FFFFFF" }}>Link</span>
            </div>
            <div className="hp-brand-tag">GIVE BLOOD · SAVE LIVES</div>
          </div>
        </div>

        <nav className="hp-nav">
          <button type="button" className="is-active"><GridIcon /> Dashboard</button>
          <button type="button"><PlusIcon /> New Request</button>
          <button type="button"><ClockIcon /> Request History</button>
          <button type="button"><DropletIcon /> Blood Availability</button>
          <button type="button"><ChartIcon /> Report</button>
          <button type="button"><GearIcon /> Settings</button>
        </nav>

        <button type="button" className="hp-logout"><LogoutIcon /> Logout</button>
      </aside>

      {/* ================= MAIN ================= */}
      <main className="hp-main">
        <div className="hp-top">
          <div>
            <div className="hp-title">Makeni Regional Hospital</div>
            <div className="hp-sub">Blood request desk · Emergency &amp; Maternity Unit</div>
          </div>
          <div className="hp-top-right">
            <div className="hp-date"><CalendarIcon /> Today, August 28 2026</div>
            <div className="hp-bell"><BellIcon /></div>
            <div className="hp-avatar">MR</div>
          </div>
        </div>

        {/* ---- stat cards ---- */}
        <div className="hp-stats">
          <Stat tone="navy" icon={<FileIcon />} figure="48" label="Total Requests" note="This month" />
          <Stat tone="amber" icon={<HourglassIcon />} figure="4" label="Pending Approval" note="Awaiting blood bank" />
          <Stat tone="green" icon={<CheckIcon />} figure="41" label="Fulfilled" note="Units received" />
          <Stat tone="red" icon={<AlertIcon />} figure="3" label="Emergency Cases" note="Highest priority" />
        </div>

        {/* ---- panels ---- */}
        <div className="hp-panels">
          {/* left: request form */}
          <div className="hp-col">
            <div className="hp-panel" style={{ flex: "1 1 auto" }}>
              <div className="hp-panel-head">
                <div className="hp-panel-title">Submit Blood Request</div>
              </div>

              <div className="hp-form">
                <div className="hp-row">
                  <div className="hp-field">
                    <label className="hp-label" htmlFor="hp-group">Blood Group</label>
                    <select id="hp-group" className="hp-select" value={group}
                            onChange={(e) => setGroup(e.target.value)}>
                      {BLOOD_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}
                    </select>
                  </div>
                  <div className="hp-field">
                    <label className="hp-label" htmlFor="hp-units">Units Required</label>
                    <input id="hp-units" className="hp-input" type="number" min="1" value={units}
                           onChange={(e) => setUnits(e.target.value)} />
                  </div>
                </div>

                <div className="hp-field">
                  <span className="hp-label">Urgency Level</span>
                  <div className="hp-urgency">
                    {["Routine", "Urgent", "Emergency"].map((u) => (
                      <button key={u} type="button"
                              className={urgency === u ? `on-${u.toLowerCase()}` : ""}
                              onClick={() => setUrgency(u)}>
                        {u}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="hp-field">
                  <label className="hp-label" htmlFor="hp-ward">Patient Ward / Reference</label>
                  <input id="hp-ward" className="hp-input" type="text"
                         placeholder="e.g. Maternity Ward — Bed 12"
                         value={ward} onChange={(e) => setWard(e.target.value)} />
                </div>

                <button type="button" className="hp-submit"><SendIcon /> Send Request</button>
              </div>
            </div>
          </div>

          {/* right: availability + tracking */}
          <div className="hp-col">
            <div className="hp-panel" style={{ flex: "0 0 auto" }}>
              <div className="hp-panel-head">
                <div className="hp-panel-title">Live Blood Availability</div>
                <div className="hp-panel-link">Makeni blood banks</div>
              </div>
              <div className="hp-avail">
                {AVAILABILITY.map((a) => (
                  <div className="hp-avail-tile" key={a.group}>
                    <span className={`hp-dot hp-dot--${a.state}`} />
                    <div className="hp-avail-group">{a.group}</div>
                    <div className="hp-avail-units">{a.units} units</div>
                  </div>
                ))}
              </div>
              <div className="hp-legend">
                <span><i style={{ background: "#1E7F45" }} /> Sufficient</span>
                <span><i style={{ background: "#D98A1F" }} /> Low</span>
                <span><i style={{ background: "#C8102E" }} /> Critical</span>
              </div>
            </div>

            <div className="hp-panel" style={{ flex: "1 1 auto" }}>
              <div className="hp-panel-head">
                <div className="hp-panel-title">My Recent Requests</div>
                <div className="hp-panel-link">View All</div>
              </div>
              <div className="hp-list">
                {REQUESTS.map((r, i) => (
                  <div className="hp-item" key={i}>
                    <div className="hp-item-group">{r.group}</div>
                    <div className="hp-item-body">
                      <div className="hp-item-main">{r.group} · {r.units} Unit{r.units > 1 ? "s" : ""}</div>
                      <div className="hp-item-sub">{r.bank} · {r.when}</div>
                    </div>
                    <div className={`hp-badge hp-b-${r.status.toLowerCase()}`}>{r.status}</div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}

/* ================= PIECES ================= */
function Stat({ tone, icon, figure, label, note }) {
  return (
    <div className="hp-stat">
      <div>
        <div className="hp-stat-figure">{figure}</div>
        <div className="hp-stat-label">{label}</div>
        <div className="hp-stat-note">{note}</div>
      </div>
      <div className={`hp-stat-icon hp-i-${tone}`}>{icon}</div>
    </div>
  );
}

/* ================= ICONS ================= */
const S = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

function GridIcon() { return (<svg viewBox="0 0 24 24" {...S}><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>); }
function PlusIcon() { return (<svg viewBox="0 0 24 24" {...S}><circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8"/></svg>); }
function ClockIcon() { return (<svg viewBox="0 0 24 24" {...S}><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg>); }
function DropletIcon() { return (<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z"/></svg>); }
function ChartIcon() { return (<svg viewBox="0 0 24 24" {...S}><path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/></svg>); }
function GearIcon() { return (<svg viewBox="0 0 24 24" {...S}><circle cx="12" cy="12" r="3.2"/><path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 1 1-4 0v-.1A1.6 1.6 0 0 0 7.5 19.4a1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0-1.1-2.7H1a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 2.6 7.5a1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 2.7-1.1V1a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.1 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7H23a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1.6z" transform="scale(0.86) translate(2 2)"/></svg>); }
function LogoutIcon() { return (<svg viewBox="0 0 24 24" {...S}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/></svg>); }
function CalendarIcon() { return (<svg viewBox="0 0 24 24" {...S}><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>); }
function BellIcon() { return (<svg viewBox="0 0 24 24" {...S}><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0"/></svg>); }
function FileIcon() { return (<svg viewBox="0 0 24 24" fill="none" stroke="#FFF" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M9 14h6M9 18h4"/></svg>); }
function HourglassIcon() { return (<svg viewBox="0 0 24 24" fill="none" stroke="#FFF" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"/></svg>); }
function CheckIcon() { return (<svg viewBox="0 0 24 24" fill="none" stroke="#FFF" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round"><path d="M4 12.5 9.5 18 20 7"/></svg>); }
function AlertIcon() { return (<svg viewBox="0 0 24 24" fill="none" stroke="#FFF" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 3 2 20h20L12 3z"/><path d="M12 10v4M12 17.5v.01"/></svg>); }
function SendIcon() { return (<svg viewBox="0 0 24 24" fill="none" stroke="#FFF" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M22 2 11 13M22 2l-7 20-4-9-9-4 20-7z"/></svg>); }
