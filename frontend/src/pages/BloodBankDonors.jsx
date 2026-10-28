import React, { useEffect, useRef, useState } from "react";
import { getDonorRoster, getDonorHistoryAtBank, correctDonorBloodGroup } from "../api/donors.js";
import {
  BLOOD_GROUP_NOTE_MAX, bloodGroupEditProblems, describeBloodGroupEditQuestion,
} from "../lib/bloodGroup.js";
import { RecordDonationModal } from "./BloodBankInventory.jsx";
import { formatFullDate, formatHeaderDate } from "../lib/dates.js";
import {
  canGoToPreviousPage, canGoToNextPage, clampPageToAvailable, describePage, DEFAULT_PAGE_SIZES,
} from "../lib/pagination.js";
import {
  initialDonorRosterState, applyDonorRosterFilterChange, goToDonorRosterPage,
  buildDonorRosterParams, describeDonorRosterEmptyState, describeDonorEligibility,
} from "../lib/donorRoster.js";
import NotificationBell from "../components/NotificationBell.jsx";
import MobileMenuToggle from "../components/MobileMenuToggle.jsx";
import { useNotifications } from "../realtime/useNotifications.js";

/* BloodLink — Blood Bank: Donors (Business Rule 21): donors with a donation at this bank, paged and filtered,
   with a history side panel and a "Record donation" shortcut reusing RecordDonationModal. */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };
const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-"];

export default function BloodBankDonors({ bloodBankId, user, onSignOut, onNavigate }) {
  const [filters, setFilters] = useState(initialDonorRosterState());
  const [searchText, setSearchText] = useState("");
  const [pageData, setPageData] = useState(null);
  const [error, setError] = useState(null);
  const [today] = useState(() => new Date());
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [selectedDonor, setSelectedDonor] = useState(null); // a roster row, or null
  const [recordingDonationFor, setRecordingDonationFor] = useState(null); // a roster row, or null
  const [editingGroupFor, setEditingGroupFor] = useState(null); // a roster row, or null

  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  // Debounces the search box so each keystroke isn't a request; kept here so the lib stays pure functions.
  useEffect(() => {
    const handle = setTimeout(() => {
      setFilters((current) => applyDonorRosterFilterChange(current, { q: searchText }));
    }, 350);
    return () => clearTimeout(handle);
  }, [searchText]);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    getDonorRoster(buildDonorRosterParams(filters), bloodBankId)
      .then((data) => { if (!cancelled) setPageData(data); })
      .catch((err) => { if (!cancelled) setError(err.message); });
    return () => { cancelled = true; };
  }, [filters, bloodBankId]);

  // Once the real page count is known, pull a stale page number back to the last page.
  useEffect(() => {
    if (!pageData) return;
    const clamped = clampPageToAvailable(filters.page, pageData.totalPages);
    if (clamped !== filters.page) {
      setFilters((current) => goToDonorRosterPage(current, clamped));
    }
  }, [pageData, filters.page]);

  const { notifications, unreadCount, connected, markAllRead } = useNotifications();

  function updateFilter(changes) {
    setFilters((current) => applyDonorRosterFilterChange(current, changes));
  }

  /** After a donation or a blood group edit, close every overlay and reload, since the donor's row changed. */
  async function handleDonorChanged() {
    setRecordingDonationFor(null);
    setEditingGroupFor(null);
    setSelectedDonor(null);
    try {
      const data = await getDonorRoster(buildDonorRosterParams(filters), bloodBankId);
      if (mountedRef.current) setPageData(data);
    } catch {
      // Left as it was; the next load or action will try again.
    }
  }

  const rows = pageData?.content ?? [];
  const totalElements = pageData?.totalElements ?? 0;
  const totalPages = pageData?.totalPages ?? 0;
  const emptyMessage = pageData ? describeDonorRosterEmptyState(totalElements, filters) : null;

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

        /* ===== CONTROLS ===== */
        .bd-controls {
          flex: 0 0 auto; display: flex; align-items: center; gap: clamp(8px, 1vw, 14px);
          flex-wrap: wrap;
        }
        .bd-search {
          flex: 1 1 220px; min-width: 160px; font-family: inherit;
          font-size: clamp(11px, 1.55vh, 13.5px); padding: clamp(7px, 1.1vh, 10px) 11px;
          border: 1.5px solid var(--line); border-radius: 7px; background: #FFF; color: var(--ink);
        }
        .bd-search:focus { outline: none; border-color: var(--navy); }
        .bd-select {
          font-family: inherit; font-size: clamp(10.5px, 1.5vh, 13px); font-weight: 600;
          padding: clamp(6px, 1vh, 9px) 10px; border: 1.5px solid var(--line); border-radius: 7px;
          background: #FFF; color: var(--ink); cursor: pointer;
        }
        .bd-select:focus { outline: none; border-color: var(--navy); }

        /* ===== TABLE ===== */
        .bd-panel {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(11px, 1.7vh, 18px) clamp(12px, 1.2vw, 20px);
          display: flex; flex-direction: column; min-height: 0; flex: 1 1 auto;
        }
        .bd-panel-head { flex: 0 0 auto; display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin-bottom: clamp(6px, 1vh, 12px); }
        .bd-panel-title { font-size: clamp(12px, 1.85vh, 16px); font-weight: 700; letter-spacing: -.01em; }
        .bd-panel-link { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: var(--red); }
        .bd-table-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
        .bd-table { width: 100%; border-collapse: collapse; }
        .bd-table th {
          text-align: left; font-size: clamp(8.5px, 1.22vh, 11px); font-weight: 700; color: var(--ink-muted);
          text-transform: uppercase; letter-spacing: .05em;
          padding: 0 8px clamp(5px, .9vh, 9px); border-bottom: 1.5px solid var(--line);
          position: sticky; top: 0; background: var(--card-bg);
        }
        .bd-table td {
          font-size: clamp(10px, 1.48vh, 13px); padding: clamp(5px, .95vh, 10px) 8px;
          border-bottom: 1px solid var(--line);
        }
        .bd-table tr:last-child td { border-bottom: none; }
        .bd-row { cursor: pointer; }
        .bd-row:hover td { background: #F6F7F8; }
        .bd-name { font-weight: 700; }
        .bd-grp { font-weight: 800; letter-spacing: -.02em; }
        .bd-muted { color: var(--ink-muted); }
        .bd-badge { font-size: clamp(8.5px, 1.18vh, 11px); font-weight: 700; padding: 3px clamp(7px, .7vw, 11px); border-radius: 20px; color: #FFF; white-space: nowrap; }
        .bd-b-green { background: var(--green); }
        .bd-b-amber { background: var(--amber); }
        .bd-empty { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); padding: clamp(8px, 1.4vh, 14px) 2px; }
        .bd-edit-btn { white-space: nowrap; }
        .bd-sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }

        /* paging footer */
        .bd-pager {
          flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between;
          flex-wrap: wrap; gap: clamp(8px, 1.2vw, 16px);
          margin-top: clamp(8px, 1.2vh, 14px); padding-top: clamp(7px, 1.1vh, 12px);
          border-top: 1px solid var(--line);
        }
        .bd-pager-size { display: flex; align-items: center; gap: 7px; font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: var(--ink-muted); }
        .bd-pager-nav { display: flex; align-items: center; gap: clamp(7px, .9vw, 12px); }
        .bd-pager-label { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 700; color: #3A3A3A; white-space: nowrap; }
        .bd-pager-btn {
          background: transparent; border: 1.5px solid rgba(28,58,94,.35); color: var(--navy);
          border-radius: 6px; cursor: pointer; font-family: inherit; font-weight: 700;
          font-size: clamp(9px, 1.28vh, 11.5px); padding: clamp(4px, .7vh, 7px) clamp(8px, .8vw, 12px);
        }
        .bd-pager-btn:hover { background: rgba(28,58,94,.07); }
        .bd-pager-btn:disabled { border-color: var(--line); color: var(--ink-muted); cursor: not-allowed; background: transparent; }

        /* wiring states */
        .bd-loading, .bd-error { flex: 1 1 auto; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; text-align: center; }
        .bd-error-msg { color: var(--red); font-weight: 600; font-size: clamp(11px, 1.6vh, 14px); }

        /* ===== DONOR HISTORY PANEL ===== */
        .bd-panel-backdrop {
          position: fixed; inset: 0; z-index: 30; background: rgba(17,17,17,.45);
          display: flex; justify-content: flex-end;
        }
        .bd-side-panel {
          background: var(--card-bg); width: min(420px, 100%); height: 100%;
          box-shadow: -8px 0 30px rgba(0,0,0,.18); padding: clamp(16px, 2.6vh, 26px);
          display: flex; flex-direction: column; gap: clamp(10px, 1.6vh, 16px); overflow-y: auto;
        }
        .bd-side-panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
        .bd-side-panel-title { font-size: clamp(15px, 2.2vh, 19px); font-weight: 800; letter-spacing: -.02em; }
        .bd-side-panel-sub { font-size: clamp(10px, 1.45vh, 12.5px); color: var(--ink-muted); margin-top: 3px; }
        .bd-panel-close {
          background: transparent; border: none; cursor: pointer; color: var(--ink-muted);
          font-size: 20px; line-height: 1; padding: 2px 6px;
        }
        .bd-history-row {
          display: flex; align-items: center; justify-content: space-between; gap: 8px;
          padding: clamp(7px, 1.1vh, 10px) 0; border-bottom: 1px solid var(--line);
          font-size: clamp(10px, 1.45vh, 13px);
        }
        .bd-history-row:last-child { border-bottom: none; }
        .bd-history-date { font-weight: 600; }
        .bd-history-units { color: var(--ink-muted); }
        .bd-record-btn {
          background: var(--navy); color: #FFF; border: none; border-radius: 8px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(11px, 1.6vh, 13.5px);
          padding: clamp(9px, 1.5vh, 12px); display: flex; align-items: center; justify-content: center; gap: 8px;
        }
        .bd-record-btn:hover { background: var(--navy-soft); }

        /* ===== the Record Donation modal this page reuses from BloodBankInventory.jsx —
           same class names, since that component's JSX refers to them directly. ===== */
        .bi-modal-backdrop {
          position: fixed; inset: 0; background: rgba(17,17,17,.45);
          display: flex; align-items: center; justify-content: center; z-index: 40;
        }
        .bi-modal {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: 0 12px 40px rgba(0,0,0,.25);
          width: min(420px, calc(100vw - 32px)); padding: clamp(16px, 2.6vh, 26px);
          font-family: inherit; color: var(--ink);
        }
        .bi-modal-title { font-size: clamp(15px, 2.2vh, 19px); font-weight: 800; letter-spacing: -.02em; margin-bottom: 4px; }
        .bi-modal-sub { font-size: clamp(10.5px, 1.5vh, 13px); color: var(--ink-muted); margin-bottom: clamp(12px, 2vh, 18px); }
        .bi-field { display: flex; flex-direction: column; gap: 5px; margin-bottom: clamp(10px, 1.6vh, 15px); }
        .bi-field label { font-size: clamp(10px, 1.4vh, 12.5px); font-weight: 600; color: #3D3D3D; }
        .bi-field select, .bi-field input, .bi-field textarea {
          width: 100%; font-family: inherit; font-size: clamp(11.5px, 1.6vh, 14px); font-weight: 500;
          color: var(--ink); padding: clamp(8px, 1.35vh, 11px) 12px;
          border: 1.5px solid var(--line); border-radius: 8px; background: #FBFBFC;
        }
        .bi-field select:focus, .bi-field input:focus, .bi-field textarea:focus { outline: none; border-color: var(--navy); background: #FFF; }
        .bi-field textarea { resize: vertical; min-height: 64px; }
        .bi-field-count { font-size: clamp(9px, 1.25vh, 11px); color: var(--ink-muted); align-self: flex-end; }
        .bi-field-err { font-size: clamp(9px, 1.25vh, 11px); font-weight: 500; color: var(--red); }
        .bi-field-note { font-size: clamp(9px, 1.25vh, 11px); font-weight: 600; color: var(--green); }
        .bi-modal-alert {
          background: rgba(200,16,46,.07); border: 1.5px solid rgba(200,16,46,.28); color: #8E0B21;
          border-radius: 8px; padding: clamp(7px, 1.2vh, 10px) 12px;
          font-size: clamp(10px, 1.42vh, 12.5px); font-weight: 500; margin-bottom: clamp(9px, 1.5vh, 14px);
        }
        .bi-modal-actions { display: flex; gap: 10px; margin-top: clamp(4px, 1vh, 8px); }
        .bi-modal-cancel {
          flex: 1 1 auto; background: #FFF; border: 1.5px solid var(--line); border-radius: 8px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(11px, 1.6vh, 13.5px); color: #3D3D3D;
          padding: clamp(9px, 1.5vh, 12px);
        }
        .bi-modal-submit {
          flex: 1 1 auto; background: var(--red); color: #FFF; border: none; border-radius: 8px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(11px, 1.6vh, 13.5px);
          padding: clamp(9px, 1.5vh, 12px);
        }
        .bi-modal-submit:hover { background: var(--red-dark); }
        .bi-modal-submit:disabled { opacity: .65; cursor: progress; }
        .bi-lookup { display: flex; gap: 8px; align-items: flex-start; }
        .bi-lookup input { flex: 1 1 auto; }
        .bi-lookup-btn {
          flex: 0 0 auto; background: var(--navy); color: #FFF; border: none; border-radius: 8px;
          cursor: pointer; font-family: inherit; font-weight: 700; font-size: clamp(11px, 1.55vh, 13px);
          padding: clamp(8px, 1.35vh, 11px) clamp(11px, 1.1vw, 15px); white-space: nowrap;
        }
        .bi-lookup-btn:hover { background: var(--navy-soft); }
        .bi-lookup-btn:disabled { background: #C9CDD3; cursor: not-allowed; }
        .bi-donor-card {
          background: #F6F7F8; border: 1.5px solid var(--line); border-radius: 8px;
          padding: clamp(9px, 1.5vh, 13px) clamp(11px, 1.1vw, 15px); margin-bottom: clamp(10px, 1.7vh, 16px);
        }
        .bi-donor-name { font-size: clamp(12px, 1.75vh, 14.5px); font-weight: 800; letter-spacing: -.01em; }
        .bi-donor-meta { font-size: clamp(9.5px, 1.35vh, 12px); color: var(--ink-muted); margin-top: 3px; line-height: 1.45; }
        .bi-donor-warn {
          margin-top: 8px; font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; line-height: 1.45;
          color: #8A5510; background: #FBF1E3; border-left: 3px solid var(--amber); border-radius: 6px;
          padding: clamp(7px, 1.1vh, 10px) clamp(9px, .9vw, 12px);
        }
        .bi-donor-ok { margin-top: 8px; font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: var(--green); }

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
          <button type="button" className="is-active"><UsersIcon /> Donors</button>
          <button type="button"><ChartIcon /> Report</button>
          <button type="button" onClick={() => onNavigate("settings")}><GearIcon /> Settings</button>
        </nav>

        <button type="button" className="bd-logout" onClick={onSignOut}><LogoutIcon /> Logout</button>
      </aside>

      <main className="bd-main">
        <div className="bd-top">
          <MobileMenuToggle open={mobileMenuOpen} onClick={() => setMobileMenuOpen((open) => !open)} controls="bd-sidebar" />
          <div>
            <div className="bd-title">Donors</div>
            <div className="bd-sub">Donors who have donated at this blood bank</div>
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

        <div className="bd-controls">
          <input
            className="bd-search" type="text" placeholder="Search by name or phone…"
            value={searchText} onChange={(e) => setSearchText(e.target.value)}
          />
          <select
            className="bd-select" value={filters.bloodType}
            onChange={(e) => updateFilter({ bloodType: e.target.value })}
          >
            <option value="">All blood groups</option>
            {BLOOD_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}
          </select>
          <select
            className="bd-select" value={filters.eligibility}
            onChange={(e) => updateFilter({ eligibility: e.target.value })}
          >
            <option value="">Any eligibility</option>
            <option value="ELIGIBLE">Eligible</option>
            <option value="IN_RECOVERY">In recovery</option>
          </select>
          <select
            className="bd-select" value={filters.sort}
            onChange={(e) => updateFilter({ sort: e.target.value })}
          >
            <option value="lastDonation">Sort: Last donation</option>
            <option value="name">Sort: Name</option>
          </select>
        </div>

        {error && (
          <div className="bd-error"><div className="bd-error-msg">{error}</div></div>
        )}

        {!error && !pageData && (
          <div className="bd-loading"><div className="bd-sub">Loading donors…</div></div>
        )}

        {!error && pageData && (
          <div className="bd-panel">
            <div className="bd-panel-head">
              <div className="bd-panel-title">Donor roster</div>
              <div className="bd-panel-link">{totalElements} total</div>
            </div>
            <div className="bd-table-scroll">
              <table className="bd-table">
                <thead>
                  <tr>
                    <th style={{ width: "19%" }}>Name</th>
                    <th style={{ width: "10%" }}>Blood group</th>
                    <th style={{ width: "15%" }}>Phone</th>
                    <th style={{ width: "15%" }}>Last donation (any bank)</th>
                    <th style={{ width: "16%" }}>Eligibility</th>
                    <th style={{ width: "10%" }}>Donations here</th>
                    <th style={{ width: "15%" }}><span className="bd-sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {emptyMessage && (
                    <tr><td colSpan={7} className="bd-empty">{emptyMessage}</td></tr>
                  )}
                  {rows.map((row) => {
                    const badge = describeDonorEligibility(
                      row.eligibility, row.eligibleFrom ? formatFullDate(row.eligibleFrom) : null);
                    return (
                      <tr key={row.donorId} className="bd-row" onClick={() => setSelectedDonor(row)}>
                        <td className="bd-name">{row.fullName}</td>
                        <td className="bd-grp">{row.bloodType ?? "—"}</td>
                        <td>{row.phone}</td>
                        <td className={row.lastDonationDate ? "" : "bd-muted"}>
                          {row.lastDonationDate ? formatFullDate(row.lastDonationDate) : "Never"}
                        </td>
                        <td><span className={`bd-badge bd-b-${badge.tone}`}>{badge.text}</span></td>
                        <td>{row.donationsHere}</td>
                        <td>
                          {/* Its own action, so the click must not also open the history panel. */}
                          <button
                            type="button" className="bd-pager-btn bd-edit-btn"
                            aria-label={`Edit ${row.fullName}'s blood group`}
                            onClick={(e) => { e.stopPropagation(); setEditingGroupFor(row); }}
                          >
                            Edit blood group
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="bd-pager">
              <label className="bd-pager-size">
                Rows per page
                <select
                  className="bd-select" value={filters.size}
                  onChange={(e) => updateFilter({ size: Number(e.target.value) })}
                >
                  {DEFAULT_PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </label>
              <div className="bd-pager-nav">
                <button
                  type="button" className="bd-pager-btn"
                  disabled={!canGoToPreviousPage(filters.page)}
                  onClick={() => setFilters((current) => goToDonorRosterPage(current, current.page - 1))}
                >
                  Previous
                </button>
                <span className="bd-pager-label">{describePage(filters.page, totalPages)}</span>
                <button
                  type="button" className="bd-pager-btn"
                  disabled={!canGoToNextPage(filters.page, totalPages)}
                  onClick={() => setFilters((current) => goToDonorRosterPage(current, current.page + 1))}
                >
                  Next
                </button>
              </div>
            </div>
          </div>
        )}
      </main>

      {selectedDonor && (
        <DonorHistoryPanel
          donor={selectedDonor}
          bloodBankId={bloodBankId}
          onClose={() => setSelectedDonor(null)}
          onRecordDonation={() => setRecordingDonationFor(selectedDonor)}
        />
      )}

      {recordingDonationFor && (
        <RecordDonationModal
          bloodBankId={bloodBankId}
          initialPhone={recordingDonationFor.phone}
          onClose={() => setRecordingDonationFor(null)}
          onRecorded={handleDonorChanged}
        />
      )}

      {editingGroupFor && (
        <EditBloodGroupModal
          donor={editingGroupFor}
          onClose={() => setEditingGroupFor(null)}
          onSaved={handleDonorChanged}
        />
      )}
    </div>
  );
}

/**
 * "Edit blood group" (Business Rule 28): the tested group and a required note, then a confirmation step;
 * the checks are lib/bloodGroup.js's, and the server still decides.
 */
function EditBloodGroupModal({ donor, onClose, onSaved }) {
  const [bloodType, setBloodType] = useState("");
  const [note, setNote] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [fieldErrors, setFieldErrors] = useState({});
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  function handleContinue(event) {
    event.preventDefault();
    const problems = bloodGroupEditProblems({ currentBloodType: donor.bloodType, bloodType, note });
    setFieldErrors(problems);
    setError(null);
    if (Object.keys(problems).length === 0) setConfirming(true);
  }

  async function handleConfirm() {
    setSaving(true);
    setError(null);
    try {
      await correctDonorBloodGroup(donor.donorId, { bloodType, note: note.trim() });
      await onSaved();
    } catch (err) {
      // Back to the form so a field the server refused can be corrected.
      setError(err.message);
      setFieldErrors(err.fieldErrors ?? {});
      setConfirming(false);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="bi-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <form className="bi-modal" onSubmit={handleContinue} noValidate>
        <div className="bi-modal-title">Edit blood group</div>
        <div className="bi-modal-sub">
          {donor.fullName} · {donor.bloodType ? `currently ${donor.bloodType}` : "no group on file"}.
          Use this after a test, when no donation is being recorded.
        </div>

        {error && <div className="bi-modal-alert" role="alert">{error}</div>}

        <div className="bi-field">
          <label htmlFor="edit-group">Blood Group (as tested)</label>
          <select id="edit-group" value={bloodType} disabled={confirming}
                  onChange={(e) => setBloodType(e.target.value)}>
            <option value="">Select tested group…</option>
            {BLOOD_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}
          </select>
          {fieldErrors.bloodType && <span className="bi-field-err">{fieldErrors.bloodType}</span>}
        </div>

        <div className="bi-field">
          <label htmlFor="edit-group-note">Note — how or where the test was done</label>
          <textarea id="edit-group-note" value={note} disabled={confirming} maxLength={BLOOD_GROUP_NOTE_MAX}
                    placeholder="e.g. Retested at our lab, sample 4471"
                    onChange={(e) => setNote(e.target.value)} />
          <span className="bi-field-count">{note.length} / {BLOOD_GROUP_NOTE_MAX}</span>
          {fieldErrors.note && <span className="bi-field-err">{fieldErrors.note}</span>}
        </div>

        {confirming ? (
          <div className="bi-donor-warn" role="alert">
            <strong>{describeBloodGroupEditQuestion(donor.fullName, donor.bloodType, bloodType)}</strong>
            <div className="bi-modal-actions">
              <button type="button" className="bi-modal-cancel" onClick={() => setConfirming(false)}>Go back</button>
              <button type="button" className="bi-modal-submit" disabled={saving} onClick={handleConfirm}>
                {saving ? "Saving…" : "Confirm"}
              </button>
            </div>
          </div>
        ) : (
          <div className="bi-modal-actions">
            <button type="button" className="bi-modal-cancel" onClick={onClose}>Cancel</button>
            <button type="submit" className="bi-modal-submit">Continue</button>
          </div>
        )}
      </form>
    </div>
  );
}

/**
 * One donor's history at this bank in a side panel; "Record donation" opens the existing RecordDonationModal,
 * pre-filled with their phone.
 */
function DonorHistoryPanel({ donor, bloodBankId, onClose, onRecordDonation }) {
  const [history, setHistory] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    setHistory(null);
    setError(null);
    getDonorHistoryAtBank(donor.donorId, bloodBankId)
      .then((data) => { if (!cancelled) setHistory(data); })
      .catch((err) => { if (!cancelled) setError(err.message); });
    return () => { cancelled = true; };
  }, [donor.donorId, bloodBankId]);

  return (
    <div className="bd-panel-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="bd-side-panel" role="dialog" aria-modal="true" aria-label={`${donor.fullName}'s history at this bank`}>
        <div className="bd-side-panel-head">
          <div>
            <div className="bd-side-panel-title">{donor.fullName}</div>
            <div className="bd-side-panel-sub">{donor.phone} · {donor.bloodType ?? "No group on file"}</div>
          </div>
          <button type="button" className="bd-panel-close" onClick={onClose} aria-label="Close">×</button>
        </div>

        <button type="button" className="bd-record-btn" onClick={onRecordDonation}>
          <DropletOutlineIcon /> Record donation
        </button>

        {error && <div className="bd-error-msg">{error}</div>}
        {!error && !history && <div className="bd-sub">Loading history…</div>}
        {!error && history && history.length === 0 && (
          <div className="bd-empty">No donations recorded at this bank yet.</div>
        )}
        {!error && history && history.length > 0 && (
          <div>
            {history.map((item, index) => (
              <div className="bd-history-row" key={index}>
                <span className="bd-history-date">{formatFullDate(item.donationDate)}</span>
                <span className="bd-grp">{item.bloodType}</span>
                <span className="bd-history-units">{item.unitsDonated} unit{item.unitsDonated === 1 ? "" : "s"}</span>
              </div>
            ))}
          </div>
        )}
      </div>
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
