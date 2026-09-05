import React from "react";

/* ================================================================
   BloodLink — Blood Bank: Blood Inventory (detail view)

   Second view of the blood bank portal: stock composition by group,
   expiry exposure, and a per-group inventory table.

   Figures match BloodBankDashboard.jsx (405 units total, 30 expiring)
   so the two views of the same facility agree.
   ================================================================ */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };
const ICON_W = { fill: "none", stroke: "#FFF", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

const STOCK = [
  { group: "A+",  units: 110, expiring: 12, state: "Sufficient", updated: "28 Aug 2026, 09:40" },
  { group: "A-",  units: 26,  expiring: 3,  state: "Low",        updated: "28 Aug 2026, 08:15" },
  { group: "B+",  units: 82,  expiring: 8,  state: "Sufficient", updated: "27 Aug 2026, 16:20" },
  { group: "B-",  units: 11,  expiring: 1,  state: "Critical",   updated: "28 Aug 2026, 07:55" },
  { group: "O+",  units: 89,  expiring: 4,  state: "Sufficient", updated: "28 Aug 2026, 10:05" },
  { group: "O-",  units: 5,   expiring: 0,  state: "Critical",   updated: "28 Aug 2026, 10:30" },
  { group: "AB+", units: 48,  expiring: 2,  state: "Low",        updated: "26 Aug 2026, 14:10" },
  { group: "AB-", units: 34,  expiring: 0,  state: "Low",        updated: "27 Aug 2026, 11:45" },
];

const COMPOSITION = [
  { label: "Sufficient", units: 281, color: "#1E7F45" },
  { label: "Low", units: 108, color: "#D98A1F" },
  { label: "Critical", units: 16, color: "#C8102E" },
];

export default function BloodBankInventory() {
  const total = STOCK.reduce((s, r) => s + r.units, 0);
  const expiring = STOCK.reduce((s, r) => s + r.expiring, 0);
  const critical = STOCK.filter((r) => r.state === "Critical").length;

  return (
    <div className="bi-root">
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');

        .bi-root {
          --navy:#1C3A5E; --navy-soft:#2A4E77; --red:#C8102E; --red-dark:#A50E26;
          --green:#1E7F45; --amber:#D98A1F; --page-bg:#F1F2F4; --card-bg:#FFFFFF;
          --ink:#111111; --ink-muted:#7A7A7A; --line:#E6E8EB;
          --radius:10px; --shadow:0 2px 10px rgba(0,0,0,0.07);

          font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
          color: var(--ink); background: var(--page-bg);
          height: 100vh; min-height: 680px; overflow: hidden; display: flex;
          -webkit-font-smoothing: antialiased;
        }
        .bi-root *, .bi-root *::before, .bi-root *::after { box-sizing: border-box; }

        .bi-side {
          flex: 0 0 auto; width: clamp(190px, 17vw, 250px); background: var(--navy);
          display: flex; flex-direction: column;
          padding: clamp(14px, 2.4vh, 26px) clamp(10px, 1vw, 16px); gap: clamp(10px, 1.6vh, 18px);
        }
        .bi-brand { display: flex; align-items: center; gap: 9px; padding: 0 6px; }
        .bi-brand-name { font-size: clamp(14px, 2vh, 17px); font-weight: 800; letter-spacing: -.02em; line-height: 1; }
        .bi-brand-tag { font-size: clamp(4.6px, .72vh, 6px); letter-spacing: .14em; color: rgba(255,255,255,.7); margin-top: 3px; font-weight: 600; }
        .bi-role { margin: clamp(2px, .8vh, 8px) 6px 0; font-size: clamp(8px, 1.1vh, 10px); font-weight: 700; letter-spacing: .1em; color: rgba(255,255,255,.5); }
        .bi-nav { display: flex; flex-direction: column; gap: clamp(2px, .5vh, 5px); }
        .bi-nav button {
          display: flex; align-items: center; gap: 11px; width: 100%; text-align: left;
          background: transparent; border: none; cursor: pointer; color: rgba(255,255,255,.86);
          font-family: inherit; font-size: clamp(11.5px, 1.72vh, 15px); font-weight: 600;
          padding: clamp(7px, 1.24vh, 12px) 12px; border-radius: 8px; transition: background .15s ease;
        }
        .bi-nav button svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); flex: 0 0 auto; }
        .bi-nav button:hover { background: rgba(255,255,255,.08); color: #FFF; }
        .bi-nav button.is-active { background: var(--navy-soft); color: #FFF; }
        .bi-logout {
          margin-top: auto; display: flex; align-items: center; gap: 11px;
          background: rgba(255,255,255,.07); border: none; cursor: pointer; color: #FFF;
          font-family: inherit; font-weight: 600; font-size: clamp(11.5px, 1.72vh, 15px);
          padding: clamp(8px, 1.3vh, 13px) 14px; border-radius: 8px;
        }
        .bi-logout svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); }

        .bi-main {
          flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column;
          padding: clamp(12px, 2.2vh, 24px) clamp(14px, 1.6vw, 28px);
          gap: clamp(9px, 1.6vh, 18px); overflow: hidden;
        }
        .bi-top { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
        .bi-title { font-size: clamp(17px, 2.9vh, 26px); font-weight: 800; letter-spacing: -.025em; }
        .bi-sub { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); margin-top: 2px; }
        .bi-top-right { display: flex; align-items: center; gap: clamp(10px, 1vw, 16px); }
        .bi-date { display: flex; align-items: center; gap: 8px; font-size: clamp(10px, 1.5vh, 13px); font-weight: 600; color: #3A3A3A; }
        .bi-date svg { width: clamp(14px, 2vh, 17px); height: clamp(14px, 2vh, 17px); }
        .bi-addbtn {
          background: var(--red); color: #FFF; border: none; border-radius: 7px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(10.5px, 1.5vh, 13px);
          padding: clamp(8px, 1.25vh, 11px) clamp(12px, 1.2vw, 18px);
          display: inline-flex; align-items: center; gap: 7px;
        }
        .bi-addbtn:hover { background: var(--red-dark); }
        .bi-addbtn svg { width: clamp(13px, 1.8vh, 16px); height: clamp(13px, 1.8vh, 16px); }

        .bi-stats { flex: 0 0 auto; display: grid; grid-template-columns: repeat(4, 1fr); gap: clamp(9px, 1.1vw, 18px); }
        .bi-stat {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(9px, 1.5vh, 16px) clamp(11px, 1.1vw, 18px);
          display: flex; align-items: center; justify-content: space-between; gap: 10px;
        }
        .bi-stat-figure { font-size: clamp(18px, 3vh, 28px); font-weight: 800; line-height: 1.05; letter-spacing: -.02em; }
        .bi-stat-label { font-size: clamp(10.5px, 1.6vh, 14px); font-weight: 700; margin-top: 1px; }
        .bi-stat-note { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); margin-top: 2px; }
        .bi-stat-icon { flex: 0 0 auto; width: clamp(30px, 4.6vh, 44px); height: clamp(30px, 4.6vh, 44px); border-radius: 50%; display: grid; place-items: center; }
        .bi-stat-icon svg { width: 52%; height: 52%; }
        .bi-i-red{background:var(--red);} .bi-i-amber{background:var(--amber);}
        .bi-i-green{background:var(--green);} .bi-i-navy{background:var(--navy);}

        .bi-charts { flex: 0 0 auto; display: grid; grid-template-columns: 1.5fr 1fr; gap: clamp(9px, 1.1vw, 18px); }
        .bi-panel {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(11px, 1.7vh, 18px) clamp(12px, 1.2vw, 20px);
          display: flex; flex-direction: column; min-height: 0;
        }
        .bi-panel-head { flex: 0 0 auto; display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: clamp(6px, 1vh, 12px); }
        .bi-panel-title { font-size: clamp(12px, 1.85vh, 16px); font-weight: 700; letter-spacing: -.01em; }
        .bi-panel-link { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: var(--red); }
        .bi-chart-wrap { flex: 1 1 auto; min-height: 0; display: flex; align-items: center; }
        .bi-chart-wrap svg { width: 100%; height: auto; max-height: clamp(110px, 18vh, 165px); }

        .bi-donut-row { display: flex; align-items: center; gap: clamp(10px, 1.2vw, 20px); width: 100%; }
        .bi-donut-row svg { flex: 0 0 auto; width: clamp(90px, 14vh, 132px); height: clamp(90px, 14vh, 132px); }
        .bi-legend { display: flex; flex-direction: column; gap: clamp(4px, .85vh, 9px); }
        .bi-legend div { display: flex; align-items: center; gap: 7px; font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; }
        .bi-legend i { width: 9px; height: 9px; border-radius: 2px; flex: 0 0 auto; }
        .bi-legend em { font-style: normal; color: var(--ink-muted); font-weight: 500; }

        .bi-tablepanel { flex: 1 1 auto; min-height: 0; }
        .bi-table { width: 100%; border-collapse: collapse; }
        .bi-table th {
          text-align: left; font-size: clamp(8.5px, 1.22vh, 11px); font-weight: 700; color: var(--ink-muted);
          text-transform: uppercase; letter-spacing: .05em;
          padding: 0 8px clamp(5px, .9vh, 9px); border-bottom: 1.5px solid var(--line);
        }
        .bi-table td { font-size: clamp(10px, 1.48vh, 13px); padding: clamp(4px, .85vh, 9px) 8px; border-bottom: 1px solid var(--line); white-space: nowrap; }
        .bi-table tr:last-child td { border-bottom: none; }
        .bi-grp { font-weight: 800; letter-spacing: -.02em; font-size: clamp(11px, 1.6vh, 14px); }
        .bi-badge { font-size: clamp(8.5px, 1.18vh, 11px); font-weight: 700; padding: 3px clamp(7px, .7vw, 11px); border-radius: 20px; color: #FFF; }
        .bi-b-sufficient { background: var(--green); }
        .bi-b-low { background: var(--amber); }
        .bi-b-critical { background: var(--red); }
        .bi-muted { color: var(--ink-muted); }

        @media (prefers-reduced-motion: reduce) { .bi-root * { transition: none !important; } }
      `}</style>

      <aside className="bi-side">
        <div className="bi-brand">
          <svg width="28" height="32" viewBox="0 0 34 38" aria-hidden="true">
            <path d="M17 2s13 14 13 22a13 13 0 0 1-26 0C4 16 17 2 17 2z" fill="#C8102E" />
            <path d="M7 24h5l2.5-6 3.5 12 3-8 2 2h5" fill="none" stroke="#FFF" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <div>
            <div className="bi-brand-name">
              <span style={{ color: "#C8102E" }}>Blood</span><span style={{ color: "#FFFFFF" }}>Link</span>
            </div>
            <div className="bi-brand-tag">GIVE BLOOD · SAVE LIVES</div>
          </div>
        </div>

        <div className="bi-role">BLOOD BANK STAFF</div>

        <nav className="bi-nav">
          <button type="button"><GridIcon /> Dashboard</button>
          <button type="button" className="is-active"><DropletOutlineIcon /> Blood Inventory</button>
          <button type="button"><TruckIcon /> Blood Logistics</button>
          <button type="button"><UsersIcon /> Donors</button>
          <button type="button"><ChartIcon /> Report</button>
          <button type="button"><GearIcon /> Settings</button>
        </nav>

        <button type="button" className="bi-logout"><LogoutIcon /> Logout</button>
      </aside>

      <main className="bi-main">
        <div className="bi-top">
          <div>
            <div className="bi-title">Blood Inventory</div>
            <div className="bi-sub">Makeni Regional Blood Bank</div>
          </div>
          <div className="bi-top-right">
            <div className="bi-date"><CalendarIcon /> Today, August 28 2026</div>
            <button type="button" className="bi-addbtn"><PlusIcon /> Add New Batch</button>
          </div>
        </div>

        <div className="bi-stats">
          <Stat tone="red" icon={<DropletIcon />} figure={total} label="Total Units" note="All blood groups" />
          <Stat tone="amber" icon={<ClockIcon />} figure={expiring} label="Expiring Soon" note="Within 4 weeks" />
          <Stat tone="green" icon={<SwapIcon />} figure="148" label="Units Issued" note="This month" />
          <Stat tone="navy" icon={<AlertIcon />} figure={critical} label="Critical Groups" note="Below safe level" />
        </div>

        <div className="bi-charts">
          <div className="bi-panel">
            <div className="bi-panel-head">
              <div className="bi-panel-title">Units by Blood Group</div>
              <div className="bi-panel-link">Live stock</div>
            </div>
            <div className="bi-chart-wrap"><GroupChart /></div>
          </div>

          <div className="bi-panel">
            <div className="bi-panel-head">
              <div className="bi-panel-title">Stock Composition</div>
              <div className="bi-panel-link">{total} units</div>
            </div>
            <div className="bi-chart-wrap">
              <div className="bi-donut-row">
                <DonutChart total={total} />
                <div className="bi-legend">
                  {COMPOSITION.map((c) => (
                    <div key={c.label}>
                      <i style={{ background: c.color }} />
                      {c.label} <em>{c.units}</em>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>

        <div className="bi-panel bi-tablepanel">
          <div className="bi-panel-head">
            <div className="bi-panel-title">Blood Inventory Details</div>
            <div className="bi-panel-link">Export</div>
          </div>
          <table className="bi-table">
            <thead>
              <tr>
                <th style={{ width: "13%" }}>Blood Group</th>
                <th style={{ width: "17%" }}>Available Units</th>
                <th style={{ width: "17%" }}>Expiring Soon</th>
                <th style={{ width: "23%" }}>Status</th>
                <th style={{ width: "30%" }}>Last Updated</th>
              </tr>
            </thead>
            <tbody>
              {STOCK.map((s) => (
                <tr key={s.group}>
                  <td className="bi-grp">{s.group}</td>
                  <td>{s.units}</td>
                  <td className={s.expiring === 0 ? "bi-muted" : ""}>{s.expiring === 0 ? "—" : s.expiring}</td>
                  <td><span className={`bi-badge bi-b-${s.state.toLowerCase()}`}>{s.state}</span></td>
                  <td className="bi-muted">{s.updated}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </main>
    </div>
  );
}

function Stat({ tone, icon, figure, label, note }) {
  return (
    <div className="bi-stat">
      <div>
        <div className="bi-stat-figure">{figure}</div>
        <div className="bi-stat-label">{label}</div>
        <div className="bi-stat-note">{note}</div>
      </div>
      <div className={`bi-stat-icon bi-i-${tone}`}>{icon}</div>
    </div>
  );
}

function GroupChart() {
  const CW = 430, CH = 170, PAD_L = 32, PAD_B = 24, PAD_T = 14;
  const max = 120;
  const plotH = CH - PAD_B - PAD_T;
  const plotW = CW - PAD_L - 10;
  const slot = plotW / STOCK.length;
  const barW = Math.min(30, slot * 0.54);
  const ticks = [0, 30, 60, 90, 120];

  return (
    <svg viewBox={`0 0 ${CW} ${CH}`} role="img" aria-label="Bar chart of available units by blood group">
      {ticks.map((t) => {
        const y = PAD_T + plotH - (t / max) * plotH;
        return (
          <g key={t}>
            <line x1={PAD_L} y1={y} x2={CW - 10} y2={y} stroke="#EEF0F2" strokeWidth="1" />
            <text x={PAD_L - 7} y={y + 3.5} textAnchor="end" fontSize="8.5" fill="#9A9A9A" fontFamily="Inter, Arial, sans-serif">{t}</text>
          </g>
        );
      })}
      {STOCK.map((d, i) => {
        const h = (d.units / max) * plotH;
        const x = PAD_L + slot * i + (slot - barW) / 2;
        const y = PAD_T + plotH - h;
        const fill = d.state === "Sufficient" ? "#1E7F45" : d.state === "Low" ? "#D98A1F" : "#C8102E";
        return (
          <g key={d.group}>
            <rect x={x} y={y} width={barW} height={h} rx="3" fill={fill} />
            <text x={x + barW / 2} y={y - 5} textAnchor="middle" fontSize="9" fontWeight="700" fill="#333" fontFamily="Inter, Arial, sans-serif">{d.units}</text>
            <text x={x + barW / 2} y={CH - 8} textAnchor="middle" fontSize="9.5" fontWeight="600" fill="#666" fontFamily="Inter, Arial, sans-serif">{d.group}</text>
          </g>
        );
      })}
      <line x1={PAD_L} y1={PAD_T + plotH} x2={CW - 10} y2={PAD_T + plotH} stroke="#D8DBDF" strokeWidth="1.2" />
    </svg>
  );
}

function DonutChart({ total }) {
  const R = 50, C = 2 * Math.PI * R;
  let offset = 0;
  return (
    <svg viewBox="0 0 132 132" role="img" aria-label="Donut chart of stock composition by status">
      <g transform="translate(66 66) rotate(-90)">
        <circle r={R} fill="none" stroke="#F0F1F3" strokeWidth="19" />
        {COMPOSITION.map((c) => {
          const len = (c.units / total) * C;
          const seg = (
            <circle key={c.label} r={R} fill="none" stroke={c.color} strokeWidth="19"
                    strokeDasharray={`${len} ${C - len}`} strokeDashoffset={-offset} />
          );
          offset += len;
          return seg;
        })}
      </g>
      <text x="66" y="63" textAnchor="middle" fontSize="20" fontWeight="800" fill="#111" fontFamily="Inter, Arial, sans-serif">{total}</text>
      <text x="66" y="78" textAnchor="middle" fontSize="9" fill="#8A8A8A" fontFamily="Inter, Arial, sans-serif">Units</text>
    </svg>
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
function PlusIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W} strokeWidth="2.4"><path d="M12 5v14M5 12h14"/></svg>); }
function DropletIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z"/></svg>); }
function ClockIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg>); }
function SwapIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><path d="M3 8h14l-3.5-3.5M21 16H7l3.5 3.5"/></svg>); }
function AlertIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W} strokeWidth="2.2"><path d="M12 3 2 20h20L12 3z"/><path d="M12 10v4M12 17.5v.01"/></svg>); }
