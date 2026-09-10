import React from "react";

/* ================================================================
   BloodLink — Admin Console (System Administrator)

   Covers the administrator use cases from Chapters One & Three:
     • Manage User Accounts  (all four roles, activate / suspend)
     • View Reports & Analytics  (donation trends, request outcomes,
       stock levels across Makeni facilities)

   Self-contained: inline SVG charts and icons, no chart library.
   Locked to 100vh so it screenshots cleanly for the dissertation.
   ================================================================ */

/* Shared SVG stroke presets (declared before any component uses them) */
const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };
const ICON_W = { fill: "none", stroke: "#FFF", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

const TRENDS = [
  { month: "Mar", donations: 120 },
  { month: "Apr", donations: 145 },
  { month: "May", donations: 132 },
  { month: "Jun", donations: 168 },
  { month: "Jul", donations: 154 },
  { month: "Aug", donations: 181 },
];

const OUTCOMES = [
  { label: "Fulfilled", pct: 64, color: "#1E7F45" },
  { label: "Approved", pct: 14, color: "#1C3A5E" },
  { label: "Pending", pct: 13, color: "#D98A1F" },
  { label: "Rejected", pct: 9, color: "#C8102E" },
];

const USERS = [
  { name: "Rahim Barrie", role: "Donor", facility: "—", status: "Active", seen: "12 mins ago" },
  { name: "Dr. Aminata Sesay", role: "Hospital Staff", facility: "Makeni Regional Hospital", status: "Active", seen: "1 hr ago" },
  { name: "Mohamed Kamara", role: "Blood Bank Staff", facility: "Makeni Regional Blood Bank", status: "Active", seen: "2 hrs ago" },
  { name: "Ramatulai Jalloh", role: "Donor", facility: "—", status: "Active", seen: "4 hrs ago" },
  { name: "Fatmata Conteh", role: "Hospital Staff", facility: "Holy Spirit Hospital", status: "Suspended", seen: "6 days ago" },
  { name: "Dr. Sorie Bangura", role: "Hospital Staff", facility: "Makeni Regional Hospital", status: "Active", seen: "Yesterday" },
  { name: "Isata Koroma", role: "Blood Bank Staff", facility: "Makeni Regional Blood Bank", status: "Active", seen: "Yesterday" },
  { name: "Ibrahim Turay", role: "Blood Bank Staff", facility: "Holy Spirit Blood Bank", status: "Pending", seen: "Never" },
];

export default function AdminConsole() {
  return (
    <div className="ad-root">
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');

        /* ===== THEME TOKENS — same palette as the other portals ===== */
        .ad-root {
          --navy:       #1C3A5E;
          --navy-soft:  #2A4E77;
          --red:        #C8102E;
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
          /* height floor: if 100vh resolves to 0 inside a preview frame,
             min-height keeps the layout from collapsing to nothing */
          height: 100vh;
          min-height: 680px;
          overflow: hidden;
          display: flex;
          -webkit-font-smoothing: antialiased;
        }
        .ad-root *, .ad-root *::before, .ad-root *::after { box-sizing: border-box; }

        /* ===== SIDEBAR ===== */
        .ad-side {
          flex: 0 0 auto;
          width: clamp(190px, 17vw, 250px);
          background: var(--navy);
          display: flex; flex-direction: column;
          padding: clamp(14px, 2.4vh, 26px) clamp(10px, 1vw, 16px);
          gap: clamp(10px, 1.6vh, 18px);
        }
        .ad-brand { display: flex; align-items: center; gap: 9px; padding: 0 6px; }
        .ad-brand-name { font-size: clamp(14px, 2vh, 17px); font-weight: 800; letter-spacing: -.02em; line-height: 1; }
        .ad-brand-tag { font-size: clamp(4.6px, .72vh, 6px); letter-spacing: .14em; color: rgba(255,255,255,.7); margin-top: 3px; font-weight: 600; }
        .ad-role {
          margin: clamp(2px, .8vh, 8px) 6px 0;
          font-size: clamp(8px, 1.1vh, 10px); font-weight: 700; letter-spacing: .1em;
          color: rgba(255,255,255,.5);
        }

        .ad-nav { display: flex; flex-direction: column; gap: clamp(2px, .5vh, 5px); }
        .ad-nav button {
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
        .ad-nav button svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); flex: 0 0 auto; }
        .ad-nav button:hover { background: rgba(255,255,255,.08); color: #FFF; }
        .ad-nav button.is-active { background: var(--navy-soft); color: #FFF; }
        .ad-nav button:focus-visible, .ad-logout:focus-visible { outline: 2px solid #FFF; outline-offset: 2px; }

        .ad-logout {
          margin-top: auto;
          display: flex; align-items: center; gap: 11px;
          background: rgba(255,255,255,.07); border: none; cursor: pointer; color: #FFF;
          font-family: inherit; font-weight: 600;
          font-size: clamp(11.5px, 1.72vh, 15px);
          padding: clamp(8px, 1.3vh, 13px) 14px; border-radius: 8px;
        }
        .ad-logout:hover { background: rgba(255,255,255,.14); }
        .ad-logout svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); }

        /* ===== MAIN ===== */
        .ad-main {
          flex: 1 1 auto; min-width: 0;
          display: flex; flex-direction: column;
          padding: clamp(12px, 2.2vh, 24px) clamp(14px, 1.6vw, 28px);
          gap: clamp(9px, 1.6vh, 18px);
          overflow: hidden;
        }
        .ad-top { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
        .ad-title { font-size: clamp(17px, 2.9vh, 26px); font-weight: 800; letter-spacing: -.025em; }
        .ad-sub { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); margin-top: 2px; }
        .ad-top-right { display: flex; align-items: center; gap: clamp(10px, 1vw, 16px); }
        .ad-date { display: flex; align-items: center; gap: 8px; font-size: clamp(10px, 1.5vh, 13px); font-weight: 600; color: #3A3A3A; }
        .ad-date svg { width: clamp(14px, 2vh, 17px); height: clamp(14px, 2vh, 17px); }
        .ad-bell { position: relative; display: grid; place-items: center; }
        .ad-bell svg { width: clamp(16px, 2.3vh, 20px); height: clamp(16px, 2.3vh, 20px); }
        .ad-bell::after {
          content: ''; position: absolute; top: 0; right: 0;
          width: 7px; height: 7px; border-radius: 50%;
          background: var(--red); border: 1.5px solid var(--page-bg);
        }
        .ad-avatar {
          width: clamp(28px, 4.2vh, 38px); height: clamp(28px, 4.2vh, 38px);
          border-radius: 50%; background: var(--navy);
          display: grid; place-items: center; color: #FFF;
          font-weight: 700; font-size: clamp(10px, 1.5vh, 13px);
          border: 2px solid #FFF; box-shadow: var(--shadow);
        }

        /* ===== STAT CARDS ===== */
        .ad-stats { flex: 0 0 auto; display: grid; grid-template-columns: repeat(4, 1fr); gap: clamp(9px, 1.1vw, 18px); }
        .ad-stat {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(9px, 1.5vh, 16px) clamp(11px, 1.1vw, 18px);
          display: flex; align-items: center; justify-content: space-between; gap: 10px;
        }
        .ad-stat-figure { font-size: clamp(18px, 3vh, 28px); font-weight: 800; line-height: 1.05; letter-spacing: -.02em; }
        .ad-stat-label { font-size: clamp(10.5px, 1.6vh, 14px); font-weight: 700; margin-top: 1px; }
        .ad-stat-note { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); margin-top: 2px; }
        .ad-stat-icon {
          flex: 0 0 auto;
          width: clamp(30px, 4.6vh, 44px); height: clamp(30px, 4.6vh, 44px);
          border-radius: 50%; display: grid; place-items: center;
        }
        .ad-stat-icon svg { width: 52%; height: 52%; }
        .ad-i-navy { background: var(--navy); }
        .ad-i-red { background: var(--red); }
        .ad-i-green { background: var(--green); }
        .ad-i-amber { background: var(--amber); }

        /* ===== PANELS ===== */
        .ad-charts { flex: 0 0 auto; display: grid; grid-template-columns: 1.45fr 1fr; gap: clamp(9px, 1.1vw, 18px); }
        .ad-panel {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(11px, 1.7vh, 18px) clamp(12px, 1.2vw, 20px);
          display: flex; flex-direction: column; min-height: 0;
        }
        .ad-panel-head { flex: 0 0 auto; display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: clamp(6px, 1vh, 12px); }
        .ad-panel-title { font-size: clamp(12px, 1.85vh, 16px); font-weight: 700; letter-spacing: -.01em; }
        .ad-panel-link { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: var(--red); cursor: pointer; }
        .ad-chart-wrap { flex: 1 1 auto; min-height: 0; display: flex; align-items: center; }
        .ad-chart-wrap svg { width: 100%; height: auto; max-height: clamp(120px, 20vh, 178px); }

        /* donut + legend */
        .ad-donut-row { display: flex; align-items: center; gap: clamp(10px, 1.2vw, 20px); width: 100%; }
        .ad-donut-row svg { flex: 0 0 auto; width: clamp(96px, 15vh, 140px); height: clamp(96px, 15vh, 140px); }
        .ad-legend { display: flex; flex-direction: column; gap: clamp(4px, .85vh, 9px); min-width: 0; }
        .ad-legend div { display: flex; align-items: center; gap: 7px; font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; }
        .ad-legend i { width: 9px; height: 9px; border-radius: 2px; flex: 0 0 auto; }
        .ad-legend em { font-style: normal; color: var(--ink-muted); font-weight: 500; }

        /* ===== USER TABLE ===== */
        .ad-table-panel { flex: 1 1 auto; min-height: 0; }
        .ad-table { width: 100%; border-collapse: collapse; }
        .ad-table th {
          text-align: left; font-size: clamp(8.5px, 1.22vh, 11px);
          font-weight: 700; color: var(--ink-muted);
          text-transform: uppercase; letter-spacing: .05em;
          padding: 0 8px clamp(5px, .9vh, 9px);
          border-bottom: 1.5px solid var(--line);
        }
        .ad-table td {
          font-size: clamp(10px, 1.48vh, 13px);
          padding: clamp(5px, .95vh, 10px) 8px;
          border-bottom: 1px solid var(--line);
          white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
        }
        .ad-table tr:last-child td { border-bottom: none; }
        .ad-user { display: flex; align-items: center; gap: 9px; }
        .ad-user-av {
          width: clamp(24px, 3.4vh, 31px); height: clamp(24px, 3.4vh, 31px);
          border-radius: 50%; flex: 0 0 auto;
          display: grid; place-items: center; color: #FFF;
          font-size: clamp(8.5px, 1.2vh, 11px); font-weight: 700;
        }
        .ad-user-name { font-weight: 700; }
        .ad-pill {
          display: inline-block;
          font-size: clamp(8.5px, 1.18vh, 11px); font-weight: 700;
          padding: 3px clamp(7px, .7vw, 10px); border-radius: 20px;
        }
        .ad-p-donor  { background: #FBE9EC; color: var(--red); }
        .ad-p-hosp   { background: #E8EDF3; color: var(--navy); }
        .ad-p-bank   { background: #E7F2EB; color: var(--green); }
        .ad-badge { font-size: clamp(8.5px, 1.18vh, 11px); font-weight: 700; padding: 3px clamp(7px, .7vw, 11px); border-radius: 20px; color: #FFF; }
        .ad-b-active    { background: var(--green); }
        .ad-b-suspended { background: var(--red); }
        .ad-b-pending   { background: var(--amber); }
        .ad-muted { color: var(--ink-muted); }

        @media (prefers-reduced-motion: reduce) { .ad-root * { transition: none !important; } }
      `}</style>

      {/* ================= SIDEBAR ================= */}
      <aside className="ad-side">
        <div className="ad-brand">
          <svg width="28" height="32" viewBox="0 0 34 38" aria-hidden="true">
            <path d="M17 2s13 14 13 22a13 13 0 0 1-26 0C4 16 17 2 17 2z" fill="#C8102E" />
            <path d="M7 24h5l2.5-6 3.5 12 3-8 2 2h5" fill="none" stroke="#FFF"
                  strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <div>
            <div className="ad-brand-name">
              <span style={{ color: "#C8102E" }}>Blood</span>
              <span style={{ color: "#FFFFFF" }}>Link</span>
            </div>
            <div className="ad-brand-tag">GIVE BLOOD · SAVE LIVES</div>
          </div>
        </div>

        <div className="ad-role">ADMINISTRATOR</div>

        <nav className="ad-nav">
          <button type="button" className="is-active"><GridIcon /> Dashboard</button>
          <button type="button"><UsersIcon /> User Accounts</button>
          <button type="button"><BankIcon /> Blood Banks</button>
          <button type="button"><HospitalIcon /> Hospitals</button>
          <button type="button"><ChartIcon /> Reports</button>
          <button type="button"><LogIcon /> Audit Log</button>
          <button type="button"><GearIcon /> Settings</button>
        </nav>

        <button type="button" className="ad-logout"><LogoutIcon /> Logout</button>
      </aside>

      {/* ================= MAIN ================= */}
      <main className="ad-main">
        <div className="ad-top">
          <div>
            <div className="ad-title">System Administration</div>
            <div className="ad-sub">BloodLink · Makeni City deployment</div>
          </div>
          <div className="ad-top-right">
            <div className="ad-date"><CalendarIcon /> Today, August 28 2026</div>
            <div className="ad-bell"><BellIcon /></div>
            <div className="ad-avatar">AD</div>
          </div>
        </div>

        {/* ---- stat cards ---- */}
        <div className="ad-stats">
          <Stat tone="navy" icon={<UsersFillIcon />} figure="924" label="Total Users" note="All roles" />
          <Stat tone="red" icon={<DropletIcon />} figure="850" label="Registered Donors" note="Voluntary donors" />
          <Stat tone="green" icon={<BuildingIcon />} figure="6" label="Partner Facilities" note="Hospitals & blood banks" />
          <Stat tone="amber" icon={<AlertIcon />} figure="2" label="System Alerts" note="Needs attention" />
        </div>

        {/* ---- charts ---- */}
        <div className="ad-charts">
          <div className="ad-panel">
            <div className="ad-panel-head">
              <div className="ad-panel-title">Donation Trends</div>
              <div className="ad-panel-link">Last 6 months</div>
            </div>
            <div className="ad-chart-wrap"><TrendChart /></div>
          </div>

          <div className="ad-panel">
            <div className="ad-panel-head">
              <div className="ad-panel-title">Request Outcomes</div>
              <div className="ad-panel-link">1,240 total</div>
            </div>
            <div className="ad-chart-wrap">
              <div className="ad-donut-row">
                <DonutChart />
                <div className="ad-legend">
                  {OUTCOMES.map((o) => (
                    <div key={o.label}>
                      <i style={{ background: o.color }} />
                      {o.label} <em>{o.pct}%</em>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* ---- user accounts ---- */}
        <div className="ad-panel ad-table-panel">
          <div className="ad-panel-head">
            <div className="ad-panel-title">User Accounts</div>
            <div className="ad-panel-link">Manage all users</div>
          </div>
          <table className="ad-table">
            <thead>
              <tr>
                <th style={{ width: "30%" }}>User</th>
                <th style={{ width: "20%" }}>Role</th>
                <th style={{ width: "27%" }}>Facility</th>
                <th style={{ width: "13%" }}>Status</th>
                <th style={{ width: "10%" }}>Last Active</th>
              </tr>
            </thead>
            <tbody>
              {USERS.map((u) => (
                <tr key={u.name}>
                  <td>
                    <div className="ad-user">
                      <div className="ad-user-av" style={{ background: roleColor(u.role) }}>
                        {initials(u.name)}
                      </div>
                      <span className="ad-user-name">{u.name}</span>
                    </div>
                  </td>
                  <td><span className={`ad-pill ${rolePill(u.role)}`}>{u.role}</span></td>
                  <td className={u.facility === "—" ? "ad-muted" : ""}>{u.facility}</td>
                  <td><span className={`ad-badge ad-b-${u.status.toLowerCase()}`}>{u.status}</span></td>
                  <td className="ad-muted">{u.seen}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </main>
    </div>
  );
}

/* ================= HELPERS ================= */
function initials(name) {
  return name.replace(/^Dr\.\s*/, "").split(" ").map((w) => w[0]).slice(0, 2).join("");
}
function roleColor(role) {
  if (role === "Donor") return "#C8102E";
  if (role === "Hospital Staff") return "#1C3A5E";
  return "#1E7F45";
}
function rolePill(role) {
  if (role === "Donor") return "ad-p-donor";
  if (role === "Hospital Staff") return "ad-p-hosp";
  return "ad-p-bank";
}

function Stat({ tone, icon, figure, label, note }) {
  return (
    <div className="ad-stat">
      <div>
        <div className="ad-stat-figure">{figure}</div>
        <div className="ad-stat-label">{label}</div>
        <div className="ad-stat-note">{note}</div>
      </div>
      <div className={`ad-stat-icon ad-i-${tone}`}>{icon}</div>
    </div>
  );
}

/* ================= CHARTS ================= */
function TrendChart() {
  const CW = 430, CH = 180, PAD_L = 34, PAD_B = 26, PAD_T = 12;
  const max = 200;
  const plotH = CH - PAD_B - PAD_T;
  const plotW = CW - PAD_L - 10;
  const slot = plotW / TRENDS.length;
  const barW = Math.min(34, slot * 0.52);
  const ticks = [0, 50, 100, 150, 200];

  return (
    <svg viewBox={`0 0 ${CW} ${CH}`} role="img"
         aria-label="Bar chart of monthly voluntary donations from March to August 2026">
      {ticks.map((t) => {
        const y = PAD_T + plotH - (t / max) * plotH;
        return (
          <g key={t}>
            <line x1={PAD_L} y1={y} x2={CW - 10} y2={y} stroke="#EEF0F2" strokeWidth="1" />
            <text x={PAD_L - 7} y={y + 3.5} textAnchor="end"
                  fontSize="9" fill="#9A9A9A" fontFamily="Inter, Arial, sans-serif">{t}</text>
          </g>
        );
      })}
      {TRENDS.map((d, i) => {
        const h = (d.donations / max) * plotH;
        const x = PAD_L + slot * i + (slot - barW) / 2;
        const y = PAD_T + plotH - h;
        return (
          <g key={d.month}>
            <rect x={x} y={y} width={barW} height={h} rx="3" fill="#C8102E" />
            <text x={x + barW / 2} y={y - 5} textAnchor="middle"
                  fontSize="9.5" fontWeight="700" fill="#333" fontFamily="Inter, Arial, sans-serif">
              {d.donations}
            </text>
            <text x={x + barW / 2} y={CH - 9} textAnchor="middle"
                  fontSize="10" fill="#666" fontFamily="Inter, Arial, sans-serif">
              {d.month}
            </text>
          </g>
        );
      })}
      <line x1={PAD_L} y1={PAD_T + plotH} x2={CW - 10} y2={PAD_T + plotH} stroke="#D8DBDF" strokeWidth="1.2" />
    </svg>
  );
}

function DonutChart() {
  const R = 52, C = 2 * Math.PI * R;
  let offset = 0;
  return (
    <svg viewBox="0 0 140 140" role="img" aria-label="Donut chart of blood request outcomes">
      <g transform="translate(70 70) rotate(-90)">
        <circle r={R} fill="none" stroke="#F0F1F3" strokeWidth="20" />
        {OUTCOMES.map((o) => {
          const len = (o.pct / 100) * C;
          const seg = (
            <circle key={o.label} r={R} fill="none" stroke={o.color} strokeWidth="20"
                    strokeDasharray={`${len} ${C - len}`} strokeDashoffset={-offset} />
          );
          offset += len;
          return seg;
        })}
      </g>
      <text x="70" y="66" textAnchor="middle" fontSize="20" fontWeight="800"
            fill="#111" fontFamily="Inter, Arial, sans-serif">1,240</text>
      <text x="70" y="82" textAnchor="middle" fontSize="9.5"
            fill="#8A8A8A" fontFamily="Inter, Arial, sans-serif">Requests</text>
    </svg>
  );
}

/* ================= ICONS ================= */

function GridIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>); }
function UsersIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><circle cx="9" cy="8" r="3.5"/><path d="M2 20c0-3.6 3.1-5.6 7-5.6s7 2 7 5.6"/><path d="M17 8.5a3 3 0 1 0 0-.2M18 14.6c2.4.5 4 2.4 4 5.4"/></svg>); }
function BankIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M3 21h18M4 21V9l8-5 8 5v12M9 21v-6h6v6"/></svg>); }
function HospitalIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M4 21V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v15M2 21h20"/><path d="M12 8v6M9 11h6"/></svg>); }
function ChartIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/></svg>); }
function LogIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h5"/></svg>); }
function GearIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><circle cx="12" cy="12" r="3.4"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9 17 7M7 17l-2.1 2.1"/></svg>); }
function LogoutIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/></svg>); }
function CalendarIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>); }
function BellIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0"/></svg>); }
function UsersFillIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><circle cx="9" cy="8" r="3.4"/><circle cx="17" cy="9" r="2.7"/><path d="M2 20c0-3.6 3.1-5.8 7-5.8s7 2.2 7 5.8z"/><path d="M16.5 14.4c3 .3 5.5 2.3 5.5 5.6h-5.2c0-2-.4-3.9-.3-5.6z"/></svg>); }
function DropletIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z"/></svg>); }
function BuildingIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><path d="M4 21V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v15M2 21h20"/><path d="M12 8v6M9 11h6"/></svg>); }
function AlertIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W} strokeWidth="2.2"><path d="M12 3 2 20h20L12 3z"/><path d="M12 10v4M12 17.5v.01"/></svg>); }
