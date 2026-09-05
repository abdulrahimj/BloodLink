import React from "react";

/* ================================================================
   BloodLink — Donor Portal

   Covers the donor use cases from Chapters One & Three:
     • View donation history and eligibility status
     • Respond to donation appeals from blood banks
     • Download recognition certificate

   Typography: Inter throughout — same as the Hospital Portal and
   Admin Console, so all screens read as one product.
   ================================================================ */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };
const ICON_W = { fill: "none", stroke: "#FFF", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

const DONOR = {
  name: "Rahim Barrie",
  group: "O+",
  totalDonations: 8,
  livesImpacted: 24,
  lastDonation: "12 May 2026",
  eligibleSince: "12 August 2026",
};

const HISTORY = [
  { date: "12 May 2026", type: "Whole Blood", units: 1, bank: "Makeni Regional Blood Bank" },
  { date: "18 January 2026", type: "Whole Blood", units: 1, bank: "Makeni Regional Blood Bank" },
  { date: "22 September 2025", type: "Whole Blood", units: 1, bank: "Holy Spirit Blood Bank" },
  { date: "30 May 2025", type: "Whole Blood", units: 1, bank: "Makeni Regional Blood Bank" },
  { date: "14 February 2025", type: "Whole Blood", units: 1, bank: "Makeni Regional Blood Bank" },
];

/* Donation appeals come from BLOOD BANKS, not hospitals.
   Hospitals request units from blood banks; blood banks appeal to
   donors when their own stock falls low. This matches the Donation
   entity, which links a donor to a blood_bank_id.

   Rahim is O+, so he only sees appeals for O+ donors. Holy Spirit
   holds 52 of the city's 141 O+ units, which is why its stock is
   low while Makeni Regional (89 units) is running planned drives. */
const APPEALS = [
  { units: 8, bank: "Holy Spirit Blood Bank", level: "Urgent", when: "22 mins ago",
    note: "O+ stock below safe level" },
  { units: 5, bank: "Makeni Regional Blood Bank", level: "Planned", when: "4 hrs ago",
    note: "Donation drive · Saturday 5 September" },
  { units: 4, bank: "Holy Spirit Blood Bank", level: "Planned", when: "Yesterday",
    note: "Community drive · Wusum" },
];

export default function DonorPortal() {
  return (
    <div className="dn-root">
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');

        /* ===== THEME TOKENS — identical to the other portals ===== */
        .dn-root {
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
        .dn-root *, .dn-root *::before, .dn-root *::after { box-sizing: border-box; }

        /* ===== SIDEBAR ===== */
        .dn-side {
          flex: 0 0 auto;
          width: clamp(190px, 17vw, 250px);
          background: var(--navy);
          display: flex; flex-direction: column;
          padding: clamp(14px, 2.4vh, 26px) clamp(10px, 1vw, 16px);
          gap: clamp(10px, 1.6vh, 18px);
        }
        .dn-brand { display: flex; align-items: center; gap: 9px; padding: 0 6px; }
        .dn-brand-name { font-size: clamp(14px, 2vh, 17px); font-weight: 800; letter-spacing: -.02em; line-height: 1; }
        .dn-brand-tag { font-size: clamp(4.6px, .72vh, 6px); letter-spacing: .14em; color: rgba(255,255,255,.7); margin-top: 3px; font-weight: 600; }
        .dn-role { margin: clamp(2px, .8vh, 8px) 6px 0; font-size: clamp(8px, 1.1vh, 10px); font-weight: 700; letter-spacing: .1em; color: rgba(255,255,255,.5); }

        .dn-nav { display: flex; flex-direction: column; gap: clamp(2px, .5vh, 5px); }
        .dn-nav button {
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
        .dn-nav button svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); flex: 0 0 auto; }
        .dn-nav button:hover { background: rgba(255,255,255,.08); color: #FFF; }
        .dn-nav button.is-active { background: var(--navy-soft); color: #FFF; }
        .dn-nav button:focus-visible, .dn-logout:focus-visible { outline: 2px solid #FFF; outline-offset: 2px; }

        .dn-logout {
          margin-top: auto;
          display: flex; align-items: center; gap: 11px;
          background: rgba(255,255,255,.07); border: none; cursor: pointer; color: #FFF;
          font-family: inherit; font-weight: 600;
          font-size: clamp(11.5px, 1.72vh, 15px);
          padding: clamp(8px, 1.3vh, 13px) 14px; border-radius: 8px;
        }
        .dn-logout:hover { background: rgba(255,255,255,.14); }
        .dn-logout svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); }

        /* ===== MAIN ===== */
        .dn-main {
          flex: 1 1 auto; min-width: 0;
          display: flex; flex-direction: column;
          padding: clamp(12px, 2.2vh, 24px) clamp(14px, 1.6vw, 28px);
          gap: clamp(9px, 1.6vh, 18px);
          overflow: hidden;
        }
        .dn-top { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
        .dn-title { font-size: clamp(17px, 2.9vh, 26px); font-weight: 800; letter-spacing: -.025em; }
        .dn-sub { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); margin-top: 2px; }
        .dn-top-right { display: flex; align-items: center; gap: clamp(10px, 1vw, 16px); }
        .dn-date { display: flex; align-items: center; gap: 8px; font-size: clamp(10px, 1.5vh, 13px); font-weight: 600; color: #3A3A3A; }
        .dn-date svg { width: clamp(14px, 2vh, 17px); height: clamp(14px, 2vh, 17px); }
        .dn-bell { position: relative; display: grid; place-items: center; }
        .dn-bell svg { width: clamp(16px, 2.3vh, 20px); height: clamp(16px, 2.3vh, 20px); }
        .dn-bell::after {
          content: ''; position: absolute; top: 0; right: 0;
          width: 7px; height: 7px; border-radius: 50%;
          background: var(--red); border: 1.5px solid var(--page-bg);
        }
        .dn-avatar {
          width: clamp(28px, 4.2vh, 38px); height: clamp(28px, 4.2vh, 38px);
          border-radius: 50%; background: var(--navy);
          display: grid; place-items: center; color: #FFF;
          font-weight: 700; font-size: clamp(10px, 1.5vh, 13px);
          border: 2px solid #FFF; box-shadow: var(--shadow);
        }

        /* ===== STAT CARDS ===== */
        .dn-stats { flex: 0 0 auto; display: grid; grid-template-columns: repeat(4, 1fr); gap: clamp(9px, 1.1vw, 18px); }
        .dn-stat {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(9px, 1.5vh, 16px) clamp(11px, 1.1vw, 18px);
          display: flex; align-items: center; justify-content: space-between; gap: 10px;
        }
        .dn-stat-figure { font-size: clamp(18px, 3vh, 28px); font-weight: 800; line-height: 1.05; letter-spacing: -.02em; }
        .dn-stat-label { font-size: clamp(10.5px, 1.6vh, 14px); font-weight: 700; margin-top: 1px; }
        .dn-stat-note { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); margin-top: 2px; }
        .dn-stat-icon {
          flex: 0 0 auto;
          width: clamp(30px, 4.6vh, 44px); height: clamp(30px, 4.6vh, 44px);
          border-radius: 50%; display: grid; place-items: center;
        }
        .dn-stat-icon svg { width: 52%; height: 52%; }
        .dn-i-red { background: var(--red); }
        .dn-i-navy { background: var(--navy); }
        .dn-i-green { background: var(--green); }
        .dn-i-amber { background: var(--amber); }
        .dn-tag {
          display: inline-block; margin-top: 4px;
          font-size: clamp(8px, 1.12vh, 10px); font-weight: 700;
          padding: 2px 8px; border-radius: 20px;
          background: #FBE9EC; color: var(--red);
        }

        /* ===== ELIGIBILITY BANNER ===== */
        .dn-elig {
          flex: 0 0 auto;
          background: var(--green); color: #FFF;
          border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(9px, 1.5vh, 15px) clamp(12px, 1.2vw, 20px);
          display: flex; align-items: center; gap: clamp(10px, 1vw, 15px);
        }
        .dn-elig svg { width: clamp(20px, 3vh, 27px); height: clamp(20px, 3vh, 27px); flex: 0 0 auto; }
        .dn-elig-main { font-size: clamp(12px, 1.85vh, 16px); font-weight: 700; }
        .dn-elig-sub { font-size: clamp(9.5px, 1.35vh, 12px); opacity: .9; margin-top: 1px; }
        .dn-elig-btn {
          margin-left: auto; flex: 0 0 auto;
          background: #FFF; color: var(--green);
          border: none; border-radius: 7px; cursor: pointer;
          font-family: inherit; font-weight: 700;
          font-size: clamp(10.5px, 1.5vh, 13px);
          padding: clamp(7px, 1.15vh, 11px) clamp(12px, 1.2vw, 20px);
        }

        /* ===== PANELS ===== */
        .dn-panels { flex: 1 1 auto; min-height: 0; display: grid; grid-template-columns: 1fr 1fr; gap: clamp(9px, 1.1vw, 18px); }
        .dn-col { min-height: 0; display: flex; flex-direction: column; gap: clamp(9px, 1.1vh, 16px); }
        .dn-panel {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(11px, 1.7vh, 18px) clamp(12px, 1.2vw, 20px);
          display: flex; flex-direction: column; min-height: 0;
        }
        .dn-panel-head { flex: 0 0 auto; display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: clamp(7px, 1.2vh, 13px); }
        .dn-panel-title { font-size: clamp(12px, 1.85vh, 16px); font-weight: 700; letter-spacing: -.01em; }
        .dn-panel-link { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: var(--red); cursor: pointer; }

        .dn-list { flex: 1 1 auto; min-height: 0; overflow: hidden; display: flex; flex-direction: column; gap: clamp(4px, .8vh, 9px); }
        .dn-item {
          display: flex; align-items: center; gap: clamp(8px, .8vw, 12px);
          padding: clamp(5px, 1vh, 10px) clamp(7px, .7vw, 11px);
          border: 1px solid var(--line); border-radius: 8px;
        }
        .dn-item-badge {
          flex: 0 0 auto;
          width: clamp(28px, 4.2vh, 38px); height: clamp(28px, 4.2vh, 38px);
          border-radius: 50%; display: grid; place-items: center;
          font-weight: 800; font-size: clamp(9.5px, 1.4vh, 12.5px); letter-spacing: -.02em;
          background: #FBE9EC; color: var(--red);
        }
        .dn-item-badge--navy { background: #E8EDF3; color: var(--navy); }
        .dn-item-body { flex: 1 1 auto; min-width: 0; }
        .dn-item-main { font-size: clamp(10.5px, 1.55vh, 13.5px); font-weight: 700; }
        .dn-item-sub { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); margin-top: 1px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .dn-pill { flex: 0 0 auto; font-size: clamp(8.5px, 1.18vh, 11px); font-weight: 700; padding: 4px clamp(7px, .7vw, 11px); border-radius: 20px; color: #FFF; }
        .dn-p-urgent { background: var(--red); }
        .dn-p-planned { background: var(--green); }
        .dn-help {
          flex: 0 0 auto; margin-left: 6px;
          background: var(--red); color: #FFF; border: none; border-radius: 6px;
          cursor: pointer; font-family: inherit; font-weight: 700;
          font-size: clamp(9px, 1.28vh, 11.5px);
          padding: clamp(5px, .85vh, 8px) clamp(8px, .8vw, 13px);
        }
        .dn-help:hover { background: var(--red-dark); }

        /* ===== CERTIFICATE ===== */
        .dn-cert {
          flex: 0 0 auto;
          background: var(--navy); color: #FFF;
          border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(11px, 1.7vh, 18px) clamp(12px, 1.2vw, 20px);
          display: flex; align-items: center; gap: clamp(10px, 1vw, 15px);
        }
        .dn-cert-icon {
          flex: 0 0 auto;
          width: clamp(32px, 4.8vh, 46px); height: clamp(32px, 4.8vh, 46px);
          border-radius: 10px; background: rgba(255,255,255,.12);
          display: grid; place-items: center;
        }
        .dn-cert-icon svg { width: 55%; height: 55%; }
        .dn-cert-main { font-size: clamp(11.5px, 1.7vh, 15px); font-weight: 700; }
        .dn-cert-sub { font-size: clamp(9px, 1.28vh, 11.5px); color: rgba(255,255,255,.72); margin-top: 2px; }
        .dn-cert-btn {
          margin-left: auto; flex: 0 0 auto;
          background: var(--red); color: #FFF; border: none; border-radius: 7px; cursor: pointer;
          font-family: inherit; font-weight: 700;
          font-size: clamp(10px, 1.42vh, 12.5px);
          padding: clamp(7px, 1.1vh, 11px) clamp(11px, 1.1vw, 17px);
        }
        .dn-cert-btn:hover { background: var(--red-dark); }

        @media (prefers-reduced-motion: reduce) { .dn-root * { transition: none !important; } }
      `}</style>

      {/* ================= SIDEBAR ================= */}
      <aside className="dn-side">
        <div className="dn-brand">
          <svg width="28" height="32" viewBox="0 0 34 38" aria-hidden="true">
            <path d="M17 2s13 14 13 22a13 13 0 0 1-26 0C4 16 17 2 17 2z" fill="#C8102E" />
            <path d="M7 24h5l2.5-6 3.5 12 3-8 2 2h5" fill="none" stroke="#FFF"
                  strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <div>
            <div className="dn-brand-name">
              <span style={{ color: "#C8102E" }}>Blood</span>
              <span style={{ color: "#FFFFFF" }}>Link</span>
            </div>
            <div className="dn-brand-tag">GIVE BLOOD · SAVE LIVES</div>
          </div>
        </div>

        <div className="dn-role">DONOR</div>

        <nav className="dn-nav">
          <button type="button" className="is-active"><GridIcon /> Dashboard</button>
          <button type="button"><ClockIcon /> Donation History</button>
          <button type="button"><DropletOutlineIcon /> Donation Appeals</button>
          <button type="button"><HeartIcon /> My Impact</button>
          <button type="button"><AwardIcon /> Certificate</button>
          <button type="button"><GearIcon /> Settings</button>
        </nav>

        <button type="button" className="dn-logout"><LogoutIcon /> Logout</button>
      </aside>

      {/* ================= MAIN ================= */}
      <main className="dn-main">
        <div className="dn-top">
          <div>
            <div className="dn-title">Hello, {DONOR.name.split(" ")[0]}</div>
            <div className="dn-sub">Voluntary donor · Makeni City</div>
          </div>
          <div className="dn-top-right">
            <div className="dn-date"><CalendarIcon /> Today, August 28 2026</div>
            <div className="dn-bell"><BellIcon /></div>
            <div className="dn-avatar">RB</div>
          </div>
        </div>

        {/* ---- stat cards ---- */}
        <div className="dn-stats">
          <div className="dn-stat">
            <div>
              <div className="dn-stat-figure">{DONOR.group}</div>
              <div className="dn-stat-label">Blood Group</div>
              <span className="dn-tag">Universal Donor</span>
            </div>
            <div className="dn-stat-icon dn-i-red"><DropletIcon /></div>
          </div>

          <Stat tone="navy" icon={<GiftIcon />} figure={DONOR.totalDonations}
                label="Total Donations" note="Since February 2025" />
          <Stat tone="green" icon={<HeartFillIcon />} figure={DONOR.livesImpacted}
                label="Lives Impacted" note="Up to 3 per donation" />
          <Stat tone="amber" icon={<CalendarFillIcon />} figure={DONOR.lastDonation.split(" ")[0] + " May"}
                label="Last Donation" note="Makeni Regional Blood Bank" />
        </div>

        {/* ---- eligibility ---- */}
        <div className="dn-elig">
          <CheckCircleIcon />
          <div>
            <div className="dn-elig-main">You are eligible to donate</div>
            <div className="dn-elig-sub">
              Eligible since {DONOR.eligibleSince} · 90 days have passed since your last donation
            </div>
          </div>
          <button type="button" className="dn-elig-btn">Book a Donation</button>
        </div>

        {/* ---- panels ---- */}
        <div className="dn-panels">
          <div className="dn-col">
            <div className="dn-panel" style={{ flex: "1 1 auto" }}>
              <div className="dn-panel-head">
                <div className="dn-panel-title">Donation History</div>
                <div className="dn-panel-link">View All</div>
              </div>
              <div className="dn-list">
                {HISTORY.map((h, i) => (
                  <div className="dn-item" key={i}>
                    <div className="dn-item-badge dn-item-badge--navy">{DONOR.group}</div>
                    <div className="dn-item-body">
                      <div className="dn-item-main">{h.date}</div>
                      <div className="dn-item-sub">{h.type} · {h.units} unit · {h.bank}</div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>

          <div className="dn-col">
            <div className="dn-panel" style={{ flex: "1 1 auto" }}>
              <div className="dn-panel-head">
                <div className="dn-panel-title">Donation Appeals</div>
                <div className="dn-panel-link">From Makeni blood banks</div>
              </div>
              <div className="dn-list">
                {APPEALS.map((a, i) => (
                  <div className="dn-item" key={i}>
                    <div className="dn-item-badge">{DONOR.group}</div>
                    <div className="dn-item-body">
                      <div className="dn-item-main">
                        {DONOR.group} donors needed · {a.units} units short
                      </div>
                      <div className="dn-item-sub">{a.bank} · {a.note} · {a.when}</div>
                    </div>
                    <div className={`dn-pill dn-p-${a.level.toLowerCase()}`}>{a.level}</div>
                    <button type="button" className="dn-help">Respond</button>
                  </div>
                ))}
              </div>
            </div>

            <div className="dn-cert">
              <div className="dn-cert-icon"><AwardFillIcon /></div>
              <div>
                <div className="dn-cert-main">Donor Recognition Certificate</div>
                <div className="dn-cert-sub">{DONOR.totalDonations} donations · issued by BloodLink Makeni</div>
              </div>
              <button type="button" className="dn-cert-btn">Download</button>
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
    <div className="dn-stat">
      <div>
        <div className="dn-stat-figure">{figure}</div>
        <div className="dn-stat-label">{label}</div>
        <div className="dn-stat-note">{note}</div>
      </div>
      <div className={`dn-stat-icon dn-i-${tone}`}>{icon}</div>
    </div>
  );
}

/* ================= ICONS ================= */
function GridIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>); }
function ClockIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg>); }
function DropletOutlineIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z"/></svg>); }
function HeartIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M20.8 6.6a5 5 0 0 0-7.1 0L12 8.3l-1.7-1.7a5 5 0 0 0-7.1 7.1l8.8 8.8 8.8-8.8a5 5 0 0 0 0-7.1z"/></svg>); }
function AwardIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><circle cx="12" cy="9" r="6"/><path d="M8.2 14.3 7 22l5-2.6L17 22l-1.2-7.7"/></svg>); }
function GearIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><circle cx="12" cy="12" r="3.4"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9 17 7M7 17l-2.1 2.1"/></svg>); }
function LogoutIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/></svg>); }
function CalendarIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>); }
function BellIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0"/></svg>); }
function DropletIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z"/></svg>); }
function GiftIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><rect x="3" y="9" width="18" height="12" rx="2"/><path d="M3 13h18M12 9v12M12 9S9.5 3 7 5.5 12 9 12 9zM12 9s2.5-6 5-3.5S12 9 12 9z"/></svg>); }
function HeartFillIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><path d="M20.8 6.6a5 5 0 0 0-7.1 0L12 8.3l-1.7-1.7a5 5 0 0 0-7.1 7.1l8.8 8.8 8.8-8.8a5 5 0 0 0 0-7.1z"/></svg>); }
function CalendarFillIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>); }
function CheckCircleIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W} strokeWidth="2.2"><circle cx="12" cy="12" r="9.5"/><path d="M7.5 12.5 11 16l6-7"/></svg>); }
function AwardFillIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W} strokeWidth="1.9"><circle cx="12" cy="9" r="6"/><path d="M8.2 14.3 7 22l5-2.6L17 22l-1.2-7.7"/></svg>); }
