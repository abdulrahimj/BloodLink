import React, { useEffect, useRef, useState } from "react";
import {
  getAdminUsers, createStaffAccount, updateUserStatus, getDonationTrends, getRequestOutcomes,
} from "../api/admin.js";
import { getBloodBanks } from "../api/bloodBanks.js";
import { getHospitals } from "../api/hospitals.js";
import { formatHeaderDate, formatRelativeTime, initials } from "../lib/dates.js";

/* ================================================================
   BloodLink — Admin Console (System Administrator)

   Covers the administrator use cases from Chapters One & Three:
     • Manage User Accounts  (all four roles, activate / suspend)
     • View Reports & Analytics  (donation trends, request outcomes)

   Wired to GET /api/admin/users, POST + PATCH /api/admin/users, and
   GET /api/admin/reports/donations + /requests. Layout, styling and the two
   inline SVG charts are the mockup's, unchanged; the TRENDS/OUTCOMES/USERS
   fixtures are replaced with fetched data, and a loading/error pair was
   added using the same idiom as the other wired portals.

   Two views, switched from the sidebar the way BloodBankPortal switches its
   own (plain state, no URL routing):
     • Dashboard (DashboardView) — read-only: the four stat cards and two
       charts, nothing that changes anything.
     • User Accounts (UserAccountsView) — the account table with its
       Suspend/Activate actions, the Create Account flow, and a role filter.
   The mockup drew the user table beneath the charts on one screen; it was
   moved to its own page once the table gained a Create Account flow and a
   donor register that only grows — a list to work through does not belong
   on a summary screen. The other sidebar entries (Blood Banks, Hospitals,
   Reports, Audit Log, Settings) are still the mockup's inert buttons.

   The notification bell is deliberately left exactly as the mockup drew it —
   a static icon with an always-on dot, no live count. No Notification type
   has been assigned to ADMIN yet (that is still an open decision), so there
   is nothing real to wire it to; wiring it to a count of zero would be worse
   than leaving it inert, since a bell that never lights up reads as broken
   rather than as "nothing has happened".

   Two stat cards changed meaning rather than just source:
     • "Partner Facilities" is now the live count of seeded blood banks plus
       hospitals (GET /api/blood-banks + /api/hospitals), not a hardcoded 6 —
       true today and still true if a facility is ever added.
     • "System Alerts" tracked nothing the schema records, so it was not
       wireable as written. It is replaced by "Pending Requests", sourced
       from the real request-outcomes report — a genuine citywide figure that
       keeps the same amber "needs attention" framing the mockup intended.

   "Create Account" is likewise new UI, for the same reason (Business Rule 14):
   the mockup drew a user table but no way to add to it, because until now staff
   and administrator accounts were inserted into the database by hand. The button,
   its form and the one-time credentials screen are all built from the tokens
   already declared below. The credentials screen is deliberately the loudest
   thing on this page — the generated password is shown exactly once and cannot
   be retrieved afterwards, so an administrator who closes it without reading it
   has to create the account again.

   The user table's Action column is new UI: the mockup's own table had no
   button to suspend or activate anyone, only a status badge. Built from this
   file's own tokens (navy/red/amber/shadow/radius already declared below),
   the same justification the appeal button and batch modal used elsewhere —
   not a deviation from an existing design, a control the mockup never drew.
   Activate needs no confirmation (restoring access is the safe direction,
   the same asymmetry the Blood Bank Dashboard already gives Approve/Reject);
   Suspend opens a light confirmation, because it denies a real person access
   immediately — though never irreversibly, since any administrator can undo
   it with one more click, which is why this stops short of the Dashboard's
   heavier Reject dialog.
   ================================================================ */

/* Shared SVG stroke presets (declared before any component uses them) */
const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };
const ICON_W = { fill: "none", stroke: "#FFF", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

/** DONOR -> "Donor", HOSPITAL_STAFF -> "Hospital Staff", matching how the
 *  mockup's own fixture data phrased each role. */
const ROLE_LABEL = {
  DONOR: "Donor",
  HOSPITAL_STAFF: "Hospital Staff",
  BLOOD_BANK_STAFF: "Blood Bank Staff",
  ADMIN: "Administrator",
};

/** ACTIVE -> "Active", SUSPENDED -> "Suspended" — the only two statuses the
 *  backend can return (see AccountStatus); title-cased for display. */
function statusLabel(status) {
  return status.charAt(0) + status.slice(1).toLowerCase();
}

/** ROLE_LABEL, minus DONOR: the three roles an administrator may create here.
 *  Donors self-register (Business Rule 8), and the server refuses a DONOR on this
 *  endpoint regardless — offering it would be offering something that cannot work. */
const CREATABLE_ROLES = ["HOSPITAL_STAFF", "BLOOD_BANK_STAFF", "ADMIN"];

/** The two views this console switches between. Held as plain
 *  state rather than a URL hash, the same way BloodBankPortal switches Dashboard /
 *  Blood Inventory: App.jsx's hash routing chooses which portal a role sees, and
 *  nothing inside a portal has ever needed its own address. */
const VIEW = { DASHBOARD: "dashboard", USERS: "users" };

/** The role filter's choices, in the order the buttons appear. "ALL" first, then
 *  the four roles in ROLE_LABEL's own order. */
const ROLE_FILTERS = ["ALL", "DONOR", "HOSPITAL_STAFF", "BLOOD_BANK_STAFF", "ADMIN"];

/**
 * The console shell: sidebar, header, data loading, and which view is showing.
 *
 * Data is loaded once, here, and shared by both views rather than fetched by each
 * view as it opens. The two genuinely overlap — the dashboard's "Total Users" and
 * "Registered Donors" cards are counts over the very list the User Accounts page
 * shows — so keeping one copy means an account created or suspended on the User
 * Accounts page is already reflected in those cards when the administrator
 * switches back, with no refetch and no chance of the two pages disagreeing.
 */
export default function AdminConsole({ user, onSignOut }) {
  const [view, setView] = useState(VIEW.DASHBOARD);

  const [users, setUsers] = useState([]);
  const [trends, setTrends] = useState([]);
  const [outcomes, setOutcomes] = useState(null);
  // The facility reference lists, kept rather than counted away: the stat card
  // needs their total, and the Create Account form needs the rows themselves for
  // its hospital / blood bank picker. Both come from the listings this page
  // already fetched (GET /api/blood-banks, GET /api/hospitals) — the same
  // endpoints every other picker in the app uses, not a second admin-only one.
  const [banks, setBanks] = useState([]);
  const [hospitals, setHospitals] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [today] = useState(() => new Date());

  async function load() {
    const [usersData, trendsData, outcomesData, banks, hospitals] = await Promise.all([
      getAdminUsers(),
      getDonationTrends(),
      getRequestOutcomes(),
      getBloodBanks(),
      getHospitals(),
    ]);
    return { usersData, trendsData, outcomesData, banks, hospitals };
  }

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    load()
      .then(({ usersData, trendsData, outcomesData, banks: bankRows, hospitals: hospitalRows }) => {
        if (cancelled) return;
        setUsers(usersData);
        setTrends(trendsData);
        setOutcomes(outcomesData);
        setBanks(bankRows);
        setHospitals(hospitalRows);
      })
      .catch((err) => { if (!cancelled) setError(err.message); })
      .finally(() => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onUsers = view === VIEW.USERS;

  return (
    <div className="ad-root">
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');

        /* ===== THEME TOKENS — same palette as the other portals ===== */
        .ad-root {
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
        .ad-table-panel { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; }
        .ad-table-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
        .ad-table { width: 100%; border-collapse: collapse; }
        .ad-table th {
          text-align: left; font-size: clamp(8.5px, 1.22vh, 11px);
          font-weight: 700; color: var(--ink-muted);
          text-transform: uppercase; letter-spacing: .05em;
          padding: 0 8px clamp(5px, .9vh, 9px);
          border-bottom: 1.5px solid var(--line);
          position: sticky; top: 0; background: var(--card-bg);
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
        /* Every other pill traces to a colour in the mockup's own legend; there
           was simply no ADMIN row in its fixture data to give one to. Amber is
           already this screen's fourth accent (used generically, not only for
           "pending" — see the System Alerts stat card in the original mockup),
           so it is reused here rather than introducing a fifth palette colour. */
        .ad-p-admin  { background: #FBF1E3; color: var(--amber); }
        .ad-badge { font-size: clamp(8.5px, 1.18vh, 11px); font-weight: 700; padding: 3px clamp(7px, .7vw, 11px); border-radius: 20px; color: #FFF; }
        .ad-b-active    { background: var(--green); }
        .ad-b-suspended { background: var(--red); }
        .ad-muted { color: var(--ink-muted); }

        /* row action */
        .ad-act {
          background: transparent; border: 1.5px solid rgba(200,16,46,.35); color: var(--red);
          border-radius: 6px; cursor: pointer; font-family: inherit; font-weight: 700;
          font-size: clamp(9px, 1.28vh, 11.5px); padding: clamp(4px, .7vh, 7px) clamp(8px, .8vw, 12px);
          white-space: nowrap;
        }
        .ad-act:hover { background: rgba(200,16,46,.07); border-color: var(--red); }
        .ad-act:disabled { border-color: var(--line); color: var(--ink-muted); cursor: not-allowed; background: transparent; }
        /* Activate is the safe direction (restoring access), so it is styled to
           match — green outline rather than red, the same tone the badge above
           already uses for "Active". */
        .ad-act--go { border-color: rgba(30,127,69,.35); color: var(--green); }
        .ad-act--go:hover { background: rgba(30,127,69,.07); border-color: var(--green); }
        .ad-act-error { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--red); font-weight: 600; white-space: normal; margin-top: 3px; }

        /* Wiring states — additive, same tokens, for real async data. */
        .ad-loading, .ad-error { flex: 1 1 auto; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; text-align: center; }
        .ad-error-msg { color: var(--red); font-weight: 600; font-size: clamp(11px, 1.6vh, 14px); }
        .ad-retry {
          background: var(--navy); color: #FFF; border: none; border-radius: 7px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(10.5px, 1.5vh, 13px);
          padding: clamp(7px, 1.15vh, 11px) clamp(14px, 1.4vw, 22px);
        }
        .ad-empty { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); padding: clamp(8px, 1.4vh, 14px) 2px; }

        /* ===== SUSPEND CONFIRMATION — lightweight on purpose, see file header ===== */
        .ad-scrim {
          position: fixed; inset: 0; z-index: 50;
          background: rgba(17, 17, 17, .45);
          display: grid; place-items: center;
          padding: 20px;
        }
        .ad-dialog {
          background: var(--card-bg); border-radius: var(--radius);
          box-shadow: 0 12px 40px rgba(0,0,0,.22);
          width: min(400px, 100%);
          padding: clamp(16px, 2.4vh, 24px) clamp(16px, 1.6vw, 26px);
          display: flex; flex-direction: column; gap: clamp(8px, 1.2vh, 13px);
        }
        .ad-dialog-title { font-size: clamp(14px, 2vh, 17px); font-weight: 800; letter-spacing: -.02em; }
        .ad-dialog-body { font-size: clamp(11px, 1.55vh, 13.5px); line-height: 1.5; color: #3A3A3A; }
        .ad-dialog-actions { display: flex; justify-content: flex-end; gap: 9px; margin-top: clamp(2px, .6vh, 6px); }
        .ad-dialog-actions button {
          font-family: inherit; font-weight: 700; cursor: pointer;
          font-size: clamp(11px, 1.55vh, 13.5px);
          padding: clamp(8px, 1.15vh, 11px) clamp(13px, 1.3vw, 20px);
          border-radius: 7px; border: 1.5px solid transparent;
        }
        .ad-dialog-cancel { background: #FFF; border-color: var(--line); color: #3A3A3A; }
        .ad-dialog-cancel:hover { background: #F6F7F8; border-color: #C9CDD3; }
        .ad-dialog-confirm { background: var(--red); color: #FFF; }
        .ad-dialog-confirm:hover { background: var(--red-dark); }
        .ad-dialog-actions button:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }

        /* ===== CREATE ACCOUNT — button, form, credentials ===== */
        /* The one filled button on this screen: adding a staff account is the only
           thing here that creates something, and navy is this app's "primary
           action" tone everywhere else (Submit Request, Add Batch). */
        .ad-new {
          background: var(--navy); color: #FFF; border: none; border-radius: 7px;
          cursor: pointer; font-family: inherit; font-weight: 700;
          font-size: clamp(9.5px, 1.35vh, 12px);
          padding: clamp(5px, .85vh, 8px) clamp(10px, 1vw, 15px);
          display: inline-flex; align-items: center; gap: 6px;
        }
        .ad-new:hover { background: var(--navy-soft); }
        .ad-new:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }
        .ad-new svg { width: clamp(11px, 1.5vh, 14px); height: clamp(11px, 1.5vh, 14px); }
        .ad-panel-head-right { display: flex; align-items: center; gap: clamp(8px, .9vw, 14px); }

        /* ===== ROLE FILTER — User Accounts page ===== */
        /* Pill-shaped to echo the role pills in the table they filter. The chosen
           one is filled navy, this app's "selected / primary" tone, and each shows
           its count so an empty role is visible before it is clicked. */
        .ad-filters { flex: 0 0 auto; display: flex; flex-wrap: wrap; gap: clamp(5px, .5vw, 8px); margin-bottom: clamp(7px, 1.1vh, 12px); }
        .ad-filter {
          display: inline-flex; align-items: center; gap: 6px;
          background: #FFF; color: #3A3A3A; border: 1.5px solid var(--line); border-radius: 20px;
          cursor: pointer; font-family: inherit; font-weight: 700;
          font-size: clamp(9px, 1.28vh, 11.5px);
          padding: clamp(4px, .7vh, 6px) clamp(9px, .9vw, 13px);
        }
        .ad-filter:hover { border-color: #C9CDD3; background: #F6F7F8; }
        .ad-filter.is-on { background: var(--navy); border-color: var(--navy); color: #FFF; }
        .ad-filter:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }
        .ad-filter-count { font-weight: 600; opacity: .7; }

        /* A wider dialog than the suspend confirmation: this one holds a form,
           not a sentence. Scrolls internally rather than growing past the viewport
           on a short screen. */
        .ad-dialog--form { width: min(480px, 100%); max-height: min(86vh, 720px); overflow-y: auto; }

        .ad-form { display: flex; flex-direction: column; gap: clamp(8px, 1.15vh, 13px); }
        .ad-field { display: flex; flex-direction: column; gap: 4px; }
        .ad-label { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 700; color: #3A3A3A; }
        .ad-input, .ad-select {
          font-family: inherit; font-size: clamp(11px, 1.55vh, 13.5px);
          padding: clamp(7px, 1.1vh, 10px) 11px;
          border: 1.5px solid var(--line); border-radius: 7px; background: #FFF; color: var(--ink);
          width: 100%;
        }
        .ad-input:focus, .ad-select:focus { outline: none; border-color: var(--navy); }
        .ad-input--bad, .ad-select--bad { border-color: var(--red); }
        .ad-hint { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); }
        .ad-err { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--red); font-weight: 600; }
        .ad-form-alert {
          background: #FBE9EC; color: var(--red); font-weight: 600;
          font-size: clamp(10px, 1.4vh, 12.5px); line-height: 1.45;
          padding: clamp(7px, 1.1vh, 10px) 11px; border-radius: 7px;
        }
        .ad-dialog-confirm:disabled, .ad-dialog-cancel:disabled { opacity: .55; cursor: not-allowed; }
        .ad-dialog-confirm--go { background: var(--navy); }
        .ad-dialog-confirm--go:hover { background: var(--navy-soft); }

        /* The password, shown once. Deliberately the most prominent thing in the
           dialog — monospace so an l and a 1 can be told apart when it is read out
           (the generator avoids those characters anyway, belt and braces). */
        .ad-cred {
          background: #F6F7F8; border: 1.5px dashed #C9CDD3; border-radius: 8px;
          padding: clamp(10px, 1.5vh, 15px) clamp(11px, 1.1vw, 16px);
          display: flex; flex-direction: column; gap: 8px;
        }
        .ad-cred-label { font-size: clamp(8.5px, 1.2vh, 11px); font-weight: 700; letter-spacing: .06em; color: var(--ink-muted); text-transform: uppercase; }
        .ad-cred-row { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
        .ad-cred-value {
          font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
          font-size: clamp(14px, 2.2vh, 20px); font-weight: 700; letter-spacing: .04em;
          color: var(--ink); word-break: break-all; flex: 1 1 auto; min-width: 0;
        }
        .ad-copy {
          flex: 0 0 auto;
          background: var(--navy); color: #FFF; border: none; border-radius: 6px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(9.5px, 1.35vh, 12px);
          padding: clamp(6px, .95vh, 9px) clamp(10px, 1vw, 14px);
        }
        .ad-copy:hover { background: var(--navy-soft); }
        .ad-copy--done { background: var(--green); }
        /* Amber, not red: nothing has gone wrong — this is a deadline, not an error. */
        .ad-warn {
          background: #FBF1E3; color: #8A5A10; border-radius: 7px;
          font-size: clamp(10px, 1.4vh, 12.5px); font-weight: 600; line-height: 1.45;
          padding: clamp(8px, 1.15vh, 11px) 11px;
        }
        .ad-summary { font-size: clamp(11px, 1.55vh, 13.5px); line-height: 1.6; color: #3A3A3A; }
        .ad-summary b { color: var(--ink); }

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

        {/* Dashboard and User Accounts are wired; the rest remain the mockup's own
            inert entries, exactly as before — nothing behind them exists yet. */}
        <nav className="ad-nav">
          <button
            type="button" className={onUsers ? "" : "is-active"}
            aria-current={onUsers ? undefined : "page"}
            onClick={() => setView(VIEW.DASHBOARD)}
          >
            <GridIcon /> Dashboard
          </button>
          <button
            type="button" className={onUsers ? "is-active" : ""}
            aria-current={onUsers ? "page" : undefined}
            onClick={() => setView(VIEW.USERS)}
          >
            <UsersIcon /> User Accounts
          </button>
          <button type="button"><BankIcon /> Blood Banks</button>
          <button type="button"><HospitalIcon /> Hospitals</button>
          <button type="button"><ChartIcon /> Reports</button>
          <button type="button"><LogIcon /> Audit Log</button>
          <button type="button"><GearIcon /> Settings</button>
        </nav>

        <button type="button" className="ad-logout" onClick={onSignOut}><LogoutIcon /> Logout</button>
      </aside>

      {/* ================= MAIN ================= */}
      <main className="ad-main">
        <div className="ad-top">
          <div>
            <div className="ad-title">{onUsers ? "User Accounts" : "System Administration"}</div>
            <div className="ad-sub">
              {onUsers ? "Create, suspend and reactivate accounts across all four roles" : "BloodLink · Makeni City deployment"}
            </div>
          </div>
          <div className="ad-top-right">
            <div className="ad-date"><CalendarIcon /> Today, {formatHeaderDate(today)}</div>
            {/* Deliberately not a <NotificationBell> — see the file header
                comment. No Notification type is addressed to ADMIN yet, so
                this stays the mockup's own static icon. */}
            <div className="ad-bell"><BellIcon /></div>
            <div className="ad-avatar">{initials(user.fullName)}</div>
          </div>
        </div>

        {loading && (
          <div className="ad-loading"><div className="ad-sub">Loading system overview…</div></div>
        )}

        {!loading && error && (
          <div className="ad-error">
            <div className="ad-error-msg">{error}</div>
            <button type="button" className="ad-retry" onClick={() => window.location.reload()}>
              Try again
            </button>
          </div>
        )}

        {!loading && !error && outcomes && (
          onUsers ? (
            <UserAccountsView
              user={user}
              users={users}
              setUsers={setUsers}
              banks={banks}
              hospitals={hospitals}
            />
          ) : (
            <DashboardView
              users={users}
              trends={trends}
              outcomes={outcomes}
              facilityCount={banks.length + hospitals.length}
            />
          )
        )}
      </main>
    </div>
  );
}

/**
 * Dashboard — the read-only system summary.
 *
 * Four stat cards and two charts, and nothing that changes anything. Account
 * management used to share this screen as a table underneath the charts; it now
 * lives on its own page (UserAccountsView), so this one answers only "how is the
 * system doing?" and never "what do I need to do?". The cards and charts are
 * exactly as they were, including the two user counts, which still read from the
 * same shared user list the User Accounts page acts on.
 */
function DashboardView({ users, trends, outcomes, facilityCount }) {
  const totalUsers = users.length;
  const registeredDonors = users.filter((u) => u.role === "DONOR").length;
  const pendingRequests = outcomes.pending ?? 0;

  return (
    <>
      {/* ---- stat cards ---- */}
      <div className="ad-stats">
        <Stat tone="navy" icon={<UsersFillIcon />} figure={totalUsers} label="Total Users" note="All roles" />
        <Stat tone="red" icon={<DropletIcon />} figure={registeredDonors} label="Registered Donors" note="Voluntary donors" />
        <Stat tone="green" icon={<BuildingIcon />} figure={facilityCount} label="Partner Facilities" note="Hospitals & blood banks" />
        <Stat tone="amber" icon={<AlertIcon />} figure={pendingRequests} label="Pending Requests" note="Needs attention" />
      </div>

      {/* ---- charts ---- */}
      <div className="ad-charts">
        <div className="ad-panel">
          <div className="ad-panel-head">
            <div className="ad-panel-title">Donation Trends</div>
            <div className="ad-panel-link">Last 6 months</div>
          </div>
          <div className="ad-chart-wrap"><TrendChart data={trends} /></div>
        </div>

        <div className="ad-panel">
          <div className="ad-panel-head">
            <div className="ad-panel-title">Request Outcomes</div>
            <div className="ad-panel-link">{outcomes.total.toLocaleString()} total</div>
          </div>
          <div className="ad-chart-wrap">
            <div className="ad-donut-row">
              <DonutChart outcomes={outcomes} />
              <div className="ad-legend">
                {outcomeSlices(outcomes).map((o) => (
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
    </>
  );
}

/**
 * User Accounts — every account in the system, and everything an administrator
 * can do to one: create it, suspend it, reactivate it.
 *
 * Moved here from underneath the Dashboard's charts, unchanged in behaviour: the
 * same columns, the same Suspend/Activate buttons and confirmation, the same
 * Create Account dialog and one-time credentials screen (Business Rule 14). What
 * is new is the role filter above the table.
 *
 * <h3>Filtering happens in the browser</h3>
 * GET /api/admin/users already returns every account, and this page already holds
 * that list, so choosing a role narrows what is on screen rather than asking the
 * server again. That keeps the filter instant and needs no new endpoint. It is the
 * right trade while the list is in the hundreds; see the note on `visibleUsers`
 * for where that stops being true.
 *
 * The list itself belongs to the console shell, not to this view — `setUsers`
 * writes straight back to it — so the Dashboard's user counts move the moment an
 * account is created here.
 */
function UserAccountsView({ user, users, setUsers, banks, hospitals }) {
  const [roleFilter, setRoleFilter] = useState("ALL");

  // The Create Account form, and the credentials screen that replaces it on
  // success. Separate pieces of state because the second outlives the first:
  // the form closes the moment the account is created, and the password stays
  // on screen until the administrator dismisses it themselves.
  const [creatingAccount, setCreatingAccount] = useState(false);
  const [newCredentials, setNewCredentials] = useState(null);

  // Which row's status is mid-write, and the last write failure — mirrors the
  // Blood Bank Dashboard's actingOn/actionError pair, keyed to one row rather
  // than shown as a page-level banner.
  const [updatingUserId, setUpdatingUserId] = useState(null);
  const [actionError, setActionError] = useState(null);

  // The user awaiting a confirmed Suspend, or null when the dialog is closed.
  const [confirmingSuspend, setConfirmingSuspend] = useState(null);
  const cancelSuspendRef = useRef(null);

  // Guards the async status write against this view having been left (the
  // administrator clicking Dashboard mid-request) before the response arrives.
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  /** Escape closes the confirmation, and focus moves to Cancel — the same
   *  keyboard handling the Dashboard's Reject dialog already uses, so the two
   *  confirmations in this app behave identically even though this one is
   *  visually lighter. */
  useEffect(() => {
    if (!confirmingSuspend) return;

    cancelSuspendRef.current?.focus();
    const onKeyDown = (event) => {
      if (event.key === "Escape") setConfirmingSuspend(null);
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [confirmingSuspend]);

  /**
   * Writes one status change and reconciles the table from the response,
   * rather than trusting the click to know what the new row should look like —
   * the same reasoning BloodBankDashboard's handleAction gives for reloading
   * after an approve/reject/fulfil instead of patching local state by hand.
   */
  async function applyStatus(targetUserId, status) {
    setUpdatingUserId(targetUserId);
    setActionError(null);
    try {
      const updated = await updateUserStatus(targetUserId, status);
      if (!mountedRef.current) return;
      setUsers((current) => current.map((u) => (u.id === targetUserId ? updated : u)));
    } catch (err) {
      if (!mountedRef.current) return;
      setActionError({ userId: targetUserId, message: err.message });
    } finally {
      if (mountedRef.current) setUpdatingUserId(null);
    }
  }

  function handleConfirmSuspend() {
    const targetUserId = confirmingSuspend.id;
    setConfirmingSuspend(null);
    applyStatus(targetUserId, "SUSPENDED");
  }

  /**
   * Called by the form once the account exists, with the server's 201 body.
   *
   * The new row is spliced into the table from that response rather than by
   * re-fetching every account: the created account comes back in exactly the
   * shape this table renders, so a second round trip would ask the server to
   * repeat what it just said. Inserted in name order, which is the order
   * GET /api/admin/users returns, so the row sits where a reload would put it
   * instead of jumping on the next visit.
   *
   * If a role filter is active that would hide the new account — Donor chosen,
   * an Administrator just created — the filter switches to the new account's
   * role. Otherwise the administrator's first sight of the account they just
   * made would be its absence, which reads as a failure that did not happen.
   *
   * The password is held in state only for as long as the credentials screen is
   * open, and is never written to storage — it cannot be fetched again, so
   * keeping a copy anywhere would be keeping the one thing hashing exists to
   * avoid keeping.
   */
  function handleAccountCreated(result) {
    setUsers((current) =>
      [...current, result.account].sort((a, b) => a.fullName.localeCompare(b.fullName))
    );
    if (roleFilter !== "ALL" && roleFilter !== result.account.role) {
      setRoleFilter(result.account.role);
    }
    setCreatingAccount(false);
    setNewCredentials(result);
  }

  // How many accounts hold each role — shown on the filter buttons, so an empty
  // role is visible as "0" before anyone clicks it and finds an empty table.
  const countsByRole = users.reduce(
    (counts, u) => ({ ...counts, [u.role]: (counts[u.role] ?? 0) + 1 }),
    {}
  );

  /*
   * Filtered in the browser, over the list already held. Fine at the scale this
   * deployment has today — two blood banks, two hospitals, their staff, and a
   * donor register in the hundreds — where the whole list is a small response and
   * renders instantly. Donors are the part that grows without limit; once the
   * register reaches the low thousands, fetching and rendering every account on
   * each visit becomes the slow part, and the fix is paging and role filtering on
   * the server (GET /api/admin/users?role=...&page=...) rather than more cleverness
   * here. Not built now: that is a backend change, and a decision for the project
   * owner rather than something to add quietly.
   */
  const visibleUsers = roleFilter === "ALL" ? users : users.filter((u) => u.role === roleFilter);

  return (
    <>
      <div className="ad-panel ad-table-panel">
        <div className="ad-panel-head">
          <div className="ad-panel-title">All Accounts</div>
          <div className="ad-panel-head-right">
            <div className="ad-panel-link">
              {roleFilter === "ALL" ? `${users.length} total` : `${visibleUsers.length} of ${users.length}`}
            </div>
            {/* The only way to create a staff or administrator account
                without writing SQL — see Business Rule 14. */}
            <button type="button" className="ad-new" onClick={() => setCreatingAccount(true)}>
              <PlusIcon /> Create Account
            </button>
          </div>
        </div>

        {/* aria-pressed on each, so a screen reader announces which role is
            currently narrowing the table rather than just listing five buttons. */}
        <div className="ad-filters" role="group" aria-label="Filter accounts by role">
          {ROLE_FILTERS.map((role) => (
            <button
              key={role}
              type="button"
              className={`ad-filter${roleFilter === role ? " is-on" : ""}`}
              aria-pressed={roleFilter === role}
              onClick={() => setRoleFilter(role)}
            >
              {role === "ALL" ? "All" : ROLE_LABEL[role]}
              <span className="ad-filter-count">
                {role === "ALL" ? users.length : (countsByRole[role] ?? 0)}
              </span>
            </button>
          ))}
        </div>

        <div className="ad-table-scroll">
          <table className="ad-table">
            <thead>
              <tr>
                <th style={{ width: "24%" }}>User</th>
                <th style={{ width: "16%" }}>Role</th>
                <th style={{ width: "20%" }}>Facility</th>
                <th style={{ width: "11%" }}>Status</th>
                <th style={{ width: "13%" }}>Joined</th>
                <th style={{ width: "16%" }}>Action</th>
              </tr>
            </thead>
            <tbody>
              {visibleUsers.length === 0 && (
                <tr>
                  <td colSpan={6} className="ad-empty">
                    {roleFilter === "ALL"
                      ? "No accounts on record."
                      : `No ${ROLE_LABEL[roleFilter]} accounts on record.`}
                  </td>
                </tr>
              )}
              {visibleUsers.map((u) => {
                const facility = u.bloodBankName ?? u.hospitalName;
                const busy = updatingUserId === u.id;
                // The backend refuses this too (409), but a button that
                // always fails is worse than one that explains itself —
                // the same reasoning BloodBankDashboard already gives
                // canFulfil for a request this bank did not approve.
                const isSelf = u.id === user.id;

                return (
                  <tr key={u.id}>
                    <td>
                      <div className="ad-user">
                        <div className="ad-user-av" style={{ background: roleColor(u.role) }}>
                          {initials(u.fullName)}
                        </div>
                        <span className="ad-user-name">{u.fullName}</span>
                      </div>
                    </td>
                    <td><span className={`ad-pill ${rolePill(u.role)}`}>{ROLE_LABEL[u.role] ?? u.role}</span></td>
                    <td className={facility ? "" : "ad-muted"}>{facility ?? "—"}</td>
                    <td><span className={`ad-badge ad-b-${u.status.toLowerCase()}`}>{statusLabel(u.status)}</span></td>
                    <td className="ad-muted">{formatRelativeTime(u.createdAt)}</td>
                    <td>
                      {u.status === "ACTIVE" ? (
                        <button
                          type="button"
                          className="ad-act"
                          disabled={busy || isSelf}
                          title={isSelf ? "You cannot suspend your own account." : undefined}
                          onClick={() => setConfirmingSuspend(u)}
                        >
                          {busy ? "…" : "Suspend"}
                        </button>
                      ) : (
                        <button
                          type="button"
                          className="ad-act ad-act--go"
                          disabled={busy}
                          onClick={() => applyStatus(u.id, "ACTIVE")}
                        >
                          {busy ? "…" : "Activate"}
                        </button>
                      )}
                      {actionError?.userId === u.id && (
                        <div className="ad-act-error">{actionError.message}</div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      {confirmingSuspend && (
        <SuspendConfirmation
          targetUser={confirmingSuspend}
          cancelRef={cancelSuspendRef}
          onCancel={() => setConfirmingSuspend(null)}
          onConfirm={handleConfirmSuspend}
        />
      )}

      {creatingAccount && (
        <CreateAccountDialog
          banks={banks}
          hospitals={hospitals}
          onCancel={() => setCreatingAccount(false)}
          onCreated={handleAccountCreated}
        />
      )}

      {newCredentials && (
        <NewAccountCredentials
          result={newCredentials}
          onDone={() => setNewCredentials(null)}
        />
      )}
    </>
  );
}

/**
 * The Create Account form.
 *
 * Role comes first because everything below it depends on that answer: a hospital
 * staff account needs a hospital, a blood bank staff account needs a bank, and an
 * administrator needs neither. The matching picker appears only once the role that
 * requires it is chosen, so the form never shows a field that would be refused if
 * it were filled in (Business Rule 14 rejects, rather than ignores, a facility sent
 * for an administrator).
 *
 * Donor is not an option, and this is not merely a display decision: donors create
 * their own accounts through public registration, which also writes the Donor
 * profile row — blood group, date of birth, address — that this form has no fields
 * for. The server refuses the role too; the form simply never offers something that
 * could not work.
 *
 * No password field either. The server generates one and returns it once — see
 * NewAccountCredentials, which is what replaces this dialog on success.
 */
function CreateAccountDialog({ banks, hospitals, onCancel, onCreated }) {
  const [form, setForm] = useState({
    role: "HOSPITAL_STAFF", fullName: "", phone: "", email: "", bloodBankId: "", hospitalId: "",
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const cancelRef = useRef(null);
  const roleRef = useRef(null);

  /** Escape closes the form, and focus starts on the role selector — the field
   *  every other field's meaning depends on. Same handling as the suspend
   *  confirmation, so both dialogs behave identically at the keyboard. */
  useEffect(() => {
    roleRef.current?.focus();
    const onKeyDown = (event) => { if (event.key === "Escape") onCancel(); };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onCancel]);

  function update(field, value) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  /** Changing role clears the facility that no longer applies, so a bank chosen
   *  before switching to Administrator cannot be submitted invisibly. */
  function changeRole(role) {
    setForm((current) => ({ ...current, role, bloodBankId: "", hospitalId: "" }));
    setFieldErrors({});
  }

  const needsHospital = form.role === "HOSPITAL_STAFF";
  const needsBank = form.role === "BLOOD_BANK_STAFF";

  function inputClass(base, field) {
    return `${base}${fieldErrors[field] ? ` ${base}--bad` : ""}`;
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (submitting) return;

    setSubmitting(true);
    setError(null);
    setFieldErrors({});

    try {
      const created = await createStaffAccount({
        fullName: form.fullName.trim(),
        // Sent as typed: the server normalises it (Business Rule 13), and doing
        // it here as well would be a second copy of that rule free to drift.
        phone: form.phone.trim(),
        email: form.email.trim(),
        role: form.role,
        // Only ever the one the chosen role calls for; the other stays null.
        bloodBankId: needsBank ? form.bloodBankId || null : null,
        hospitalId: needsHospital ? form.hospitalId || null : null,
      });
      onCreated(created);
    } catch (err) {
      // Covers a duplicate phone or email (409), a bad phone number or a missing
      // facility (400, keyed to the field). The backend's messages are written
      // for display, so they are shown verbatim.
      setError(err.message);
      setFieldErrors(err.fieldErrors ?? {});
      setSubmitting(false);
    }
  }

  return (
    <div
      className="ad-scrim"
      onMouseDown={(event) => { if (event.target === event.currentTarget && !submitting) onCancel(); }}
    >
      <form
        className="ad-dialog ad-dialog--form"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ad-create-title"
        onSubmit={handleSubmit}
      >
        <div className="ad-dialog-title" id="ad-create-title">Create staff account</div>
        <div className="ad-dialog-body">
          The new account is ready to use immediately. A password is generated for it and
          shown to you once, on the next screen.
        </div>

        {error && <div className="ad-form-alert" role="alert">{error}</div>}

        <div className="ad-form">
          <div className="ad-field">
            <label className="ad-label" htmlFor="ad-role">Role</label>
            <select
              id="ad-role" ref={roleRef} className={inputClass("ad-select", "role")}
              value={form.role} onChange={(e) => changeRole(e.target.value)}
            >
              {CREATABLE_ROLES.map((role) => (
                <option key={role} value={role}>{ROLE_LABEL[role]}</option>
              ))}
            </select>
            {fieldErrors.role && <span className="ad-err">{fieldErrors.role}</span>}
            <span className="ad-hint">Donors sign themselves up and are not created here.</span>
          </div>

          {needsHospital && (
            <div className="ad-field">
              <label className="ad-label" htmlFor="ad-hospital">Hospital</label>
              <select
                id="ad-hospital" className={inputClass("ad-select", "hospitalId")}
                value={form.hospitalId} onChange={(e) => update("hospitalId", e.target.value)}
              >
                <option value="">Select a hospital…</option>
                {hospitals.map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}
              </select>
              {fieldErrors.hospitalId && <span className="ad-err">{fieldErrors.hospitalId}</span>}
            </div>
          )}

          {needsBank && (
            <div className="ad-field">
              <label className="ad-label" htmlFor="ad-bank">Blood bank</label>
              <select
                id="ad-bank" className={inputClass("ad-select", "bloodBankId")}
                value={form.bloodBankId} onChange={(e) => update("bloodBankId", e.target.value)}
              >
                <option value="">Select a blood bank…</option>
                {banks.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
              </select>
              {fieldErrors.bloodBankId && <span className="ad-err">{fieldErrors.bloodBankId}</span>}
            </div>
          )}

          <div className="ad-field">
            <label className="ad-label" htmlFor="ad-fullname">Full name</label>
            <input
              id="ad-fullname" className={inputClass("ad-input", "fullName")} type="text"
              placeholder="e.g. Fatmata Conteh" autoComplete="off"
              value={form.fullName} onChange={(e) => update("fullName", e.target.value)}
            />
            {fieldErrors.fullName && <span className="ad-err">{fieldErrors.fullName}</span>}
          </div>

          <div className="ad-field">
            <label className="ad-label" htmlFor="ad-phone">Phone number</label>
            <input
              id="ad-phone" className={inputClass("ad-input", "phone")} type="tel"
              placeholder="076 900 005 or +232 76 900 005" autoComplete="off"
              value={form.phone} onChange={(e) => update("phone", e.target.value)}
            />
            {fieldErrors.phone && <span className="ad-err">{fieldErrors.phone}</span>}
            <span className="ad-hint">This is what they sign in with.</span>
          </div>

          <div className="ad-field">
            <label className="ad-label" htmlFor="ad-email">Email address</label>
            <input
              id="ad-email" className={inputClass("ad-input", "email")} type="email"
              placeholder="name@example.com" autoComplete="off"
              value={form.email} onChange={(e) => update("email", e.target.value)}
            />
            {fieldErrors.email && <span className="ad-err">{fieldErrors.email}</span>}
          </div>
        </div>

        <div className="ad-dialog-actions">
          <button
            type="button" className="ad-dialog-cancel" ref={cancelRef}
            onClick={onCancel} disabled={submitting}
          >
            Cancel
          </button>
          <button type="submit" className="ad-dialog-confirm ad-dialog-confirm--go" disabled={submitting}>
            {submitting ? "Creating…" : "Create account"}
          </button>
        </div>
      </form>
    </div>
  );
}

/**
 * The one-time credentials screen.
 *
 * This exists because of a fact about the system rather than a design preference:
 * only the BCrypt hash of the password is stored, so there is no endpoint that
 * could show it again — not to this administrator, not to anyone. If it is lost,
 * the account needs a new password, not a lookup. The warning below says so
 * plainly for that reason, and this dialog deliberately has no scrim-click or
 * Escape dismissal: closing it is a decision, not something to do by accident.
 *
 * The administrator relays the password to the new staff member directly. BloodLink
 * sends no email or SMS, so there is no other channel it could travel down.
 */
function NewAccountCredentials({ result, onDone }) {
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  const doneRef = useRef(null);

  useEffect(() => { doneRef.current?.focus(); }, []);

  async function copyPassword() {
    try {
      await navigator.clipboard.writeText(result.temporaryPassword);
      setCopied(true);
      setCopyFailed(false);
      // Reverts to "Copy" so the button can be used again — the password is still
      // on screen, and a confirmation that never resets reads as stuck.
      setTimeout(() => setCopied(false), 2500);
    } catch {
      // Clipboard access can be refused outright (an insecure origin, a browser
      // permission). Saying so is better than a button that silently does
      // nothing, since the password is still readable right above it.
      setCopyFailed(true);
    }
  }

  const account = result.account;

  return (
    <div className="ad-scrim">
      <div
        className="ad-dialog ad-dialog--form"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ad-cred-title"
      >
        <div className="ad-dialog-title" id="ad-cred-title">Account created</div>

        <div className="ad-summary">
          <b>{account.fullName}</b> — {ROLE_LABEL[account.role] ?? account.role}
          {(account.hospitalName || account.bloodBankName) && (
            <> at <b>{account.hospitalName ?? account.bloodBankName}</b></>
          )}
          <br />
          Signs in with <b>{account.phone}</b>
        </div>

        <div className="ad-cred">
          <div className="ad-cred-label">Temporary password</div>
          <div className="ad-cred-row">
            <span className="ad-cred-value">{result.temporaryPassword}</span>
            <button
              type="button"
              className={`ad-copy${copied ? " ad-copy--done" : ""}`}
              onClick={copyPassword}
            >
              {copied ? "Copied" : "Copy"}
            </button>
          </div>
          {copyFailed && (
            <span className="ad-err">Could not copy automatically — select the password above and copy it.</span>
          )}
        </div>

        <div className="ad-warn">
          This password will not be shown again. Write it down or copy it now, and give it to
          {" "}{account.fullName} directly — they should change it after signing in. If it is lost,
          the account has to be created again.
        </div>

        <div className="ad-dialog-actions">
          <button type="button" className="ad-dialog-confirm ad-dialog-confirm--go" ref={doneRef} onClick={onDone}>
            I have saved the password
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * A light confirmation before a suspension is submitted.
 *
 * Deliberately not the Blood Bank Dashboard's RejectConfirmation pattern: that
 * dialog earns its warning block and scary framing because a bank cannot undo
 * its own decline and the request leaves its queue for good. Suspending an
 * account is not like that — it denies access immediately, which is real, but
 * any administrator can reverse it with one more click a moment later, so the
 * dialog says only what is actually true: who this affects and what happens,
 * nothing more alarming than that.
 */
function SuspendConfirmation({ targetUser, cancelRef, onCancel, onConfirm }) {
  return (
    <div
      className="ad-scrim"
      onMouseDown={(event) => { if (event.target === event.currentTarget) onCancel(); }}
    >
      <div
        className="ad-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ad-suspend-title"
        aria-describedby="ad-suspend-body"
      >
        <div className="ad-dialog-title" id="ad-suspend-title">Suspend {targetUser.fullName}?</div>
        <div className="ad-dialog-body" id="ad-suspend-body">
          They will be signed out immediately and unable to sign back in until an
          administrator reactivates the account.
        </div>
        <div className="ad-dialog-actions">
          <button type="button" className="ad-dialog-cancel" ref={cancelRef} onClick={onCancel}>
            Cancel
          </button>
          <button type="button" className="ad-dialog-confirm" onClick={onConfirm}>
            Suspend account
          </button>
        </div>
      </div>
    </div>
  );
}

/* ================= HELPERS ================= */
function roleColor(role) {
  if (role === "DONOR") return "#C8102E";
  if (role === "HOSPITAL_STAFF") return "#1C3A5E";
  if (role === "BLOOD_BANK_STAFF") return "#1E7F45";
  return "#D98A1F"; // ADMIN
}
function rolePill(role) {
  if (role === "DONOR") return "ad-p-donor";
  if (role === "HOSPITAL_STAFF") return "ad-p-hosp";
  if (role === "BLOOD_BANK_STAFF") return "ad-p-bank";
  return "ad-p-admin";
}

/**
 * The donut's four slices, percentages derived from the server's real counts.
 * Rounded independently rather than with largest-remainder adjustment, so the
 * four percentages can land a point or two off 100 in total — a cosmetic
 * rounding artefact common to this kind of legend, not a data error.
 */
function outcomeSlices(outcomes) {
  const pct = (n) => (outcomes.total === 0 ? 0 : Math.round((n / outcomes.total) * 100));
  return [
    { label: "Fulfilled", pct: pct(outcomes.fulfilled), color: "#1E7F45" },
    { label: "Approved", pct: pct(outcomes.approved), color: "#1C3A5E" },
    { label: "Pending", pct: pct(outcomes.pending), color: "#D98A1F" },
    { label: "Rejected", pct: pct(outcomes.rejected), color: "#C8102E" },
  ];
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

/**
 * The vertical scale is derived from the real data rather than a hardcoded 200:
 * six months of a real, small system might never clear single digits, and
 * fixing the axis at 200 would draw every bar as a sliver. Rounded up to a
 * multiple of 4 (minimum 4) so the four gridlines above zero are always whole
 * numbers, the same way the mockup's fixed 0/50/100/150/200 ladder was.
 */
function TrendChart({ data }) {
  const CW = 430, CH = 180, PAD_L = 34, PAD_B = 26, PAD_T = 12;
  const highest = Math.max(0, ...data.map((d) => d.totalDonations));
  const max = Math.max(4, Math.ceil(highest / 4) * 4);
  const plotH = CH - PAD_B - PAD_T;
  const plotW = CW - PAD_L - 10;
  const slot = plotW / Math.max(data.length, 1);
  const barW = Math.min(34, slot * 0.52);
  const ticks = [0, max / 4, max / 2, (max * 3) / 4, max];

  return (
    <svg viewBox={`0 0 ${CW} ${CH}`} role="img"
         aria-label="Bar chart of monthly voluntary donations over the last six months">
      {ticks.map((t) => {
        const y = PAD_T + plotH - (t / max) * plotH;
        return (
          <g key={t}>
            <line x1={PAD_L} y1={y} x2={CW - 10} y2={y} stroke="#EEF0F2" strokeWidth="1" />
            <text x={PAD_L - 7} y={y + 3.5} textAnchor="end"
                  fontSize="9" fill="#9A9A9A" fontFamily="Inter, Arial, sans-serif">{Math.round(t)}</text>
          </g>
        );
      })}
      {data.map((d, i) => {
        const h = (d.totalDonations / max) * plotH;
        const x = PAD_L + slot * i + (slot - barW) / 2;
        const y = PAD_T + plotH - h;
        return (
          <g key={d.month + i}>
            <rect x={x} y={y} width={barW} height={h} rx="3" fill="#C8102E" />
            <text x={x + barW / 2} y={y - 5} textAnchor="middle"
                  fontSize="9.5" fontWeight="700" fill="#333" fontFamily="Inter, Arial, sans-serif">
              {d.totalDonations}
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

function DonutChart({ outcomes }) {
  const R = 52, C = 2 * Math.PI * R;
  const slices = outcomeSlices(outcomes);
  let offset = 0;

  return (
    <svg viewBox="0 0 140 140" role="img" aria-label="Donut chart of blood request outcomes">
      <g transform="translate(70 70) rotate(-90)">
        <circle r={R} fill="none" stroke="#F0F1F3" strokeWidth="20" />
        {outcomes.total === 0 ? null : slices.map((o) => {
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
            fill="#111" fontFamily="Inter, Arial, sans-serif">{outcomes.total.toLocaleString()}</text>
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
/* Drawn white, not currentColor: it only ever sits inside the filled navy
   "Create Account" button, where the surrounding text is white too. */
function PlusIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><path d="M12 5v14M5 12h14"/></svg>); }
function BellIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0"/></svg>); }
function UsersFillIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><circle cx="9" cy="8" r="3.4"/><circle cx="17" cy="9" r="2.7"/><path d="M2 20c0-3.6 3.1-5.8 7-5.8s7 2.2 7 5.8z"/><path d="M16.5 14.4c3 .3 5.5 2.3 5.5 5.6h-5.2c0-2-.4-3.9-.3-5.6z"/></svg>); }
function DropletIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z"/></svg>); }
function BuildingIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><path d="M4 21V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v15M2 21h20"/><path d="M12 8v6M9 11h6"/></svg>); }
function AlertIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W} strokeWidth="2.2"><path d="M12 3 2 20h20L12 3z"/><path d="M12 10v4M12 17.5v.01"/></svg>); }
