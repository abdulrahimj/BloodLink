import React, { useState } from "react";
import { formatHeaderDate } from "../lib/dates.js";
import RequestHistoryPanel from "../components/RequestHistoryPanel.jsx";
import NotificationBell from "../components/NotificationBell.jsx";
import MobileMenuToggle from "../components/MobileMenuToggle.jsx";
import { useNotifications } from "../realtime/useNotifications.js";

/* BloodLink — Blood Bank: Request history (Business Rule 36): the finished requests this bank contributed to or
   declined. Same sidebar shell (bd- classes) as the Donors page, so responsive.css lays it out on phones too. */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

export default function BloodBankRequestHistory({ user, onSignOut, onNavigate }) {
  const [today] = useState(() => new Date());
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const { notifications, unreadCount, connected, markAllRead } = useNotifications();

  return (
    <div className={`bd-root${mobileMenuOpen ? " is-menu-open" : ""}`}>
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');

        .bd-root {
          --navy:#1C3A5E; --navy-soft:#2A4E77; --red:#C8102E; --red-dark:#A50E26;
          --green:#1E7F45; --amber:#D98A1F; --page-bg:#F1F2F4; --card-bg:#FFFFFF;
          --ink:#111111; --ink-muted:#7A7A7A; --line:#E6E8EB;
          --radius:10px; --shadow:0 2px 10px rgba(0,0,0,0.07);

          font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
          color: var(--ink); background: var(--page-bg);
          height: 100vh; min-height: 680px; overflow: hidden; display: flex;
          -webkit-font-smoothing: antialiased;
        }
        .bd-root *, .bd-root *::before, .bd-root *::after { box-sizing: border-box; }

        .bd-side {
          flex: 0 0 auto; width: clamp(190px, 17vw, 250px); background: var(--navy);
          display: flex; flex-direction: column;
          padding: clamp(14px, 2.4vh, 26px) clamp(10px, 1vw, 16px); gap: clamp(10px, 1.6vh, 18px);
        }
        .bd-brand { display: flex; align-items: center; gap: 9px; padding: 0 6px; }
        .bd-brand-name { font-size: clamp(14px, 2vh, 17px); font-weight: 800; letter-spacing: -.02em; line-height: 1; }
        .bd-brand-tag { font-size: clamp(4.6px, .72vh, 6px); letter-spacing: .14em; color: rgba(255,255,255,.7); margin-top: 3px; font-weight: 600; }
        .bd-role { margin: clamp(2px, .8vh, 8px) 6px 0; font-size: clamp(8px, 1.1vh, 10px); font-weight: 700; letter-spacing: .1em; color: rgba(255,255,255,.5); }
        .bd-nav { display: flex; flex-direction: column; gap: clamp(2px, .5vh, 5px); }
        .bd-nav button {
          display: flex; align-items: center; gap: 11px; width: 100%; text-align: left;
          background: transparent; border: none; cursor: pointer; color: rgba(255,255,255,.86);
          font-family: inherit; font-size: clamp(11.5px, 1.72vh, 15px); font-weight: 600;
          padding: clamp(7px, 1.24vh, 12px) 12px; border-radius: 8px; transition: background .15s ease;
        }
        .bd-nav button svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); flex: 0 0 auto; }
        .bd-nav button:hover { background: rgba(255,255,255,.08); color: #FFF; }
        .bd-nav button.is-active { background: var(--navy-soft); color: #FFF; }
        .bd-logout {
          margin-top: auto; display: flex; align-items: center; gap: 11px;
          background: rgba(255,255,255,.07); border: none; cursor: pointer; color: #FFF;
          font-family: inherit; font-weight: 600; font-size: clamp(11.5px, 1.72vh, 15px);
          padding: clamp(8px, 1.3vh, 13px) 14px; border-radius: 8px;
        }
        .bd-logout svg { width: clamp(14px, 2vh, 18px); height: clamp(14px, 2vh, 18px); }

        .bd-main {
          flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column;
          padding: clamp(12px, 2.2vh, 24px) clamp(14px, 1.6vw, 28px);
          gap: clamp(9px, 1.6vh, 18px); overflow: hidden;
        }
        .bd-top { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
        .bd-title { font-size: clamp(17px, 2.9vh, 26px); font-weight: 800; letter-spacing: -.025em; }
        .bd-sub { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); margin-top: 2px; }
        .bd-top-right { display: flex; align-items: center; gap: clamp(10px, 1vw, 16px); }
        .bd-date { display: flex; align-items: center; gap: 8px; font-size: clamp(10px, 1.5vh, 13px); font-weight: 600; color: #3A3A3A; }
        .bd-date svg { width: clamp(14px, 2vh, 17px); height: clamp(14px, 2vh, 17px); }

        @media (prefers-reduced-motion: reduce) { .bd-root * { transition: none !important; } }
      `}</style>

      {mobileMenuOpen && <button type="button" className="mobile-menu-backdrop" aria-label="Close navigation menu" onClick={() => setMobileMenuOpen(false)} />}
      <aside className="bd-side" id="bd-sidebar">
        <div className="bd-brand">
          <svg width="28" height="32" viewBox="0 0 34 38" aria-hidden="true">
            <path d="M17 2s13 14 13 22a13 13 0 0 1-26 0C4 16 17 2 17 2z" fill="#C8102E" />
            <path d="M7 24h5l2.5-6 3.5 12 3-8 2 2h5" fill="none" stroke="#FFF" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <div>
            <div className="bd-brand-name">
              <span style={{ color: "#C8102E" }}>Blood</span><span style={{ color: "#FFFFFF" }}>Link</span>
            </div>
            <div className="bd-brand-tag">GIVE BLOOD · SAVE LIVES</div>
          </div>
        </div>

        <div className="bd-role">{user.role === "ADMIN" ? "ADMINISTRATOR" : "BLOOD BANK STAFF"}</div>

        <nav className="bd-nav" onClick={() => setMobileMenuOpen(false)}>
          <button type="button" onClick={() => onNavigate("dashboard")}><GridIcon /> Dashboard</button>
          <button type="button" onClick={() => onNavigate("inventory")}><DropletOutlineIcon /> Blood Inventory</button>
          <button type="button"><TruckIcon /> Blood Logistics</button>
          <button type="button" onClick={() => onNavigate("donors")}><UsersIcon /> Donors</button>
          <button type="button" className="is-active" aria-current="page"><ClockIcon /> Request history</button>
          <button type="button" onClick={() => onNavigate("settings")}><GearIcon /> Settings</button>
        </nav>

        <button type="button" className="bd-logout" onClick={onSignOut}><LogoutIcon /> Logout</button>
      </aside>

      <main className="bd-main">
        <div className="bd-top">
          <MobileMenuToggle open={mobileMenuOpen} onClick={() => setMobileMenuOpen((open) => !open)} controls="bd-sidebar" />
          <div>
            <div className="bd-title">Request history</div>
            <div className="bd-sub">Finished requests this bank contributed to or declined</div>
          </div>
          <div className="bd-top-right">
            <div className="bd-date"><CalendarIcon /> Today, {formatHeaderDate(today)}</div>
            <NotificationBell
              notifications={notifications}
              unreadCount={unreadCount}
              connected={connected}
              onMarkAllRead={markAllRead}
            />
          </div>
        </div>

        <RequestHistoryPanel side="bank" />
      </main>
    </div>
  );
}

function GridIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>); }
function DropletOutlineIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z"/></svg>); }
function TruckIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M2 7h11v10H2zM13 10h4.5l3.5 3.5V17h-8"/><circle cx="6.5" cy="18.5" r="1.8"/><circle cx="17" cy="18.5" r="1.8"/></svg>); }
function UsersIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><circle cx="9" cy="8" r="3.5"/><path d="M2 20c0-3.6 3.1-5.6 7-5.6s7 2 7 5.6"/><path d="M17 8.5a3 3 0 1 0 0-.2M18 14.6c2.4.5 4 2.4 4 5.4"/></svg>); }
function ClockIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg>); }
function GearIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><circle cx="12" cy="12" r="3.4"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9 17 7M7 17l-2.1 2.1"/></svg>); }
function LogoutIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/></svg>); }
function CalendarIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>); }
