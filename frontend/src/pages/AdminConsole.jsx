import React, { useEffect, useRef, useState } from "react";
import {
  getAdminUsers, getUserCounts, createStaffAccount, updateUserStatus, editAccountDetails, getAuditLog,
  getDonationTrends, getRequestOutcomes, createBloodBank, createHospital,
} from "../api/admin.js";
import {
  USER_ACCOUNT_PAGE_SIZES, USER_ACCOUNT_SORTS, USER_ACCOUNT_STATUSES,
  initialUserAccountsState, applyUserAccountsChange, goToUserAccountsPage, toggleUserAccountsDirection,
  buildUserAccountsParams, hasActiveUserAccountFilters, clearUserAccountFilters,
  describeUserAccountsEmptyState, stateAfterAccountCreated, roleCount,
} from "../lib/userAccounts.js";
import { describeShowingRange } from "../lib/pagination.js";
import { getBloodBanks } from "../api/bloodBanks.js";
import { getHospitals } from "../api/hospitals.js";
import ProfileSettings from "../components/ProfileSettings.jsx";
import MobileMenuToggle from "../components/MobileMenuToggle.jsx";
import AdminReports from "./AdminReports.jsx";
import { formatDateTime, formatHeaderDate, formatRelativeTime, initials } from "../lib/dates.js";
import {
  AUDIT_LOG_PAGE_SIZES, canGoToPreviousPage, canGoToNextPage,
  clampPageToAvailable, isAuditLogEmpty, describeAuditLogPage,
} from "../lib/auditLogPaging.js";

/* BloodLink — Admin Console: Dashboard (stats and charts), User Accounts (search, create, edit, suspend),
   Audit Log, Blood Banks/Hospitals, Reports and Settings, switched from the sidebar. */

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

/** The three roles an administrator may create here; donors self-register (Business Rule 8)
 * and the server refuses DONOR anyway. */
const CREATABLE_ROLES = ["HOSPITAL_STAFF", "BLOOD_BANK_STAFF", "ADMIN"];

/** The views this console switches between, held as plain state like BloodBankPortal's,
 * since App.jsx's hash routing only picks the portal. */
const VIEW = {
  DASHBOARD: "dashboard", USERS: "users", BANKS: "banks", HOSPITALS: "hospitals",
  REPORTS: "reports", AUDIT: "audit", SETTINGS: "settings",
};

/** The views that load their own data and so are not held behind the console
 *  overview's loading state or its errors. */
const SELF_LOADING_VIEWS = [VIEW.SETTINGS, VIEW.AUDIT, VIEW.REPORTS];

/** The header's title and subtitle for each view. */
const HEADINGS = {
  [VIEW.DASHBOARD]: { title: "System Administration", sub: "BloodLink · Makeni City deployment" },
  [VIEW.USERS]: { title: "User Accounts", sub: "Create, edit, suspend and reactivate accounts across all four roles" },
  [VIEW.BANKS]: { title: "Blood Banks", sub: "Every blood bank on BloodLink, and adding a new one" },
  [VIEW.HOSPITALS]: { title: "Hospitals", sub: "Every hospital on BloodLink, and adding a new one" },
  [VIEW.REPORTS]: { title: "Reports", sub: "Citywide stock, requests, donations and donors — counts only, read-only" },
  [VIEW.AUDIT]: { title: "Audit Log", sub: "Administrator actions, and stock write-offs and donor blood group changes by bank staff, newest first — permanent and read-only" },
  [VIEW.SETTINGS]: { title: "Profile Settings", sub: "Your name, email and password" },
};

/** How each audit action reads and its badge tone: suspend red, reactivate green, creations (facilities too) navy,
 * edits amber (a donor blood group change too, Business Rule 28), and a write-off red (blood lost). */
const AUDIT_ACTION = {
  ACCOUNT_CREATED: { label: "Created", tone: "ad-au-created" },
  ACCOUNT_EDITED: { label: "Edited", tone: "ad-au-edited" },
  ACCOUNT_SUSPENDED: { label: "Suspended", tone: "ad-au-suspended" },
  ACCOUNT_REACTIVATED: { label: "Reactivated", tone: "ad-au-reactivated" },
  BLOOD_BANK_CREATED: { label: "Created", tone: "ad-au-created" },
  HOSPITAL_CREATED: { label: "Created", tone: "ad-au-created" },
  STOCK_WRITTEN_OFF: { label: "Written off", tone: "ad-au-written-off" },
  DONOR_BLOOD_GROUP_UPDATED: { label: "Blood group", tone: "ad-au-edited" },
};

/** The line under an audit entry's subject: the account's role, the kind of facility, or a stock batch. */
function auditSubjectKind(entry) {
  if (entry.targetType === "BLOOD_BANK") return "Blood bank";
  if (entry.targetType === "HOSPITAL") return "Hospital";
  if (entry.targetType === "BATCH") return "Stock batch";
  return ROLE_LABEL[entry.targetRole] ?? entry.targetRole;
}

/** What the Blood Banks and Hospitals pages differ by — everything else about the
 *  two is the same page (FacilitiesView). */
const FACILITY_KIND = {
  [VIEW.BANKS]: {
    noun: "blood bank", plural: "Blood Banks", create: createBloodBank,
    namePlaceholder: "e.g. Kabala Road Blood Bank",
  },
  [VIEW.HOSPITALS]: {
    noun: "hospital", plural: "Hospitals", create: createHospital,
    namePlaceholder: "e.g. Teko Barracks Hospital",
  },
};

/** The role filter's choices, in the order the buttons appear. "ALL" first, then
 *  the four roles in ROLE_LABEL's own order. */
const ROLE_FILTERS = ["ALL", "DONOR", "HOSPITAL_STAFF", "BLOOD_BANK_STAFF", "ADMIN"];

/**
 * The console shell: sidebar, header, data loading and the current view. Account counts are loaded here and shared,
 * reloaded after account changes; the account list itself is paged by the User Accounts view.
 */
export default function AdminConsole({ user, onSignOut, onUserUpdated }) {
  const [view, setView] = useState(VIEW.DASHBOARD);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  const [userCounts, setUserCounts] = useState(null);
  const [trends, setTrends] = useState([]);
  const [outcomes, setOutcomes] = useState(null);
  // Facility lists from the public endpoints, kept for the stat card's total and the Create Account pickers.
  const [banks, setBanks] = useState([]);
  const [hospitals, setHospitals] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [today] = useState(() => new Date());

  async function load() {
    const [countsData, trendsData, outcomesData, banks, hospitals] = await Promise.all([
      getUserCounts(),
      getDonationTrends(),
      getRequestOutcomes(),
      getBloodBanks(),
      getHospitals(),
    ]);
    return { countsData, trendsData, outcomesData, banks, hospitals };
  }

  /** Re-reads account counts after an account change; on failure the old numbers stay rather than an error banner. */
  async function reloadUserCounts() {
    try {
      setUserCounts(await getUserCounts());
    } catch {
      // Left as it was; the next change or visit will try again.
    }
  }

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    load()
      .then(({ countsData, trendsData, outcomesData, banks: bankRows, hospitals: hospitalRows }) => {
        if (cancelled) return;
        setUserCounts(countsData);
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

  /**
   * Passes the administrator's own name/email change up to App; the accounts table refetches itself anyway.
   */
  function handleUserUpdated(changes) {
    onUserUpdated?.(changes);
  }

  return (
    <div className={`ad-root${mobileMenuOpen ? " is-menu-open" : ""}`}>
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
        /* Grows to fill the Dashboard below the stat cards. It used to be sized to
           its content, with the user table taking the rest of the page; the table
           now lives on User Accounts, so the charts take that space instead of
           leaving it empty. The grid's rows stretch, so both panels match height. */
        .ad-charts { flex: 1 1 auto; min-height: 0; display: grid; grid-template-columns: 1.45fr 1fr; gap: clamp(9px, 1.1vw, 18px); }
        .ad-panel {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(11px, 1.7vh, 18px) clamp(12px, 1.2vw, 20px);
          display: flex; flex-direction: column; min-height: 0;
        }
        .ad-panel-head { flex: 0 0 auto; display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: clamp(6px, 1vh, 12px); }
        .ad-panel-title { font-size: clamp(12px, 1.85vh, 16px); font-weight: 700; letter-spacing: -.01em; }
        .ad-panel-link { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: var(--red); cursor: pointer; }
        /* The chart fills whatever height the panel has, positioned absolutely so
           the SVG takes its size from the box rather than the box from the SVG
           (an SVG sized by height:auto grows with width only, and would never use
           the extra height). min-height is the chart's old fixed size, so on a
           short screen it is never smaller than it used to be — it only grows. */
        .ad-chart-wrap { flex: 1 1 auto; position: relative; min-height: clamp(120px, 20vh, 178px); }
        /* Scaled with the SVG default preserveAspectRatio ("meet"): the chart grows
           until it touches the box's width or height, whichever comes first, and
           is centred in the rest — never stretched out of proportion. */
        .ad-chart-wrap > svg { position: absolute; inset: 0; width: 100%; height: 100%; }

        /* donut + legend */
        /* The donut and its legend fill the chart box together. The donut takes the
           box's full height as a square, capped at 60% of the width so the legend
           beside it always keeps room for its labels. */
        .ad-donut-row { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: clamp(10px, 1.2vw, 20px); }
        .ad-donut-row svg { flex: 0 1 auto; height: 100%; width: auto; aspect-ratio: 1 / 1; max-width: 60%; }
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
        /* Edit sits beside Suspend/Activate. Navy outline: it changes an account
           without granting or removing access, so it takes neither the red of
           Suspend nor the green of Activate. */
        .ad-acts { display: flex; align-items: center; gap: 6px; }
        .ad-act--edit { border-color: rgba(28,58,94,.35); color: var(--navy); }
        .ad-act--edit:hover { background: rgba(28,58,94,.07); border-color: var(--navy); }

        /* ===== AUDIT LOG ===== */
        .ad-table--log td { vertical-align: top; }
        /* Details are sentences ("Blood bank changed from … to …"): wrap them
           instead of cutting them off with an ellipsis like the other cells. */
        .ad-log-details { white-space: normal !important; line-height: 1.45; color: #3A3A3A; }
        .ad-log-when { font-weight: 600; }
        .ad-log-ago { font-size: clamp(8.5px, 1.2vh, 11px); margin-top: 2px; }
        .ad-au-created     { background: var(--navy); }
        .ad-au-edited      { background: var(--amber); }
        .ad-au-suspended   { background: var(--red); }
        .ad-au-reactivated { background: var(--green); }
        .ad-au-written-off { background: var(--red); }

        /* paging footer, below the table */
        .ad-log-pager {
          flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between;
          flex-wrap: wrap; gap: clamp(8px, 1.2vw, 16px);
          margin-top: clamp(8px, 1.2vh, 14px); padding-top: clamp(7px, 1.1vh, 12px);
          border-top: 1px solid var(--line);
        }
        .ad-log-size { display: flex; align-items: center; gap: 7px; font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: var(--ink-muted); }
        .ad-log-size select {
          font-family: inherit; font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 700; color: var(--ink);
          border: 1.5px solid var(--line); border-radius: 6px; background: #FFF;
          padding: 3px clamp(5px, .5vw, 8px); cursor: pointer;
        }
        .ad-log-size select:focus { outline: none; border-color: var(--navy); }
        .ad-log-nav { display: flex; align-items: center; gap: clamp(7px, .9vw, 12px); }
        .ad-log-page-label { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 700; color: #3A3A3A; white-space: nowrap; }

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

        /* ===== SEARCH, STATUS, SORT — User Accounts page ===== */
        .ad-users-controls {
          flex: 0 0 auto; display: flex; align-items: center; flex-wrap: wrap;
          gap: clamp(6px, .6vw, 10px); margin-bottom: clamp(6px, 1vh, 10px);
        }
        .ad-users-search, .ad-users-select, .ad-users-dir, .ad-users-clear {
          font-family: inherit; font-size: clamp(9.5px, 1.35vh, 12px); color: var(--ink);
          border: 1.5px solid var(--line); border-radius: 7px; background: #FFF;
          padding: clamp(4px, .7vh, 7px) clamp(8px, .7vw, 11px);
        }
        .ad-users-search { flex: 1 1 200px; min-width: 150px; }
        .ad-users-select, .ad-users-dir { font-weight: 600; cursor: pointer; }
        .ad-users-search:focus, .ad-users-select:focus { outline: none; border-color: var(--navy); }
        .ad-users-dir:hover, .ad-users-clear:hover { background: #F6F7F8; border-color: #C9CDD3; }
        .ad-users-dir:focus-visible, .ad-users-clear:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }
        /* Text-like, red: it undoes something, and it only appears when there is
           something to undo. */
        .ad-users-clear { color: var(--red); font-weight: 700; cursor: pointer; border-color: transparent; background: transparent; }
        .ad-users-busy { font-size: clamp(9px, 1.25vh, 11px); }
        .ad-users-error { color: var(--red); font-weight: 600; }

        /* Compact rows, so a 13-row page fits a 768px-tall laptop screen with no
           scrolling inside the table. Only this table; the others keep their size. */
        .ad-table--compact td { padding: clamp(3px, .5vh, 6px) 8px; }
        .ad-table--compact .ad-user-av {
          width: clamp(20px, 2.6vh, 26px); height: clamp(20px, 2.6vh, 26px);
          font-size: clamp(8px, 1.1vh, 10px);
        }
        .ad-table--compact .ad-act { padding: clamp(3px, .5vh, 5px) clamp(7px, .7vw, 11px); }

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

        /* ===== BLOOD BANKS / HOSPITALS — Business Rule 19 ===== */
        /* The "add one" panel sits above the list and sizes to its content; the
           list below takes the rest of the page, like the User Accounts table. */
        .ad-fac-top { flex: 0 0 auto; }
        .ad-fac-head { align-items: center; }
        .ad-fac-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: clamp(8px, .9vw, 14px); }
        /* Green: something was added and it worked — the app's success tone. */
        .ad-fac-done {
          background: #E7F2EB; color: var(--green); border-radius: 7px;
          font-size: clamp(10px, 1.4vh, 12.5px); font-weight: 600; line-height: 1.45;
          padding: clamp(7px, 1.1vh, 10px) 11px;
        }
        .ad-fac-done b { color: var(--green); }
        .ad-row-new td { background: #F3F9F5; }

        @media (prefers-reduced-motion: reduce) { .ad-root * { transition: none !important; } }
      `}</style>

      {/* ================= SIDEBAR ================= */}
      {mobileMenuOpen && <button type="button" className="mobile-menu-backdrop" aria-label="Close navigation menu" onClick={() => setMobileMenuOpen(false)} />}
      <aside className="ad-side" id="ad-sidebar">
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

        <nav className="ad-nav" onClick={() => setMobileMenuOpen(false)}>
          <NavButton view={VIEW.DASHBOARD} current={view} onSelect={setView}><GridIcon /> Dashboard</NavButton>
          <NavButton view={VIEW.USERS} current={view} onSelect={setView}><UsersIcon /> User Accounts</NavButton>
          <NavButton view={VIEW.BANKS} current={view} onSelect={setView}><BankIcon /> Blood Banks</NavButton>
          <NavButton view={VIEW.HOSPITALS} current={view} onSelect={setView}><HospitalIcon /> Hospitals</NavButton>
          <NavButton view={VIEW.REPORTS} current={view} onSelect={setView}><ChartIcon /> Reports</NavButton>
          <NavButton view={VIEW.AUDIT} current={view} onSelect={setView}><LogIcon /> Audit Log</NavButton>
          <NavButton view={VIEW.SETTINGS} current={view} onSelect={setView}><GearIcon /> Settings</NavButton>
        </nav>

        <button type="button" className="ad-logout" onClick={onSignOut}><LogoutIcon /> Logout</button>
      </aside>

      {/* ================= MAIN ================= */}
      <main className="ad-main">
        <div className="ad-top">
          <MobileMenuToggle open={mobileMenuOpen} onClick={() => setMobileMenuOpen((open) => !open)} controls="ad-sidebar" />
          <div>
            <div className="ad-title">{HEADINGS[view].title}</div>
            <div className="ad-sub">{HEADINGS[view].sub}</div>
          </div>
          <div className="ad-top-right">
            <div className="ad-date"><CalendarIcon /> Today, {formatHeaderDate(today)}</div>
            {/* Deliberately not a <NotificationBell>: no Notification type is addressed to ADMIN yet,
                so this stays the mockup's static icon. */}
            <div className="ad-bell"><BellIcon /></div>
            <div className="ad-avatar">{initials(user.fullName)}</div>
          </div>
        </div>

        {/* Settings is not held behind the overview data's loading or errors,
            so an administrator can still change their password. */}
        {view === VIEW.SETTINGS && <ProfileSettings onUserUpdated={handleUserUpdated} />}

        {/* Like Settings, the log fetches its own data and does not wait on the
            console overview's. */}
        {view === VIEW.AUDIT && <AuditLogView />}

        {/* Reports (Business Rule 23) loads each of its sections itself. */}
        {view === VIEW.REPORTS && <AdminReports />}

        {!SELF_LOADING_VIEWS.includes(view) && loading && (
          <div className="ad-loading"><div className="ad-sub">Loading system overview…</div></div>
        )}

        {!SELF_LOADING_VIEWS.includes(view) && !loading && error && (
          <div className="ad-error">
            <div className="ad-error-msg">{error}</div>
            <button type="button" className="ad-retry" onClick={() => window.location.reload()}>
              Try again
            </button>
          </div>
        )}

        {!SELF_LOADING_VIEWS.includes(view) && !loading && !error && outcomes && (
          view === VIEW.USERS ? (
            <UserAccountsView
              user={user}
              counts={userCounts}
              onAccountsChanged={reloadUserCounts}
              banks={banks}
              hospitals={hospitals}
            />
          ) : view === VIEW.BANKS || view === VIEW.HOSPITALS ? (
            // Keyed by view so switching between the two pages starts each with
            // its own closed, empty form rather than carrying one over.
            <FacilitiesView
              key={view}
              kind={FACILITY_KIND[view]}
              facilities={view === VIEW.BANKS ? banks : hospitals}
              setFacilities={view === VIEW.BANKS ? setBanks : setHospitals}
            />
          ) : (
            <DashboardView
              counts={userCounts}
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
 * Dashboard: the read-only system summary, four stat cards and two charts;
 * the user counts come from GET /api/admin/reports/users.
 */
function DashboardView({ counts, trends, outcomes, facilityCount }) {
  const totalUsers = roleCount(counts, "");
  const registeredDonors = roleCount(counts, "DONOR");
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
 * User Accounts: create, edit, suspend and reactivate accounts. Search, filters, sort and paging go to the server
 * one page at a time (logic in lib/userAccounts.js); after a change the page is refetched, not patched.
 */
function UserAccountsView({ user, counts, onAccountsChanged, banks, hospitals }) {
  const [query, setQuery] = useState(initialUserAccountsState);
  // What is in the search box right now. It reaches `query` (and the server) only
  // after typing pauses — see the debounce effect below.
  const [searchText, setSearchText] = useState("");
  const [pageData, setPageData] = useState(null);
  const [loadingPage, setLoadingPage] = useState(true);
  const [loadError, setLoadError] = useState(null);
  // Bumped to fetch the same query again after an action changes an account.
  const [reloadToken, setReloadToken] = useState(0);
  const reloadPage = () => setReloadToken((token) => token + 1);

  // Waits 350ms after typing pauses before searching; unchanged text returns the same state, so no extra request.
  useEffect(() => {
    const handle = setTimeout(() => {
      setQuery((current) => (current.q.trim() === searchText.trim()
        ? current
        : applyUserAccountsChange(current, { q: searchText })));
    }, 350);
    return () => clearTimeout(handle);
  }, [searchText]);

  // Fetches exactly one page whenever the query or reload token changes, dropping responses for stale queries.
  useEffect(() => {
    let cancelled = false;
    setLoadingPage(true);
    setLoadError(null);
    getAdminUsers(buildUserAccountsParams(query))
      .then((data) => { if (!cancelled) setPageData(data); })
      .catch((err) => { if (!cancelled) setLoadError(err.message); })
      .finally(() => { if (!cancelled) setLoadingPage(false); });
    return () => { cancelled = true; };
  }, [query, reloadToken]);

  // A page past the end (after a suspend under the Active filter emptied the last
  // page, say) is pulled back to the real last page — the Donors page's rule.
  useEffect(() => {
    if (!pageData) return;
    const clamped = clampPageToAvailable(query.page, pageData.totalPages);
    if (clamped !== query.page) setQuery((current) => goToUserAccountsPage(current, clamped));
  }, [pageData, query.page]);

  function changeQuery(changes) {
    setQuery((current) => applyUserAccountsChange(current, changes));
  }

  function clearFilters() {
    setSearchText("");
    setQuery((current) => clearUserAccountFilters(current));
  }

  // The Create Account form and its credentials screen, separate state because the password screen
  // outlives the form until dismissed.
  const [creatingAccount, setCreatingAccount] = useState(false);
  const [newCredentials, setNewCredentials] = useState(null);

  // Which row's status is mid-write and the last write failure, shown against that row.
  const [updatingUserId, setUpdatingUserId] = useState(null);
  const [actionError, setActionError] = useState(null);

  // The user awaiting a confirmed Suspend, or null when the dialog is closed.
  const [confirmingSuspend, setConfirmingSuspend] = useState(null);
  const cancelSuspendRef = useRef(null);

  // The account whose phone / facility is being edited, or null (Business Rule 17).
  const [editing, setEditing] = useState(null);

  // Guards the async status write against this view having been left (the
  // administrator clicking Dashboard mid-request) before the response arrives.
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  /** Escape closes the confirmation and focus moves to Cancel, as in the Dashboard's Reject dialog. */
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
   * Writes one status change, then refetches the page, since the account may no longer belong under the filter.
   */
  async function applyStatus(targetUserId, status) {
    setUpdatingUserId(targetUserId);
    setActionError(null);
    try {
      await updateUserStatus(targetUserId, status);
      if (!mountedRef.current) return;
      reloadPage();
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

  /** After a phone or facility edit, fetches the page again — the same approach as
   *  applyStatus, since a phone search may no longer match the edited account. */
  function handleEdited() {
    setEditing(null);
    reloadPage();
  }

  /**
   * After creation: refetch the page and reload counts, switching a filter that would hide the new account.
   * The password lives in state only while the credentials screen is open, never stored.
   */
  function handleAccountCreated(result) {
    const next = stateAfterAccountCreated(query, result.account);
    if (next === query) reloadPage(); else setQuery(next);
    onAccountsChanged?.();
    setCreatingAccount(false);
    setNewCredentials(result);
  }

  const rows = pageData?.content ?? [];
  const totalElements = pageData?.totalElements ?? 0;
  const totalPages = pageData?.totalPages ?? 0;
  const emptyMessage = pageData && !loadError ? describeUserAccountsEmptyState(totalElements, query) : null;
  const filtersActive = hasActiveUserAccountFilters(query) || searchText.trim() !== "";

  return (
    <>
      <div className="ad-panel ad-table-panel">
        <div className="ad-panel-head">
          <div className="ad-panel-title">All Accounts</div>
          <div className="ad-panel-head-right">
            {loadingPage && pageData && <div className="ad-muted ad-users-busy" role="status">Updating…</div>}
            {/* The only way to create a staff or administrator account
                without writing SQL — see Business Rule 14. */}
            <button type="button" className="ad-new" onClick={() => setCreatingAccount(true)}>
              <PlusIcon /> Create Account
            </button>
          </div>
        </div>

        {/* The role filter buttons, applied by the server, with per-role totals from the database;
            aria-pressed marks the active role. */}
        <div className="ad-filters" role="group" aria-label="Filter accounts by role">
          {ROLE_FILTERS.map((role) => {
            const value = role === "ALL" ? "" : role;
            return (
              <button
                key={role}
                type="button"
                className={`ad-filter${query.role === value ? " is-on" : ""}`}
                aria-pressed={query.role === value}
                onClick={() => changeQuery({ role: value })}
              >
                {role === "ALL" ? "All" : ROLE_LABEL[role]}
                <span className="ad-filter-count">{roleCount(counts, value)}</span>
              </button>
            );
          })}
        </div>

        {/* Search, status filter and sort: one request with the role filter above. */}
        <div className="ad-users-controls">
          <input
            className="ad-users-search" type="search" placeholder="Search name, phone or email…"
            aria-label="Search accounts by name, phone or email"
            value={searchText} onChange={(e) => setSearchText(e.target.value)}
          />
          <select
            className="ad-users-select" aria-label="Filter by status"
            value={query.status} onChange={(e) => changeQuery({ status: e.target.value })}
          >
            <option value="">All statuses</option>
            {USER_ACCOUNT_STATUSES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
          </select>
          <select
            className="ad-users-select" aria-label="Sort by"
            value={query.sort} onChange={(e) => changeQuery({ sort: e.target.value })}
          >
            {USER_ACCOUNT_SORTS.map((s) => <option key={s.value} value={s.value}>Sort: {s.label}</option>)}
          </select>
          <button
            type="button" className="ad-users-dir"
            aria-label={query.direction === "asc" ? "Sorted ascending — switch to descending" : "Sorted descending — switch to ascending"}
            onClick={() => setQuery((current) => toggleUserAccountsDirection(current))}
          >
            {query.direction === "asc" ? "↑ Asc" : "↓ Desc"}
          </button>
          {filtersActive && (
            <button type="button" className="ad-users-clear" onClick={clearFilters}>Clear filters</button>
          )}
        </div>

        <div className="ad-table-scroll">
          <table className="ad-table ad-table--compact">
            <thead>
              <tr>
                <th style={{ width: "22%" }}>User</th>
                <th style={{ width: "15%" }}>Role</th>
                <th style={{ width: "19%" }}>Facility</th>
                <th style={{ width: "10%" }}>Status</th>
                <th style={{ width: "12%" }}>Joined</th>
                <th style={{ width: "22%" }}>Action</th>
              </tr>
            </thead>
            <tbody>
              {!pageData && loadingPage && !loadError && (
                <tr><td colSpan={6} className="ad-empty">Loading accounts…</td></tr>
              )}
              {loadError && (
                // Not a sign-out: apiRequest only clears the session on a session-level 401.
                <tr>
                  <td colSpan={6} className="ad-empty">
                    <span className="ad-users-error">Could not load accounts: {loadError}</span>{" "}
                    <button type="button" className="ad-act ad-act--edit" onClick={reloadPage}>Try again</button>
                  </td>
                </tr>
              )}
              {emptyMessage && (
                <tr><td colSpan={6} className="ad-empty">{emptyMessage}</td></tr>
              )}
              {!loadError && rows.map((u) => {
                const facility = u.bloodBankName ?? u.hospitalName;
                const busy = updatingUserId === u.id;
                // The backend refuses self-suspension (409) too, but a button that explains itself is better.
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
                      <div className="ad-acts">
                        {/* Disabled on your own row, as the server refuses it:
                            nobody changes their own phone (Business Rule 16). */}
                        <button
                          type="button"
                          className="ad-act ad-act--edit"
                          disabled={busy || isSelf}
                          title={isSelf ? "Another administrator has to change your phone or facility." : undefined}
                          onClick={() => setEditing(u)}
                        >
                          Edit
                        </button>
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
                      </div>
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

        {/* "Showing 1-13 of N", Previous / Next (disabled at either end) and a page
            size limited to what fits on screen without scrolling. */}
        <div className="ad-log-pager">
          <label className="ad-log-size">
            Rows per page
            <select value={query.size} onChange={(e) => changeQuery({ size: Number(e.target.value) })}>
              {USER_ACCOUNT_PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
          <div className="ad-log-nav">
            <span className="ad-log-page-label">{describeShowingRange(query.page, query.size, totalElements)}</span>
            <button
              type="button" className="ad-act ad-act--edit"
              disabled={!canGoToPreviousPage(query.page) || loadingPage}
              onClick={() => setQuery((current) => goToUserAccountsPage(current, current.page - 1))}
            >
              Previous
            </button>
            <button
              type="button" className="ad-act ad-act--edit"
              disabled={!canGoToNextPage(query.page, totalPages) || loadingPage}
              onClick={() => setQuery((current) => goToUserAccountsPage(current, current.page + 1))}
            >
              Next
            </button>
          </div>
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

      {editing && (
        <EditAccountDialog
          account={editing}
          banks={banks}
          hospitals={hospitals}
          onCancel={() => setEditing(null)}
          onSaved={handleEdited}
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
 * Edit another account's phone and facility (Business Rule 17), pre-filled; Save enables only on a change.
 * The facility picker matches the existing role, which itself is not editable here.
 */
function EditAccountDialog({ account, banks, hospitals, onCancel, onSaved }) {
  const needsHospital = account.role === "HOSPITAL_STAFF";
  const needsBank = account.role === "BLOOD_BANK_STAFF";

  const [phone, setPhone] = useState(account.phone);
  const [hospitalId, setHospitalId] = useState(account.hospitalId ?? "");
  const [bloodBankId, setBloodBankId] = useState(account.bloodBankId ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const phoneRef = useRef(null);

  /** Escape closes, and focus starts on the phone — the same keyboard handling
   *  as this screen's other dialogs. */
  useEffect(() => {
    phoneRef.current?.focus();
    const onKeyDown = (event) => { if (event.key === "Escape" && !saving) onCancel(); };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onCancel, saving]);

  const changed =
    phone.trim() !== account.phone
    || (needsHospital && hospitalId !== (account.hospitalId ?? ""))
    || (needsBank && bloodBankId !== (account.bloodBankId ?? ""));

  function inputClass(base, field) {
    return `${base}${fieldErrors[field] ? ` ${base}--bad` : ""}`;
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (saving || !changed) return;

    setSaving(true);
    setError(null);
    setFieldErrors({});
    try {
      const updated = await editAccountDetails(account.id, {
        // Sent as typed; the server normalises it (Business Rule 13).
        phone: phone.trim(),
        hospitalId: needsHospital ? hospitalId || null : null,
        bloodBankId: needsBank ? bloodBankId || null : null,
      });
      onSaved(updated);
    } catch (err) {
      // A phone already in use (409), a malformed phone or a missing facility (400,
      // keyed to the field). The backend's messages are written for display.
      setError(err.message);
      setFieldErrors(err.fieldErrors ?? {});
      setSaving(false);
    }
  }

  return (
    <div
      className="ad-scrim"
      onMouseDown={(event) => { if (event.target === event.currentTarget && !saving) onCancel(); }}
    >
      <form
        className="ad-dialog ad-dialog--form"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ad-edit-title"
        onSubmit={handleSubmit}
        noValidate
      >
        <div className="ad-dialog-title" id="ad-edit-title">Edit {account.fullName}</div>
        <div className="ad-dialog-body">
          Changes to the sign-in phone number take effect at their next sign-in. Every change is
          recorded in the Audit Log under your name.
        </div>

        {error && <div className="ad-form-alert" role="alert">{error}</div>}

        <div className="ad-form">
          <div className="ad-field">
            <span className="ad-label">Role</span>
            <div>
              <span className={`ad-pill ${rolePill(account.role)}`}>{ROLE_LABEL[account.role] ?? account.role}</span>
            </div>
            <span className="ad-hint">Role cannot be changed here.</span>
          </div>

          <div className="ad-field">
            <label className="ad-label" htmlFor="ad-edit-phone">Phone number</label>
            <input
              id="ad-edit-phone" ref={phoneRef} className={inputClass("ad-input", "phone")} type="tel"
              autoComplete="off" placeholder="076 900 005 or +232 76 900 005"
              value={phone} onChange={(e) => setPhone(e.target.value)}
            />
            {fieldErrors.phone && <span className="ad-err">{fieldErrors.phone}</span>}
            <span className="ad-hint">This is what they sign in with.</span>
          </div>

          {needsHospital && (
            <div className="ad-field">
              <label className="ad-label" htmlFor="ad-edit-hospital">Hospital</label>
              <select
                id="ad-edit-hospital" className={inputClass("ad-select", "hospitalId")}
                value={hospitalId} onChange={(e) => setHospitalId(e.target.value)}
              >
                <option value="">Select a hospital…</option>
                {hospitals.map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}
              </select>
              {fieldErrors.hospitalId && <span className="ad-err">{fieldErrors.hospitalId}</span>}
            </div>
          )}

          {needsBank && (
            <div className="ad-field">
              <label className="ad-label" htmlFor="ad-edit-bank">Blood bank</label>
              <select
                id="ad-edit-bank" className={inputClass("ad-select", "bloodBankId")}
                value={bloodBankId} onChange={(e) => setBloodBankId(e.target.value)}
              >
                <option value="">Select a blood bank…</option>
                {banks.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
              </select>
              {fieldErrors.bloodBankId && <span className="ad-err">{fieldErrors.bloodBankId}</span>}
            </div>
          )}
        </div>

        <div className="ad-dialog-actions">
          <button type="button" className="ad-dialog-cancel" onClick={onCancel} disabled={saving}>
            Cancel
          </button>
          <button type="submit" className="ad-dialog-confirm ad-dialog-confirm--go" disabled={saving || !changed}>
            {saving ? "Saving…" : "Save changes"}
          </button>
        </div>
      </form>
    </div>
  );
}

/**
 * Blood Banks / Hospitals (Business Rule 19): the list plus an inline "Create New" form; create only.
 * The list is the shell's shared one, so a new facility appears everywhere without a reload.
 */
function FacilitiesView({ kind, facilities, setFacilities }) {
  const [creating, setCreating] = useState(false);
  const [justAdded, setJustAdded] = useState(null);

  /** Adds the new row in name order — the order the GET endpoint returns — so it
   *  sits where a reload would put it. */
  function handleCreated(created) {
    setFacilities((current) => [...current, created].sort((a, b) => a.name.localeCompare(b.name)));
    setCreating(false);
    setJustAdded(created);
  }

  return (
    <>
      <div className="ad-panel ad-fac-top">
        <div className="ad-panel-head ad-fac-head">
          <div className="ad-panel-title">Add a {kind.noun}</div>
          {!creating && (
            <button type="button" className="ad-new" onClick={() => { setCreating(true); setJustAdded(null); }}>
              <PlusIcon /> Create New
            </button>
          )}
        </div>

        {creating ? (
          <CreateFacilityForm kind={kind} onCancel={() => setCreating(false)} onCreated={handleCreated} />
        ) : justAdded ? (
          <div className="ad-fac-done" role="status">
            <b>{justAdded.name}</b> has been added and recorded in the Audit Log.
          </div>
        ) : (
          <div className="ad-hint">
            New {kind.noun}s are available immediately — in staff pickers and across BloodLink. They cannot be
            edited or removed here once created, so check the name before saving.
          </div>
        )}
      </div>

      <div className="ad-panel ad-table-panel">
        <div className="ad-panel-head">
          <div className="ad-panel-title">All {kind.plural}</div>
          <div className="ad-panel-link">{facilities.length} total</div>
        </div>
        <div className="ad-table-scroll">
          <table className="ad-table">
            <thead>
              <tr>
                <th style={{ width: "40%" }}>Name</th>
                <th style={{ width: "35%" }}>Location</th>
                <th style={{ width: "25%" }}>Contact phone</th>
              </tr>
            </thead>
            <tbody>
              {facilities.length === 0 && (
                <tr><td colSpan={3} className="ad-empty">No {kind.noun}s on record.</td></tr>
              )}
              {facilities.map((f) => (
                <tr key={f.id} className={justAdded?.id === f.id ? "ad-row-new" : undefined}>
                  <td><span className="ad-user-name">{f.name}</span></td>
                  <td>{f.location}</td>
                  <td className="ad-muted">{f.contactPhone}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

/**
 * The inline "Create New" form: three required fields, server messages shown by each field;
 * a duplicate name is a 409, and the phone is sent as typed.
 */
function CreateFacilityForm({ kind, onCancel, onCreated }) {
  const [form, setForm] = useState({ name: "", location: "", contactPhone: "" });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const nameRef = useRef(null);

  useEffect(() => { nameRef.current?.focus(); }, []);

  function update(field, value) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  function inputClass(field) {
    return `ad-input${fieldErrors[field] ? " ad-input--bad" : ""}`;
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (submitting) return;

    setSubmitting(true);
    setError(null);
    setFieldErrors({});
    try {
      const created = await kind.create({
        name: form.name.trim(),
        location: form.location.trim(),
        contactPhone: form.contactPhone.trim(),
      });
      onCreated(created);
    } catch (err) {
      setError(err.message);
      setFieldErrors(err.fieldErrors ?? {});
      setSubmitting(false);
    }
  }

  return (
    <form className="ad-form" onSubmit={handleSubmit} noValidate aria-label={`New ${kind.noun}`}>
      {error && <div className="ad-form-alert" role="alert">{error}</div>}

      <div className="ad-fac-fields">
        <div className="ad-field">
          <label className="ad-label" htmlFor="ad-fac-name">Name</label>
          <input
            id="ad-fac-name" ref={nameRef} className={inputClass("name")} type="text" autoComplete="off"
            placeholder={kind.namePlaceholder}
            value={form.name} onChange={(e) => update("name", e.target.value)}
          />
          {fieldErrors.name && <span className="ad-err">{fieldErrors.name}</span>}
        </div>

        <div className="ad-field">
          <label className="ad-label" htmlFor="ad-fac-location">Location</label>
          <input
            id="ad-fac-location" className={inputClass("location")} type="text" autoComplete="off"
            placeholder="e.g. Kabala Road, Makeni"
            value={form.location} onChange={(e) => update("location", e.target.value)}
          />
          {fieldErrors.location && <span className="ad-err">{fieldErrors.location}</span>}
        </div>

        <div className="ad-field">
          <label className="ad-label" htmlFor="ad-fac-phone">Contact phone</label>
          <input
            id="ad-fac-phone" className={inputClass("contactPhone")} type="tel" autoComplete="off"
            placeholder="e.g. 076 900 005"
            value={form.contactPhone} onChange={(e) => update("contactPhone", e.target.value)}
          />
          {fieldErrors.contactPhone && <span className="ad-err">{fieldErrors.contactPhone}</span>}
        </div>
      </div>

      <div className="ad-dialog-actions">
        <button type="button" className="ad-dialog-cancel" onClick={onCancel} disabled={submitting}>
          Cancel
        </button>
        <button type="submit" className="ad-dialog-confirm ad-dialog-confirm--go" disabled={submitting}>
          {submitting ? "Saving…" : `Create ${kind.noun}`}
        </button>
      </div>
    </form>
  );
}

/**
 * Audit Log (Business Rule 17): read-only, newest first, one page at a time, fetched fresh on each change
 * so new entries always show. Paging arithmetic lives in lib/auditLogPaging.js.
 */
function AuditLogView() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(AUDIT_LOG_PAGE_SIZES[0]);
  const [pageData, setPageData] = useState(null); // { content, page, size, totalElements, totalPages }
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    getAuditLog(page, size)
      .then((data) => { if (!cancelled) setPageData(data); })
      .catch((err) => { if (!cancelled) setError(err.message); });
    return () => { cancelled = true; };
  }, [page, size]);

  // Once the real page count is known, pull a stale page number back to the last page.
  useEffect(() => {
    if (!pageData) return;
    const clamped = clampPageToAvailable(page, pageData.totalPages);
    if (clamped !== page) setPage(clamped);
  }, [pageData, page]);

  if (error) {
    // A failed load here never ends the session, so the administrator can simply try again.
    return <div className="ad-error"><div className="ad-error-msg">{error}</div></div>;
  }
  if (!pageData) {
    return <div className="ad-loading"><div className="ad-sub">Loading audit log…</div></div>;
  }

  const { content: entries, totalElements, totalPages } = pageData;

  return (
    <div className="ad-panel ad-table-panel">
      <div className="ad-panel-head">
        <div className="ad-panel-title">Recorded actions</div>
        <div className="ad-panel-link">{totalElements} total</div>
      </div>
      <div className="ad-table-scroll">
        <table className="ad-table ad-table--log">
          <thead>
            <tr>
              <th style={{ width: "15%" }}>When</th>
              <th style={{ width: "11%" }}>Action</th>
              <th style={{ width: "16%" }}>Performed by</th>
              {/* "Subject" rather than "Account": since Business Rule 19 an entry can
                  be about a blood bank or hospital as well as an account. */}
              <th style={{ width: "20%" }}>Subject</th>
              <th style={{ width: "38%" }}>Details</th>
            </tr>
          </thead>
          <tbody>
            {isAuditLogEmpty(totalElements) && (
              <tr><td colSpan={5} className="ad-empty">Nothing has been recorded yet.</td></tr>
            )}
            {entries.map((entry) => {
              const action = AUDIT_ACTION[entry.action] ?? { label: entry.action, tone: "" };
              return (
                <tr key={entry.id}>
                  <td>
                    <div className="ad-log-when">{formatDateTime(entry.createdAt)}</div>
                    <div className="ad-muted ad-log-ago">{formatRelativeTime(entry.createdAt)}</div>
                  </td>
                  <td><span className={`ad-badge ${action.tone}`}>{action.label}</span></td>
                  <td>{entry.actorName}</td>
                  <td>
                    <div className="ad-user-name">{entry.targetName}</div>
                    <div className="ad-muted ad-log-ago">{auditSubjectKind(entry)}</div>
                  </td>
                  <td className="ad-log-details">{entry.details}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <div className="ad-log-pager">
        <label className="ad-log-size">
          Rows per page
          <select
            value={size}
            onChange={(e) => setSize(Number(e.target.value))}
          >
            {AUDIT_LOG_PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
        <div className="ad-log-nav">
          <button
            type="button"
            className="ad-act ad-act--edit"
            disabled={!canGoToPreviousPage(page)}
            onClick={() => setPage((p) => p - 1)}
          >
            Previous
          </button>
          <span className="ad-log-page-label">{describeAuditLogPage(page, totalPages)}</span>
          <button
            type="button"
            className="ad-act ad-act--edit"
            disabled={!canGoToNextPage(page, totalPages)}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * The Create Account form: role first, then only the facility picker that role needs (Business Rule 14).
 * No Donor option and no password field; the server generates one (see NewAccountCredentials).
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

  /** Escape closes the form and focus starts on the role selector, as in the suspend confirmation. */
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
      // Shows a duplicate phone/email (409) or a bad phone or missing facility (400) verbatim, as worded by the backend.
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
 * The one-time credentials screen: only the hash is stored, so the password can never be shown again.
 * No scrim-click or Escape dismissal; the administrator relays it in person.
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
      // Clipboard access can be refused, so say so rather than silently failing; the password is still visible.
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
 * A light confirmation before suspending: access is denied immediately but any administrator can reverse it,
 * so it says only who is affected and what happens.
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

/** A sidebar entry wired to one of the console's views: highlighted, and marked
 *  as the current page for screen readers, when that view is showing. */
function NavButton({ view, current, onSelect, children }) {
  const active = view === current;
  return (
    <button
      type="button"
      className={active ? "is-active" : ""}
      aria-current={active ? "page" : undefined}
      onClick={() => onSelect(view)}
    >
      {children}
    </button>
  );
}

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
 * The donut's four slices from the server's counts, each rounded independently,
 * so the total may be a point or two off 100.
 */
function outcomeSlices(outcomes) {
  const pct = (n) => (outcomes.total === 0 ? 0 : Math.round((n / outcomes.total) * 100));
  return [
    { label: "Fulfilled", pct: pct(outcomes.fulfilled), color: "#1E7F45" },
    // The slice "Approved" used to hold. Since Business Rule 20 a request is never
    // approved — it is filled cooperatively, so the middle state is part-filled.
    { label: "Partly filled", pct: pct(outcomes.partiallyFulfilled), color: "#1C3A5E" },
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
 * The vertical scale follows the data (rounded up to a multiple of 4, minimum 4)
 * so small real numbers aren't drawn as slivers.
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
