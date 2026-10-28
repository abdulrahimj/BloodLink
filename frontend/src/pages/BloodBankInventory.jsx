import React, { useEffect, useRef, useState } from "react";
import { getInventory, getInventorySummary, addInventoryBatch } from "../api/inventory.js";
import { getAppealReach, sendDonationAppeal } from "../api/appeals.js";
import { lookupDonorByPhone, recordDonation } from "../api/donations.js";
import { formatDateTime, formatFullDate, formatHeaderDate } from "../lib/dates.js";
import { describeAppealReach, donorCount } from "../lib/appealReach.js";
import { appealButtonLabel as getAppealButtonLabel, appealButtonText as getAppealButtonText } from "../lib/appealStatus.js";
import NotificationBell from "../components/NotificationBell.jsx";
import MobileMenuToggle from "../components/MobileMenuToggle.jsx";
import { useNotifications } from "../realtime/useNotifications.js";
import { refreshesInventory } from "../lib/batchStock.js";
import {
  describeDonationGroupCheck, describeDonationGroupChangeQuestion, describeDonationRecorded,
  isUnconfirmedBloodGroupChange, needsBloodGroupChangeConfirmation,
} from "../lib/bloodGroup.js";
import BatchDialog from "./BloodBankBatches.jsx";

/* BloodLink — Blood Bank: Blood Inventory: usable, expiring and expired stock per group, each group's batches
   (history, write-off), Add New Batch, Record Donation and Send Appeal on Low/Critical rows, with the live bell. */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };
const ICON_W = { fill: "none", stroke: "#FFF", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-"];
const STATUS_COLOR = { SUFFICIENT: "#1E7F45", LOW: "#D98A1F", CRITICAL: "#C8102E" };

function titleCase(status) {
  return status.charAt(0) + status.slice(1).toLowerCase();
}

/** Statuses that show an appeal button at all (Low, Critical); whether it can be pressed
 * is the server's answer (canSendAppeal). */
function needsDonors(status) {
  return status === "LOW" || status === "CRITICAL";
}

/**
 * Whether the Send Appeal button is live, from the server, since it depends on the bank's last appeal for the group.
 */
function canSendAppeal(item) {
  return item.appealAvailability === "AVAILABLE";
}

/**
 * "Appeal Sent." on a cooling-down row tells the operator their earlier click worked; otherwise "Send Appeal".
 */
function appealButtonText(item) {
  return getAppealButtonText(item);
}

/** The same distinction spelled out for screen readers, saying why the button is disabled. */
function appealButtonLabel(item) {
  return getAppealButtonLabel(item);
}

export default function BloodBankInventory({ bloodBankId, user, onSignOut, onNavigate }) {
  const [stock, setStock] = useState([]);
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [today] = useState(() => new Date());
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [batchModalOpen, setBatchModalOpen] = useState(false);
  const [donationModalOpen, setDonationModalOpen] = useState(false);
  // Separate from appealNotice, so a donation receipt never overwrites an unread appeal receipt.
  const [donationNotice, setDonationNotice] = useState(null);

  // The appeal awaiting confirmation (null when closed): group, fetched reach, failure and in-flight flag
  // in one object so the dialog can't mix groups.
  const [appeal, setAppeal] = useState(null);
  // What the last appeal did, shown in the header until the next one. Cleared on
  // a reload so a stale "Appeal sent" is not left sitting over refreshed figures.
  const [appealNotice, setAppealNotice] = useState(null);
  // The group whose batches are open (null when closed); refreshKey tells that list to re-read with the page.
  const [batchGroup, setBatchGroup] = useState(null);
  const [refreshKey, setRefreshKey] = useState(0);
  // Only a bank's own staff change stock (Business Rule 8); the server refuses anyone else as well.
  const canChangeStock = user.role === "BLOOD_BANK_STAFF";

  // Guards setState after unmount for both fetches; reset on every mount because StrictMode's
  // simulated unmount would otherwise disable load() for good.
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; };
  }, []);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [inventory, summaryData] = await Promise.all([
        getInventory(bloodBankId),
        getInventorySummary(bloodBankId),
      ]);
      if (!mountedRef.current) return;
      setStock(inventory);
      setSummary(summaryData);
    } catch (err) {
      if (mountedRef.current) setError(err.message);
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bloodBankId]);

  /**
   * Re-fetches without the loading placeholder or error banner, so a background refresh
   * never blanks or replaces figures someone is reading.
   */
  async function refreshQuietly() {
    try {
      const [inventory, summaryData] = await Promise.all([
        getInventory(bloodBankId),
        getInventorySummary(bloodBankId),
      ]);
      if (!mountedRef.current) return;
      setStock(inventory);
      setSummary(summaryData);
      setRefreshKey((key) => key + 1);
    } catch {
      // Left as it was; the next alert or a manual reload will try again.
    }
  }

  // The header bell, same session as the Dashboard; stock, appeal-response and expiry notices refresh this screen.
  const { notifications, unreadCount, connected, markAllRead } = useNotifications({
    onNotification: (notification) => {
      if (refreshesInventory(notification.type)) refreshQuietly();
    },
  });

  const total = summary?.totalUnits ?? 0;
  const expiring = summary?.expiringSoonUnits ?? 0;
  const critical = summary?.criticalGroupsCount ?? 0;
  const unitsIssued = summary?.unitsIssuedThisMonth ?? 0;
  const composition = summary?.composition ?? [];
  const expiredUnits = summary?.expiredUnits ?? 0;
  const openGroup = stock.find((s) => s.bloodType === batchGroup) ?? null;

  /** Re-fetches after "Add New Batch" so every card, chart and row shows what the server stored. */
  async function handleBatchAdded() {
    setBatchModalOpen(false);
    await load();
  }

  /**
   * Reloads after a donation is recorded, as for a batch; the notice quotes the receipt's own figure from the write.
   */
  async function handleDonationRecorded(receipt) {
    setDonationModalOpen(false);
    setDonationNotice(describeDonationRecorded(receipt));
    await load();
  }

  /**
   * Opens the appeal confirmation at once and fetches its reach; confirm stays disabled
   * until the count arrives, so nobody sends blind.
   */
  function openAppeal(bloodType) {
    setAppeal({ bloodType, reach: null, error: null, sending: false });

    getAppealReach(bloodType)
      .then((reach) => {
        if (!mountedRef.current) return;
        // Ignore a stale response for a different group, so one group's count never shows under another's heading.
        setAppeal((current) =>
          current && current.bloodType === bloodType ? { ...current, reach } : current);
      })
      .catch((err) => {
        if (!mountedRef.current) return;
        setAppeal((current) =>
          current && current.bloodType === bloodType ? { ...current, error: err.message } : current);
      });
  }

  /**
   * Sends the shown appeal (bloodBankId only matters for an ADMIN); on success the notice uses
   * the response's figures, which are the truth.
   */
  async function confirmAppeal() {
    const { bloodType } = appeal;
    setAppeal((current) => ({ ...current, sending: true, error: null }));

    try {
      const receipt = await sendDonationAppeal({ bloodType, bloodBankId });
      if (!mountedRef.current) return;
      setAppeal(null);
      setAppealNotice(
        receipt.donorsNotified === 0
          ? `No appeal was delivered — no eligible ${bloodType} donor is on the register right now.`
          : `Appeal sent — ${donorCount(receipt.donorsNotified)} of ${bloodType} notified.`
      );
      // Re-read quietly so this row's button shows "Appeal Sent." at once (Business Rule 12)
      // without blanking the screen or losing the notice.
      refreshQuietly();
    } catch (err) {
      if (!mountedRef.current) return;
      setAppeal((current) => (current ? { ...current, sending: false, error: err.message } : current));
      // Also re-read on failure: a 409 usually means this row is out of date, so show the state that refused it.
      refreshQuietly();
    }
  }

  return (
    <div className={`bi-root${mobileMenuOpen ? " is-menu-open" : ""}`}>
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
        .bi-addbtn:disabled { opacity: .6; cursor: not-allowed; }
        .bi-addbtn svg { width: clamp(13px, 1.8vh, 16px); height: clamp(13px, 1.8vh, 16px); }

        /* Record Donation — the second way units enter this bank. */
        .bi-donatebtn {
          background: transparent; color: var(--navy);
          border: 1.5px solid rgba(28,58,94,.35); border-radius: 7px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(10.5px, 1.5vh, 13px);
          padding: clamp(8px, 1.25vh, 11px) clamp(12px, 1.2vw, 18px);
          display: inline-flex; align-items: center; gap: 7px; white-space: nowrap;
        }
        .bi-donatebtn:hover { background: rgba(28,58,94,.06); border-color: var(--navy); }
        .bi-donatebtn:disabled { opacity: .6; cursor: not-allowed; }
        .bi-donatebtn svg { width: clamp(13px, 1.8vh, 16px); height: clamp(13px, 1.8vh, 16px); }

        /* donor lookup inside the Record Donation modal */
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
          padding: clamp(9px, 1.5vh, 13px) clamp(11px, 1.1vw, 15px);
          margin-bottom: clamp(10px, 1.7vh, 16px);
        }
        .bi-donor-name { font-size: clamp(12px, 1.75vh, 14.5px); font-weight: 800; letter-spacing: -.01em; }
        .bi-donor-meta { font-size: clamp(9.5px, 1.35vh, 12px); color: var(--ink-muted); margin-top: 3px; line-height: 1.45; }
        /* Shown when the donor is still inside their recovery window, as the reason
           the form below it is locked. Amber to match this app's other "not yet"
           states; the refusal itself is enforced by the backend (409), not by this. */
        .bi-donor-warn {
          margin-top: 8px; font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; line-height: 1.45;
          color: #8A5510; background: #FBF1E3;
          border-left: 3px solid var(--amber); border-radius: 6px;
          padding: clamp(7px, 1.1vh, 10px) clamp(9px, .9vw, 12px);
        }
        .bi-donor-ok {
          margin-top: 8px; font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600;
          color: var(--green);
        }

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

        .bi-tablepanel { flex: 1 1 auto; min-height: 0; overflow: auto; }
        .bi-expired { color: var(--red); font-weight: 800; }
        .bi-ok { color: var(--green); }
        /* Opens the group's batches: outlined navy, so it never competes with the red Send Appeal.
           Slimmer than Send Appeal, so eight rows that all carry one still fit a 768px-tall screen. */
        .bi-batchbtn {
          background: transparent; color: var(--navy);
          border: 1.5px solid rgba(28,58,94,.3); border-radius: 6px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(9px, 1.28vh, 11.5px); line-height: 1.3;
          padding: clamp(1px, .3vh, 4px) clamp(7px, .7vw, 11px); white-space: nowrap;
        }
        .bi-batchbtn:hover { background: rgba(28,58,94,.06); border-color: var(--navy); }
        .bi-batchbtn:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }
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

        /* Send Appeal — a row-level action, so it is sized and weighted below the
           header's "Add New Batch" and outlined rather than filled. Two solid red
           buttons on one screen would read as two equally primary actions, when
           adding stock is the page's main job and appealing is a response to a
           particular row. */
        .bi-appealbtn {
          background: transparent; color: var(--red);
          border: 1.5px solid rgba(200,16,46,.35); border-radius: 6px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(9px, 1.28vh, 11.5px);
          padding: clamp(4px, .7vh, 7px) clamp(7px, .7vw, 11px);
          white-space: nowrap;
        }
        .bi-appealbtn:hover { background: rgba(200,16,46,.07); border-color: var(--red); }
        .bi-appealbtn:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }
        /* Unavailable: either this bank appealed for the group recently, or it is
           no longer short enough to appeal about (Business Rule 12). Greyed and
           inert rather than hidden, so the row still shows that the action exists
           and is simply not open right now — a cell that emptied itself after a
           successful send would read as the button having failed. Hover and
           active-red are both dropped, since neither is true of it any more. */
        .bi-appealbtn:disabled {
          color: var(--ink-muted); border-color: var(--line);
          background: transparent; cursor: default;
        }
        .bi-appealbtn:disabled:hover { background: transparent; border-color: var(--line); }

        /* What the last appeal did. Green because it reports a completed action,
           matching how this design system already uses green for success. */
        .bi-notice {
          flex: 0 0 auto;
          background: rgba(30,127,69,.08); border: 1.5px solid rgba(30,127,69,.28); color: #146034;
          border-radius: 8px; padding: clamp(7px, 1.2vh, 10px) clamp(10px, 1vw, 14px);
          font-size: clamp(10px, 1.42vh, 12.5px); font-weight: 600;
          display: flex; align-items: center; gap: 10px;
        }
        .bi-notice button {
          margin-left: auto; background: transparent; border: none; cursor: pointer;
          font-family: inherit; font-size: clamp(10px, 1.42vh, 12.5px); font-weight: 700;
          color: #146034; opacity: .7; padding: 0 2px;
        }
        .bi-notice button:hover { opacity: 1; }

        /* The appeal confirmation reuses the batch modal's shell, then deliberately
           stops there: no warning block, no red confirm. It is a lighter step than
           the Dashboard's Reject dialog because it is a lighter action. */
        .bi-confirm-fact {
          background: #F6F7F8; border: 1.5px solid var(--line); border-radius: 8px;
          padding: clamp(9px, 1.5vh, 13px) clamp(11px, 1.1vw, 15px);
          margin-bottom: clamp(10px, 1.7vh, 16px);
        }
        .bi-confirm-count { font-size: clamp(12.5px, 1.85vh, 15px); font-weight: 800; letter-spacing: -.01em; }
        .bi-confirm-note { font-size: clamp(9.5px, 1.35vh, 12px); color: var(--ink-muted); margin-top: 4px; line-height: 1.45; }
        .bi-confirm-send {
          flex: 1 1 auto; background: var(--navy); color: #FFF; border: none; border-radius: 8px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(11px, 1.6vh, 13.5px);
          padding: clamp(9px, 1.5vh, 12px);
        }
        .bi-confirm-send:hover { background: var(--navy-soft); }
        .bi-confirm-send:disabled { background: #C9CDD3; cursor: not-allowed; }

        /* Wiring states — additive, same tokens, for real async data. */
        .bi-loading, .bi-error { flex: 1 1 auto; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; text-align: center; }
        .bi-error-msg { color: var(--red); font-weight: 600; font-size: clamp(11px, 1.6vh, 14px); }

        /* Add New Batch — new UI, built from this file's own tokens; the mockup's
           button had no behaviour to preserve. */
        .bi-modal-backdrop {
          position: fixed; inset: 0; background: rgba(17,17,17,.45);
          display: flex; align-items: center; justify-content: center; z-index: 20;
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
        .bi-field select, .bi-field input {
          width: 100%; font-family: inherit; font-size: clamp(11.5px, 1.6vh, 14px); font-weight: 500;
          color: var(--ink); padding: clamp(8px, 1.35vh, 11px) 12px;
          border: 1.5px solid var(--line); border-radius: 8px; background: #FBFBFC;
        }
        .bi-field select:focus, .bi-field input:focus { outline: none; border-color: var(--navy); background: #FFF; }
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

        @media (prefers-reduced-motion: reduce) { .bi-root * { transition: none !important; } }
      `}</style>

      {mobileMenuOpen && <button type="button" className="mobile-menu-backdrop" aria-label="Close navigation menu" onClick={() => setMobileMenuOpen(false)} />}
      <aside className="bi-side" id="bi-sidebar">
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

        <div className="bi-role">{user.role === "ADMIN" ? "ADMINISTRATOR" : "BLOOD BANK STAFF"}</div>

        <nav className="bi-nav" onClick={() => setMobileMenuOpen(false)}>
          <button type="button" onClick={() => onNavigate("dashboard")}><GridIcon /> Dashboard</button>
          <button type="button" className="is-active"><DropletOutlineIcon /> Blood Inventory</button>
          <button type="button"><TruckIcon /> Blood Logistics</button>
          <button type="button" onClick={() => onNavigate("donors")}><UsersIcon /> Donors</button>
          <button type="button"><ChartIcon /> Report</button>
          <button type="button" onClick={() => onNavigate("settings")}><GearIcon /> Settings</button>
        </nav>

        <button type="button" className="bi-logout" onClick={onSignOut}><LogoutIcon /> Logout</button>
      </aside>

      <main className="bi-main">
        <div className="bi-top">
          <MobileMenuToggle open={mobileMenuOpen} onClick={() => setMobileMenuOpen((open) => !open)} controls="bi-sidebar" />
          <div>
            <div className="bi-title">Blood Inventory</div>
            <div className="bi-sub">{summary?.bloodBankName ?? "Loading…"}</div>
          </div>
          <div className="bi-top-right">
            <div className="bi-date"><CalendarIcon /> Today, {formatHeaderDate(today)}</div>
            {/* Placed like the bell on the other screens, keeping "Add New Batch" rightmost in the header. */}
            <NotificationBell
              notifications={notifications}
              unreadCount={unreadCount}
              connected={connected}
              onMarkAllRead={markAllRead}
            />
            {/* Record Donation sits beside Add New Batch (both add stock), outlined so Add New Batch stays primary. */}
            {canChangeStock && (
              <>
                <button type="button" className="bi-donatebtn" disabled={loading}
                        onClick={() => setDonationModalOpen(true)}>
                  <DropletOutlineIcon /> Record Donation
                </button>
                <button type="button" className="bi-addbtn" disabled={loading} onClick={() => setBatchModalOpen(true)}>
                  <PlusIcon /> Add New Batch
                </button>
              </>
            )}
          </div>
        </div>

        {/* Shown above the panels, since the dialog is gone: this is a receipt for what already happened. */}
        {appealNotice && (
          <div className="bi-notice" role="status">
            {appealNotice}
            <button type="button" onClick={() => setAppealNotice(null)} aria-label="Dismiss">Dismiss</button>
          </div>
        )}

        {donationNotice && (
          <div className="bi-notice" role="status">
            {donationNotice}
            <button type="button" onClick={() => setDonationNotice(null)} aria-label="Dismiss">Dismiss</button>
          </div>
        )}

        {loading && (
          <div className="bi-loading"><div className="bi-sub">Loading inventory…</div></div>
        )}

        {!loading && error && (
          <div className="bi-error"><div className="bi-error-msg">{error}</div></div>
        )}

        {!loading && !error && summary && (
          <>
            <div className="bi-stats">
              <Stat tone="red" icon={<DropletIcon />} figure={total} label="Total Units" note="Usable, all blood groups" />
              <Stat tone="amber" icon={<ClockIcon />} figure={expiring} label="Expiring Soon"
                    note={`Within ${summary.expiringSoonWindowDays} days`} />
              <Stat tone="green" icon={<SwapIcon />} figure={unitsIssued} label="Units Issued" note="This month" />
              <Stat tone="navy" icon={<AlertIcon />} figure={critical} label="Critical Groups" note="Below safe level" />
            </div>

            <div className="bi-charts">
              <div className="bi-panel">
                <div className="bi-panel-head">
                  <div className="bi-panel-title">Units by Blood Group</div>
                  <div className="bi-panel-link">Live stock</div>
                </div>
                <div className="bi-chart-wrap"><GroupChart stock={stock} /></div>
              </div>

              <div className="bi-panel">
                <div className="bi-panel-head">
                  <div className="bi-panel-title">Stock Composition</div>
                  <div className="bi-panel-link">{total} units</div>
                </div>
                <div className="bi-chart-wrap">
                  <div className="bi-donut-row">
                    <DonutChart composition={composition} total={total} />
                    <div className="bi-legend">
                      {composition.map((c) => (
                        <div key={c.status}>
                          <i style={{ background: STATUS_COLOR[c.status] }} />
                          {titleCase(c.status)} <em>{c.units}</em>
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
                {/* Replaces the mockup's inert "Export" with what staff must act on. */}
                {expiredUnits > 0
                  ? <div className="bi-panel-link">{expiredUnits} expired unit{expiredUnits === 1 ? "" : "s"} to write off</div>
                  : <div className="bi-panel-link bi-ok">No expired units</div>}
              </div>
              <table className="bi-table">
                <thead>
                  <tr>
                    <th style={{ width: "9%" }}>Blood Group</th>
                    <th style={{ width: "10%" }}>Usable Units</th>
                    <th style={{ width: "11%" }}>Expiring Soon</th>
                    <th style={{ width: "10%" }}>Expired</th>
                    <th style={{ width: "13%" }}>Batches</th>
                    <th style={{ width: "11%" }}>Status</th>
                    <th style={{ width: "19%" }}>Last Updated</th>
                    <th style={{ width: "17%" }}>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {stock.map((s) => {
                    return (
                      <tr key={s.bloodType}>
                        <td className="bi-grp">{s.bloodType}</td>
                        <td>{s.unitsAvailable}</td>
                        <td className={s.expiringSoonUnits > 0 ? "" : "bi-muted"}>
                          {s.expiringSoonUnits > 0 ? s.expiringSoonUnits : "—"}
                        </td>
                        {/* Expired units are on the shelf but not stock: red until written off. */}
                        <td className={s.expiredUnits > 0 ? "bi-expired" : "bi-muted"}>
                          {s.expiredUnits > 0 ? s.expiredUnits : "—"}
                        </td>
                        <td>
                          {s.batchCount > 0 ? (
                            <button type="button" className="bi-batchbtn" onClick={() => setBatchGroup(s.bloodType)}
                                    aria-label={`View the ${s.batchCount} ${s.bloodType} batches`}>
                              View {s.batchCount} batch{s.batchCount === 1 ? "" : "es"}
                            </button>
                          ) : <span className="bi-muted">No batches</span>}
                        </td>
                        <td><span className={`bi-badge bi-b-${s.status.toLowerCase()}`}>{titleCase(s.status)}</span></td>
                        <td className="bi-muted">{s.lastUpdated ? formatDateTime(s.lastUpdated) : "Never stocked"}</td>
                        <td>
                          {/* Sufficient rows get no button (the Status cell says why); Low and Critical rows always
                              show it, disabled when the server says it can't be sent. */}
                          {needsDonors(s.status) && (
                            <button
                              type="button"
                              className="bi-appealbtn"
                              // Never derived from this row's stock alone: it depends on the last appeal, which only
                              // the server knows, and is refreshed on every reload.
                              disabled={!canSendAppeal(s)}
                              onClick={() => openAppeal(s.bloodType)}
                              aria-label={appealButtonLabel(s)}
                            >
                              {appealButtonText(s)}
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </>
        )}
      </main>

      {batchModalOpen && (
        <AddBatchModal
          bloodBankId={bloodBankId}
          onClose={() => setBatchModalOpen(false)}
          onAdded={handleBatchAdded}
        />
      )}

      {donationModalOpen && (
        <RecordDonationModal
          bloodBankId={bloodBankId}
          onClose={() => setDonationModalOpen(false)}
          onRecorded={handleDonationRecorded}
        />
      )}

      {appeal && (
        <AppealConfirmation
          appeal={appeal}
          onCancel={() => setAppeal(null)}
          onConfirm={confirmAppeal}
        />
      )}

      {openGroup && (
        <BatchDialog
          bloodBankId={bloodBankId}
          group={openGroup}
          canChangeStock={canChangeStock}
          refreshKey={refreshKey}
          onClose={() => setBatchGroup(null)}
          onStockChanged={refreshQuietly}
        />
      )}
    </div>
  );
}

/**
 * The step between "Send Appeal" and sending: shows how many donors it will reach (the same server calculation
 * the send uses), since an appeal can't be recalled. Deliberately lighter than the Reject dialog.
 */
function AppealConfirmation({ appeal, onCancel, onConfirm }) {
  const { bloodType, reach, error, sending } = appeal;
  const cancelRef = useRef(null);

  // Escape closes and focus lands on Cancel, so the dialog is never dismissed into an action by reflex.
  useEffect(() => {
    cancelRef.current?.focus();
    const onKeyDown = (event) => {
      if (event.key === "Escape" && !sending) onCancel();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onCancel, sending]);

  // Nothing to send to. Blocked rather than allowed as a no-op: the request would
  // succeed, report zero, and leave the operator believing an appeal went out.
  const description = reach === null
    ? null
    : describeAppealReach(bloodType, reach.eligibleDonors, reach.donorsInRecovery);
  const canSend = description !== null && !description.nobodyToReach && !sending;

  return (
    <div
      className="bi-modal-backdrop"
      onMouseDown={(e) => { if (e.target === e.currentTarget && !sending) onCancel(); }}
    >
      <div className="bi-modal" role="dialog" aria-modal="true" aria-labelledby="bi-appeal-title">
        <div className="bi-modal-title" id="bi-appeal-title">Send appeal for {bloodType}?</div>
        <div className="bi-modal-sub">
          Donors of this group will be asked to come in and give.
        </div>

        {error && <div className="bi-modal-alert" role="alert">{error}</div>}

        <div className="bi-confirm-fact">
          {reach === null && !error && (
            <div className="bi-confirm-note">Checking how many donors can be contacted…</div>
          )}

          {description !== null && (
            <>
              <div className="bi-confirm-count">{description.heading}</div>
              {/* Says what the eligibility filter did rather than silently shrinking the audience
                  (see describeAppealReach). */}
              <div className="bi-confirm-note">{description.note}</div>
            </>
          )}
        </div>

        <div className="bi-modal-actions">
          <button type="button" className="bi-modal-cancel" ref={cancelRef} onClick={onCancel} disabled={sending}>
            Cancel
          </button>
          <button type="button" className="bi-confirm-send" onClick={onConfirm} disabled={!canSend}>
            {sending ? "Sending…" : "Send appeal"}
          </button>
        </div>
      </div>
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

/**
 * "Add New Batch": the form that makes the mockup's button real, a small modal in the file's own design tokens.
 */
/**
 * "Record Donation": find the donor, then record the tested group and units; a donor in recovery locks the form
 * (the server also refuses). The group never pre-fills (Business Rule 4); exported for the Donors page.
 */
export function RecordDonationModal({ bloodBankId, onClose, onRecorded, initialPhone = "" }) {
  const [phone, setPhone] = useState(initialPhone);
  const [donor, setDonor] = useState(null);
  const [lookupError, setLookupError] = useState(null);
  const [lookingUp, setLookingUp] = useState(false);

  // Empty by design — see the note above. Not BLOOD_GROUPS[0], and not the
  // donor's profile group.
  const [bloodType, setBloodType] = useState("");
  const [unitsDonated, setUnitsDonated] = useState("1");
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);
  // The "change their profile group?" step (Business Rule 28); the confirmation is sent only from it.
  const [confirmingGroupChange, setConfirmingGroupChange] = useState(false);

  async function handleLookup() {
    const trimmed = phone.trim();
    if (!trimmed || lookingUp) return;

    setLookingUp(true);
    setLookupError(null);
    setError(null);
    setConfirmingGroupChange(false);
    try {
      setDonor(await lookupDonorByPhone(trimmed));
      // A new donor starts a new form; the tested group must never carry over (Business Rule 4).
      setBloodType("");
      setUnitsDonated("1");
      setFieldErrors({});
    } catch (err) {
      // Clear any previously found donor: leaving the last one on screen beside a
      // "not found" message is how the wrong person gets a donation recorded.
      setDonor(null);
      setLookupError(err.message);
    } finally {
      setLookingUp(false);
    }
  }

  async function handleSubmit(event) {
    event.preventDefault();
    // `locked` as well as the disabled button: pressing Enter in the units field
    // submits a form without ever touching that button.
    if (!donor || locked || submitting) return;
    // A test that contradicts the profile's group is confirmed first, then sent with the confirmation.
    if (needsBloodGroupChangeConfirmation(donor.profileBloodType, bloodType)) {
      setConfirmingGroupChange(true);
      return;
    }
    await submitDonation(false);
  }

  async function submitDonation(confirmBloodGroupChange) {
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      const receipt = await recordDonation({
        donorId: donor.donorId,
        bloodType,
        unitsDonated: Number(unitsDonated),
        bloodBankId,
        confirmBloodGroupChange,
      });
      await onRecorded(receipt);
    } catch (err) {
      setError(err.message);
      setFieldErrors(err.fieldErrors ?? {});
      setConfirmingGroupChange(false);
      // The profile changed since the lookup: re-read it so the warning shows before the next try.
      if (isUnconfirmedBloodGroupChange(err)) {
        lookupDonorByPhone(phone.trim()).then(setDonor).catch(() => {});
      }
    } finally {
      setSubmitting(false);
    }
  }

  const eligible = donor?.eligibilityStatus === "ELIGIBLE";
  // Found, but inside the recovery window: nothing below may be filled in.
  const locked = Boolean(donor) && !eligible;
  const groupCheck = donor ? describeDonationGroupCheck(donor.profileBloodType, bloodType) : null;

  return (
    <div className="bi-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <form className="bi-modal" onSubmit={handleSubmit} noValidate>
        <div className="bi-modal-title">Record Donation</div>
        <div className="bi-modal-sub">
          Records the donation, updates the donor&rsquo;s eligibility, and adds the units to stock.
        </div>

        {error && <div className="bi-modal-alert" role="alert">{error}</div>}

        <div className="bi-field">
          <label htmlFor="donation-phone">Donor Phone Number</label>
          <div className="bi-lookup">
            <input
              id="donation-phone" type="tel" placeholder="e.g. +232 76 000 001"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              // Enter looks the donor up rather than submitting a form that has
              // no donor yet — the natural key press at this point in the flow.
              onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); handleLookup(); } }}
            />
            <button type="button" className="bi-lookup-btn" disabled={lookingUp || !phone.trim()}
                    onClick={handleLookup}>
              {lookingUp ? "Finding…" : "Find donor"}
            </button>
          </div>
          {lookupError && <span className="bi-field-err">{lookupError}</span>}
        </div>

        {donor && (
          <div className="bi-donor-card">
            <div className="bi-donor-name">{donor.fullName}</div>
            <div className="bi-donor-meta">
              {donor.profileBloodType
                ? `Profile group ${donor.profileBloodType}`
                : "No group on file — never typed"}
              {" · "}
              {donor.lastDonationDate
                ? `Last donated ${formatFullDate(donor.lastDonationDate)}`
                : "No previous donation"}
            </div>
            {eligible ? (
              <div className="bi-donor-ok">Eligible to donate</div>
            ) : (
              <div className="bi-donor-warn" role="alert">
                <strong>Donation locked.</strong> {donor.fullName} last donated on{" "}
                {formatFullDate(donor.lastDonationDate)} and is still inside the 90-day recovery
                window. A donation can be recorded from {formatFullDate(donor.eligibleFrom)}.
              </div>
            )}
          </div>
        )}

        <div className="bi-field">
          <label htmlFor="donation-group">Blood Group (as tested today)</label>
          <select
            id="donation-group" value={bloodType} disabled={!donor || locked}
            onChange={(e) => { setBloodType(e.target.value); setConfirmingGroupChange(false); }}
          >
            <option value="">Select tested group…</option>
            {BLOOD_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}
          </select>
          {groupCheck && (
            <span className={groupCheck.tone === "warn" ? "bi-field-err" : "bi-field-note"}>{groupCheck.text}</span>
          )}
          {fieldErrors.bloodType && <span className="bi-field-err">{fieldErrors.bloodType}</span>}
        </div>

        <div className="bi-field">
          <label htmlFor="donation-units">Units Donated</label>
          <input
            id="donation-units" type="number" min="1" max="10" step="1" disabled={!donor || locked}
            value={unitsDonated} onChange={(e) => setUnitsDonated(e.target.value)}
          />
          {fieldErrors.unitsDonated && <span className="bi-field-err">{fieldErrors.unitsDonated}</span>}
        </div>

        {confirmingGroupChange ? (
          <div className="bi-donor-warn" role="alert">
            <strong>
              {describeDonationGroupChangeQuestion(donor.fullName, donor.profileBloodType, bloodType)}
            </strong>
            <div className="bi-modal-actions">
              <button type="button" className="bi-modal-cancel" onClick={() => setConfirmingGroupChange(false)}>
                Go back
              </button>
              <button type="button" className="bi-modal-submit" disabled={submitting}
                      onClick={() => submitDonation(true)}>
                {submitting ? "Recording…" : "Confirm and record"}
              </button>
            </div>
          </div>
        ) : (
          <div className="bi-modal-actions">
            <button type="button" className="bi-modal-cancel" onClick={onClose}>Cancel</button>
            <button type="submit" className="bi-modal-submit" disabled={submitting || !donor || locked || !bloodType}>
              {submitting ? "Recording…" : "Record Donation"}
            </button>
          </div>
        )}
      </form>
    </div>
  );
}

function AddBatchModal({ bloodBankId, onClose, onAdded }) {
  const [bloodType, setBloodType] = useState(BLOOD_GROUPS[0]);
  const [unitsToAdd, setUnitsToAdd] = useState("");
  const [expiryDate, setExpiryDate] = useState("");
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event) {
    event.preventDefault();
    if (submitting) return;

    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      await addInventoryBatch(bloodBankId, bloodType, {
        unitsToAdd: Number(unitsToAdd),
        expiryDate,
      });
      await onAdded();
    } catch (err) {
      setError(err.message);
      setFieldErrors(err.fieldErrors ?? {});
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="bi-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <form className="bi-modal" onSubmit={handleSubmit} noValidate>
        <div className="bi-modal-title">Add New Batch</div>
        <div className="bi-modal-sub">Record freshly tested or received units for one blood group.</div>

        {error && <div className="bi-modal-alert" role="alert">{error}</div>}

        <div className="bi-field">
          <label htmlFor="batch-group">Blood Group</label>
          <select id="batch-group" value={bloodType} onChange={(e) => setBloodType(e.target.value)}>
            {BLOOD_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}
          </select>
          {fieldErrors.bloodType && <span className="bi-field-err">{fieldErrors.bloodType}</span>}
        </div>

        <div className="bi-field">
          <label htmlFor="batch-units">Units</label>
          <input
            id="batch-units" type="number" min="1" step="1" placeholder="e.g. 10"
            value={unitsToAdd} onChange={(e) => setUnitsToAdd(e.target.value)}
          />
          {fieldErrors.unitsToAdd && <span className="bi-field-err">{fieldErrors.unitsToAdd}</span>}
        </div>

        <div className="bi-field">
          <label htmlFor="batch-expiry">Expiry Date</label>
          <input
            id="batch-expiry" type="date"
            value={expiryDate} onChange={(e) => setExpiryDate(e.target.value)}
          />
          {fieldErrors.expiryDate && <span className="bi-field-err">{fieldErrors.expiryDate}</span>}
        </div>

        <div className="bi-modal-actions">
          <button type="button" className="bi-modal-cancel" onClick={onClose}>Cancel</button>
          <button type="submit" className="bi-modal-submit" disabled={submitting}>
            {submitting ? "Adding…" : "Add Batch"}
          </button>
        </div>
      </form>
    </div>
  );
}

/** Same bar-chart shape as the mockup, redrawn from live per-group units. */
function GroupChart({ stock }) {
  const CW = 430, CH = 170, PAD_L = 32, PAD_B = 24, PAD_T = 14;
  const max = Math.max(120, ...stock.map((d) => d.unitsAvailable));
  const plotH = CH - PAD_B - PAD_T;
  const plotW = CW - PAD_L - 10;
  const slot = plotW / (stock.length || 1);
  const barW = Math.min(30, slot * 0.54);
  const ticks = [0, Math.round(max / 4), Math.round(max / 2), Math.round((3 * max) / 4), max];

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
      {stock.map((d, i) => {
        const h = (d.unitsAvailable / max) * plotH;
        const x = PAD_L + slot * i + (slot - barW) / 2;
        const y = PAD_T + plotH - h;
        const fill = STATUS_COLOR[d.status];
        return (
          <g key={d.bloodType}>
            <rect x={x} y={y} width={barW} height={h} rx="3" fill={fill} />
            <text x={x + barW / 2} y={y - 5} textAnchor="middle" fontSize="9" fontWeight="700" fill="#333" fontFamily="Inter, Arial, sans-serif">{d.unitsAvailable}</text>
            <text x={x + barW / 2} y={CH - 8} textAnchor="middle" fontSize="9.5" fontWeight="600" fill="#666" fontFamily="Inter, Arial, sans-serif">{d.bloodType}</text>
          </g>
        );
      })}
      <line x1={PAD_L} y1={PAD_T + plotH} x2={CW - 10} y2={PAD_T + plotH} stroke="#D8DBDF" strokeWidth="1.2" />
    </svg>
  );
}

/** Same donut shape as the mockup, redrawn from the live composition breakdown. */
function DonutChart({ composition, total }) {
  const R = 50, C = 2 * Math.PI * R;
  let offset = 0;
  return (
    <svg viewBox="0 0 132 132" role="img" aria-label="Donut chart of stock composition by status">
      <g transform="translate(66 66) rotate(-90)">
        <circle r={R} fill="none" stroke="#F0F1F3" strokeWidth="19" />
        {total > 0 && composition.map((c) => {
          const len = (c.units / total) * C;
          const seg = (
            <circle key={c.status} r={R} fill="none" stroke={STATUS_COLOR[c.status]} strokeWidth="19"
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
