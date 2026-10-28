import React, { useEffect, useState } from "react";
import { getMyDonorProfile, getMyDonationHistory } from "../api/donors.js";
import { acceptDonationAppeal } from "../api/appeals.js";
import { getBloodBanks } from "../api/bloodBanks.js";
import { cancelMyBooking, createBooking, getMyBooking } from "../api/bookings.js";
import MobileMenuToggle from "../components/MobileMenuToggle.jsx";
import NotificationBell from "../components/NotificationBell.jsx";
import ProfileSettings from "../components/ProfileSettings.jsx";
import { useNotifications } from "../realtime/useNotifications.js";
import { describeDonorPortalBloodGroup } from "../lib/bloodGroup.js";
import {
  bookingDateRange, bookingFormProblems, canCancelBooking, describeBookingQuestion, describeBookingWhen,
  describeCancelQuestion,
} from "../lib/donationBooking.js";

/* BloodLink — Donor Portal: history, eligibility, booking a donation visit (Business Rule 29) and live appeals from
   blood banks (Business Rule 1), from /api/donors/me, /me/donations, /api/bookings/me and the donor's notifications. */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };
const ICON_W = { fill: "none", stroke: "#FFF", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

const MONTH_NAMES = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];

/** Parses "YYYY-MM-DD" as a local date, since new Date("YYYY-MM-DD") would shift a day west of UTC. */
function parseIsoDate(isoDate) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new Date(year, month - 1, day);
}

/** "12 May 2026" — used for the eligibility banner and Certificate panel. */
function formatFullDate(isoDate) {
  const d = parseIsoDate(isoDate);
  return `${d.getDate()} ${MONTH_NAMES[d.getMonth()]} ${d.getFullYear()}`;
}

/** "12 May" — compact form for the Last Donation stat card. */
function formatShortDate(isoDate) {
  const d = parseIsoDate(isoDate);
  return `${d.getDate()} ${MONTH_NAMES[d.getMonth()]}`;
}

/** "February 2025" — for "Since {month year}" framing. */
function formatMonthYear(isoDate) {
  const d = parseIsoDate(isoDate);
  return `${MONTH_NAMES[d.getMonth()]} ${d.getFullYear()}`;
}

/** "September 5 2026" — matches the mockup's header date, computed for real. */
function formatHeaderDate(date) {
  return `${MONTH_NAMES[date.getMonth()]} ${date.getDate()} ${date.getFullYear()}`;
}

/**
 * "2 hrs ago" for a recent appeal, a plain date once older; takes an Instant, unlike the date-only helpers above.
 */
function formatAppealTime(instantIso) {
  const minutes = Math.floor((Date.now() - new Date(instantIso).getTime()) / 60000);
  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes} min${minutes === 1 ? "" : "s"} ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hr${hours === 1 ? "" : "s"} ago`;
  const days = Math.floor(hours / 24);
  if (days === 1) return "Yesterday";
  if (days < 7) return `${days} days ago`;
  return formatFullDate(instantIso.slice(0, 10));
}

/** "RB" from "Rahim Barrie" — first letter of up to the first two words. */
function initials(fullName) {
  return fullName
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}

/**
 * The one blood-group fact worth a badge: O- is the universal donor, AB+ the universal recipient.
 */
function bloodGroupTag(bloodType) {
  if (bloodType === "O-") return "Universal Donor";
  if (bloodType === "AB+") return "Universal Recipient";
  return null;
}

export default function DonorPortal({ user, onSignOut, onUserUpdated }) {
  // "dashboard" or "settings" (shared Profile Settings in the main area, Business Rule 16), as plain state.
  const [view, setView] = useState("dashboard");
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [profile, setProfile] = useState(null);
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  // Only the header date needs "now"; computed once per mount rather than ticking.
  const [today] = useState(() => new Date());

  // The bell, and the appeals panel below it, are both fed from here: one live
  // socket per screen rather than one per feature.
  const { notifications, unreadCount, connected, markAllRead, updateNotification } = useNotifications();
  const [acceptingAppealId, setAcceptingAppealId] = useState(null);
  const [appealFeedback, setAppealFeedback] = useState({});

  // A donor's appeals are their DONATION_APPEAL notifications (there is no appeals table),
  // capped at what the panel shows.
  const appeals = notifications.filter((item) => item.type === "DONATION_APPEAL").slice(0, 4);

  // The donor's open donation visit (Business Rule 29), or null; the booking form and the cancel step.
  const [booking, setBooking] = useState(null);
  const [bookingFormOpen, setBookingFormOpen] = useState(false);
  const [cancelStep, setCancelStep] = useState({ confirming: false, busy: false, error: null });

  useEffect(() => {
    let cancelled = false;

    async function load() {
      setLoading(true);
      setError(null);
      try {
        const [profileData, historyData, bookingData] = await Promise.all([
          getMyDonorProfile(),
          getMyDonationHistory(),
          getMyBooking(),
        ]);
        if (!cancelled) {
          setProfile(profileData);
          setHistory(historyData);
          setBooking(bookingData);
        }
      } catch (err) {
        if (!cancelled) setError(err.message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    load();
    return () => { cancelled = true; };
  }, []);

  // The name from the session, shown at once and updated by Profile Settings; the profile's is the fallback.
  const displayName = user?.fullName ?? profile?.fullName ?? "";
  const firstName = displayName.split(" ")[0] || displayName;

  const isEligible = profile?.eligibilityStatus === "ELIGIBLE";

  // "Respond" accepts the appeal (Business Rule 25); a 409 means the server found the donor ineligible,
  // so the profile is re-read and the button disappears.
  async function respondToAppeal(appeal) {
    if (!isEligible || appeal.accepted || acceptingAppealId) return;
    setAcceptingAppealId(appeal.id);
    setAppealFeedback((current) => ({ ...current, [appeal.id]: null }));
    try {
      const updated = await acceptDonationAppeal(appeal.id);
      updateNotification(updated);
      setAppealFeedback((current) => ({
        ...current,
        [appeal.id]: updated.accepted ? "Your response was sent successfully." : "Could not accept this appeal.",
      }));
    } catch (err) {
      if (err.status === 409) {
        try {
          setProfile(await getMyDonorProfile());
        } catch {
          // Keep the existing profile if refreshing also fails.
        }
      }
      setAppealFeedback((current) => ({ ...current, [appeal.id]: err.message || "Could not accept this appeal. Try again." }));
    } finally {
      setAcceptingAppealId(null);
    }
  }
  /** After a refused booking (409: no longer eligible, or already booked), re-read profile and booking. */
  async function refreshProfileAndBooking() {
    try {
      const [profileData, bookingData] = await Promise.all([getMyDonorProfile(), getMyBooking()]);
      setProfile(profileData);
      setBooking(bookingData);
    } catch {
      // Keep what is shown; the next load will try again.
    }
  }

  async function confirmCancelBooking() {
    setCancelStep({ confirming: true, busy: true, error: null });
    try {
      await cancelMyBooking();
      setBooking(null);
      setCancelStep({ confirming: false, busy: false, error: null });
    } catch (err) {
      setCancelStep({ confirming: false, busy: false, error: err.message });
      refreshProfileAndBooking();
    }
  }

  const lastDonation = history[0] ?? null;
  const bloodGroupCard = describeDonorPortalBloodGroup(profile?.bloodType);
  // The panel's CSS clips overflow rather than scrolling it, so it is capped here at
  // the same five rows the mockup's fixture happened to have.
  const visibleHistory = history.slice(0, 5);

  return (
    <div className={`dn-root${mobileMenuOpen ? " is-menu-open" : ""}`}>
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
        /* The bell moved to <NotificationBell>, which brings its own styles and
           inherits the tokens declared above. The mockup's .dn-bell rules were a
           fixed red dot that was always on; a count that is sometimes zero cannot
           be drawn with a ::after pseudo-element, and leaving them here would mean
           two sets of bell styles with only one bell. */
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
        .dn-group-notice {
          flex: 0 0 auto; font-size: clamp(10px, 1.45vh, 13px); font-weight: 600; line-height: 1.45;
          color: #8A5510; background: #FBF1E3; border-left: 3px solid var(--amber); border-radius: 6px;
          padding: clamp(7px, 1.1vh, 10px) clamp(10px, 1vw, 14px);
        }
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
        /* Additive variant, same tokens as the rest of the design system (CLAUDE.md:
           amber = low/pending) — used only while a real donor is inside the 90-day
           window, which the static mockup never had to represent. */
        .dn-elig--ineligible { background: var(--amber); }
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

        /* ===== DONATION VISIT (Business Rule 29) ===== */
        .dn-booking {
          flex: 0 0 auto; background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          border-left: 4px solid var(--navy);
          padding: clamp(8px, 1.3vh, 13px) clamp(12px, 1.2vw, 20px);
          display: flex; align-items: center; flex-wrap: wrap; gap: clamp(10px, 1vw, 15px);
        }
        .dn-booking-icon {
          flex: 0 0 auto; width: clamp(28px, 4.2vh, 38px); height: clamp(28px, 4.2vh, 38px);
          border-radius: 50%; background: var(--navy); color: #FFF; display: grid; place-items: center;
        }
        .dn-booking-icon svg { width: 55%; height: 55%; }
        .dn-booking-body { flex: 1 1 200px; min-width: 0; }
        .dn-booking-main { font-size: clamp(11.5px, 1.7vh, 15px); font-weight: 700; }
        .dn-booking-sub { font-size: clamp(9.5px, 1.35vh, 12px); color: var(--ink-muted); margin-top: 1px; }
        .dn-booking-err { font-size: clamp(9px, 1.25vh, 11px); color: var(--red); font-weight: 600; margin-top: 2px; }
        .dn-booking-confirm {
          display: flex; align-items: center; flex-wrap: wrap; gap: 8px;
          font-size: clamp(10px, 1.45vh, 12.5px); font-weight: 600; color: #8A5510;
        }
        .dn-booking-cancel, .dn-booking-keep {
          flex: 0 0 auto; border-radius: 6px; cursor: pointer; font-family: inherit; font-weight: 700;
          font-size: clamp(9.5px, 1.35vh, 12px); padding: clamp(5px, .85vh, 8px) clamp(10px, .9vw, 14px);
        }
        .dn-booking-cancel { background: transparent; color: var(--red); border: 1.5px solid rgba(200,16,46,.4); }
        .dn-booking-cancel:hover { background: #FBE9EC; }
        .dn-booking-keep { background: var(--navy); color: #FFF; border: 1.5px solid var(--navy); }
        .dn-booking-cancel:disabled, .dn-booking-keep:disabled { opacity: .6; cursor: progress; }

        .dn-modal-backdrop {
          position: fixed; inset: 0; z-index: 50; background: rgba(17,17,17,.45);
          display: grid; place-items: center; padding: 16px;
        }
        .dn-modal {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: 0 12px 40px rgba(0,0,0,.22);
          width: min(440px, 100%); max-height: calc(100dvh - 32px); overflow-y: auto;
          padding: clamp(16px, 2.4vh, 24px) clamp(16px, 1.6vw, 26px);
        }
        .dn-modal-title { font-size: clamp(15px, 2.2vh, 19px); font-weight: 800; letter-spacing: -.02em; margin-bottom: 4px; }
        .dn-modal-sub { font-size: clamp(10.5px, 1.5vh, 13px); color: var(--ink-muted); margin-bottom: clamp(12px, 2vh, 18px); }
        .dn-modal-alert {
          background: rgba(200,16,46,.07); border: 1.5px solid rgba(200,16,46,.28); color: #8E0B21;
          border-radius: 8px; padding: clamp(7px, 1.2vh, 10px) 12px;
          font-size: clamp(10px, 1.42vh, 12.5px); font-weight: 500; margin-bottom: clamp(9px, 1.5vh, 14px);
        }
        .dn-field { display: flex; flex-direction: column; gap: 5px; margin: 0 0 clamp(10px, 1.6vh, 15px); border: none; padding: 0; }
        .dn-field label, .dn-field legend { font-size: clamp(10px, 1.4vh, 12.5px); font-weight: 600; color: #3D3D3D; padding: 0; }
        .dn-field select, .dn-field input[type="date"] {
          width: 100%; font-family: inherit; font-size: clamp(11.5px, 1.6vh, 14px); font-weight: 500;
          color: var(--ink); padding: clamp(8px, 1.35vh, 11px) 12px;
          border: 1.5px solid var(--line); border-radius: 8px; background: #FBFBFC;
        }
        .dn-field select:focus, .dn-field input:focus { outline: none; border-color: var(--navy); background: #FFF; }
        .dn-field--choice { flex-direction: row; flex-wrap: wrap; align-items: center; gap: 8px 18px; }
        .dn-field--choice legend { width: 100%; margin-bottom: 5px; }
        .dn-choice { display: flex; align-items: center; gap: 6px; font-size: clamp(11px, 1.55vh, 13.5px); font-weight: 600; cursor: pointer; }
        .dn-field-err { width: 100%; font-size: clamp(9px, 1.25vh, 11px); font-weight: 500; color: var(--red); }
        .dn-modal-confirm {
          font-size: clamp(10.5px, 1.5vh, 13px); line-height: 1.45; color: #8A5510; background: #FBF1E3;
          border-left: 3px solid var(--amber); border-radius: 6px; padding: clamp(8px, 1.2vh, 12px) clamp(10px, 1vw, 14px);
        }
        .dn-modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: clamp(8px, 1.2vh, 12px); }
        .dn-modal-primary, .dn-modal-secondary {
          font-family: inherit; font-weight: 700; cursor: pointer; border-radius: 7px;
          font-size: clamp(11px, 1.55vh, 13.5px); padding: clamp(8px, 1.15vh, 11px) clamp(13px, 1.3vw, 20px);
        }
        .dn-modal-primary { background: var(--red); color: #FFF; border: 1.5px solid var(--red); }
        .dn-modal-primary:hover { background: var(--red-dark); }
        .dn-modal-primary:disabled { opacity: .65; cursor: progress; }
        .dn-modal-secondary { background: #FFF; color: #3A3A3A; border: 1.5px solid var(--line); }

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

        /* Wiring states — additive, same tokens, for real async data. */
        .dn-loading, .dn-error { flex: 1 1 auto; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; text-align: center; }
        .dn-error-msg { color: var(--red); font-weight: 600; font-size: clamp(11px, 1.6vh, 14px); }
        .dn-retry {
          background: var(--navy); color: #FFF; border: none; border-radius: 7px; cursor: pointer;
          font-family: inherit; font-weight: 700; font-size: clamp(10.5px, 1.5vh, 13px);
          padding: clamp(7px, 1.15vh, 11px) clamp(14px, 1.4vw, 22px);
        }
        .dn-empty { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); padding: clamp(8px, 1.4vh, 14px) 2px; }

        @media (prefers-reduced-motion: reduce) { .dn-root * { transition: none !important; } }
      `}</style>

      {/* ================= SIDEBAR ================= */}
      {mobileMenuOpen && <button type="button" className="mobile-menu-backdrop" aria-label="Close navigation menu" onClick={() => setMobileMenuOpen(false)} />}
      <aside className="dn-side" id="dn-sidebar">
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

        {/* Dashboard and Settings are wired; the middle four are still the
            mockup's inert entries. */}
        <nav className="dn-nav" onClick={() => setMobileMenuOpen(false)}>
          <button
            type="button" className={view === "dashboard" ? "is-active" : ""}
            aria-current={view === "dashboard" ? "page" : undefined}
            onClick={() => setView("dashboard")}
          >
            <GridIcon /> Dashboard
          </button>
          <button type="button"><ClockIcon /> Donation History</button>
          <button type="button"><DropletOutlineIcon /> Donation Appeals</button>
          <button type="button"><HeartIcon /> My Impact</button>
          <button type="button"><AwardIcon /> Certificate</button>
          <button
            type="button" className={view === "settings" ? "is-active" : ""}
            aria-current={view === "settings" ? "page" : undefined}
            onClick={() => setView("settings")}
          >
            <GearIcon /> Settings
          </button>
        </nav>

        <button type="button" className="dn-logout" onClick={onSignOut}><LogoutIcon /> Logout</button>
      </aside>

      {/* ================= MAIN ================= */}
      <main className="dn-main">
        <div className="dn-top">
          <MobileMenuToggle open={mobileMenuOpen} onClick={() => setMobileMenuOpen((open) => !open)} controls="dn-sidebar" />
          <div>
            <div className="dn-title">
              {view === "settings" ? "Profile Settings" : `Hello${firstName ? `, ${firstName}` : ""}`}
            </div>
            <div className="dn-sub">
              {view === "settings" ? "Your name, email and password" : "Voluntary donor · Makeni City"}
            </div>
          </div>
          <div className="dn-top-right">
            <div className="dn-date"><CalendarIcon /> Today, {formatHeaderDate(today)}</div>
            <NotificationBell
              notifications={notifications}
              unreadCount={unreadCount}
              connected={connected}
              onMarkAllRead={markAllRead}
            />
            <div className="dn-avatar">{displayName ? initials(displayName) : ""}</div>
          </div>
        </div>

        {view === "settings" && <ProfileSettings onUserUpdated={onUserUpdated} />}

        {view === "dashboard" && loading && (
          <div className="dn-loading">
            <div className="dn-sub">Loading your dashboard…</div>
          </div>
        )}

        {view === "dashboard" && !loading && error && (
          <div className="dn-error">
            <div className="dn-error-msg">{error}</div>
            <button type="button" className="dn-retry" onClick={() => window.location.reload()}>
              Try again
            </button>
          </div>
        )}

        {view === "dashboard" && !loading && !error && profile && (
          <>
            {/* ---- stat cards ---- */}
            <div className="dn-stats">
              <div className="dn-stat">
                <div>
                  <div className="dn-stat-figure">{bloodGroupCard.figure}</div>
                  <div className="dn-stat-label">Blood Group</div>
                  {bloodGroupTag(profile.bloodType) && (
                    <span className="dn-tag">{bloodGroupTag(profile.bloodType)}</span>
                  )}
                </div>
                <div className="dn-stat-icon dn-i-red"><DropletIcon /></div>
              </div>

              <Stat tone="navy" icon={<GiftIcon />} figure={profile.totalDonations}
                    label="Total Donations"
                    note={profile.firstDonationDate ? `Since ${formatMonthYear(profile.firstDonationDate)}` : "No donations yet"} />
              <Stat tone="green" icon={<HeartFillIcon />} figure={profile.livesImpacted}
                    label="Lives Impacted" note="Up to 3 per donation" />
              <Stat tone="amber" icon={<CalendarFillIcon />}
                    figure={profile.lastDonationDate ? formatShortDate(profile.lastDonationDate) : "—"}
                    label="Last Donation"
                    note={lastDonation ? lastDonation.bloodBankName : "Not yet donated"} />
            </div>

            {/* No group on file yet: how it gets added (Business Rule 28). */}
            {bloodGroupCard.notice && <div className="dn-group-notice" role="note">{bloodGroupCard.notice}</div>}

            {/* ---- eligibility ---- */}
            <div className={`dn-elig${isEligible ? "" : " dn-elig--ineligible"}`}>
              {/* ClockIcon's stroke is currentColor, which inherits the white text
                  color set on .dn-elig / .dn-elig--ineligible — no override needed. */}
              {isEligible ? <CheckCircleIcon /> : <ClockIcon />}
              <div>
                <div className="dn-elig-main">
                  {isEligible ? "You are eligible to donate" : "You are not yet eligible to donate"}
                </div>
                <div className="dn-elig-sub">
                  {isEligible
                    ? `Eligible since ${formatFullDate(profile.eligibleSince)}${profile.lastDonationDate ? " · 90 days have passed since your last donation" : ""}`
                    : `Eligible again from ${formatFullDate(profile.eligibleSince)} · 90 days must pass since your last donation`}
                </div>
              </div>
              {/* Eligible donors only, and only one open visit at a time (Business Rule 29); the server checks both. */}
              {isEligible && !booking && (
                <button type="button" className="dn-elig-btn" onClick={() => setBookingFormOpen(true)}>
                  Book a Donation
                </button>
              )}
            </div>

            {booking && (
              <div className="dn-booking">
                <div className="dn-booking-icon"><CalendarIcon /></div>
                <div className="dn-booking-body">
                  <div className="dn-booking-main">Your donation visit · {booking.bloodBankName}</div>
                  <div className="dn-booking-sub">{describeBookingWhen(booking)}</div>
                  {cancelStep.error && <div className="dn-booking-err" role="alert">{cancelStep.error}</div>}
                </div>
                {cancelStep.confirming ? (
                  <div className="dn-booking-confirm" role="alert">
                    <span>{describeCancelQuestion(booking)}</span>
                    <button type="button" className="dn-booking-keep" disabled={cancelStep.busy}
                            onClick={() => setCancelStep({ confirming: false, busy: false, error: null })}>
                      Keep it
                    </button>
                    <button type="button" className="dn-booking-cancel" disabled={cancelStep.busy}
                            onClick={confirmCancelBooking}>
                      {cancelStep.busy ? "Cancelling…" : "Yes, cancel"}
                    </button>
                  </div>
                ) : canCancelBooking(booking) && (
                  <button type="button" className="dn-booking-cancel"
                          onClick={() => setCancelStep({ confirming: true, busy: false, error: null })}>
                    Cancel
                  </button>
                )}
              </div>
            )}

            {/* ---- panels ---- */}
            <div className="dn-panels">
              <div className="dn-col">
                <div className="dn-panel" style={{ flex: "1 1 auto" }}>
                  <div className="dn-panel-head">
                    <div className="dn-panel-title">Donation History</div>
                    <div className="dn-panel-link">View All</div>
                  </div>
                  <div className="dn-list">
                    {visibleHistory.length === 0 && (
                      <div className="dn-empty">No donations on record yet.</div>
                    )}
                    {visibleHistory.map((h, i) => (
                      <div className="dn-item" key={i}>
                        {/* The group tested at this donation (Business Rule 4), not the profile value. */}
                        <div className="dn-item-badge dn-item-badge--navy">{h.bloodType}</div>
                        <div className="dn-item-body">
                          <div className="dn-item-main">{formatFullDate(h.donationDate)}</div>
                          <div className="dn-item-sub">
                            {/* "Whole Blood" is the only donation type the schema
                                records — there is no per-donation type field. */}
                            Whole Blood · {h.unitsDonated} unit{h.unitsDonated === 1 ? "" : "s"} · {h.bloodBankName}
                          </div>
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
                  {/* Real appeals from blood banks (Business Rule 1); the badge shows the donor's own group,
                      not one parsed from the message. */}
                  <div className="dn-list">
                    {appeals.length === 0 && (
                      <div className="dn-empty">
                        No active appeals right now. Blood banks will appeal here when
                        your group is needed.
                      </div>
                    )}
                    {appeals.map((appeal) => (
                      <div className="dn-item" key={appeal.id}>
                        <div className="dn-item-badge">{profile.bloodType ?? "?"}</div>
                        <div className="dn-item-body">
                          <div className="dn-item-main">{appeal.message}</div>
                          <div className="dn-item-sub">{formatAppealTime(appeal.createdAt)}</div>
                        </div>
                        {/* Appeals no longer reach donors in recovery (Business Rule 2); this hides Respond
                            if the donor has donated since an appeal arrived. Accepted appeals show a pill (Rule 25). */}
                        {appeal.accepted ? (
                          <span className="dn-pill dn-p-planned" aria-label="Appeal accepted">Accepted</span>
                        ) : isEligible && (
                          <button type="button" className="dn-help" disabled={acceptingAppealId !== null}
                            onClick={() => respondToAppeal(appeal)}>
                            {acceptingAppealId === appeal.id ? "Sending…" : "Respond"}
                          </button>
                        )}
                        {appealFeedback[appeal.id] && (
                          <div role="status" className="dn-item-sub" style={{ color: appeal.accepted ? "var(--green)" : "var(--red)" }}>
                            {appealFeedback[appeal.id]}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>

                <div className="dn-cert">
                  <div className="dn-cert-icon"><AwardFillIcon /></div>
                  <div>
                    <div className="dn-cert-main">Donor Recognition Certificate</div>
                    <div className="dn-cert-sub">{profile.totalDonations} donations · issued by BloodLink Makeni</div>
                  </div>
                  <button type="button" className="dn-cert-btn">Download</button>
                </div>
              </div>
            </div>
          </>
        )}
      </main>

      {bookingFormOpen && (
        <BookingDialog
          onClose={() => setBookingFormOpen(false)}
          onBooked={(created) => { setBooking(created); setBookingFormOpen(false); }}
          onRefused={refreshProfileAndBooking}
        />
      )}
    </div>
  );
}

/**
 * "Book a Donation" (Business Rule 29): blood bank, date (today to today + 30, UTC), morning or afternoon, then a
 * confirmation step. Checks in lib/donationBooking.js; a 409 re-reads the profile and booking behind it.
 */
function BookingDialog({ onClose, onBooked, onRefused }) {
  const [banks, setBanks] = useState(null);
  const [banksError, setBanksError] = useState(null);
  const [form, setForm] = useState({ bloodBankId: "", bookingDate: "", timeOfDay: "" });
  const [fieldErrors, setFieldErrors] = useState({});
  const [error, setError] = useState(null);
  const [confirming, setConfirming] = useState(false);
  const [saving, setSaving] = useState(false);
  const [range] = useState(() => bookingDateRange());

  useEffect(() => {
    getBloodBanks().then(setBanks).catch((err) => setBanksError(err.message));
  }, []);

  const bankName = banks?.find((bank) => bank.id === form.bloodBankId)?.name ?? "";

  function update(changes) {
    setForm((current) => ({ ...current, ...changes }));
  }

  function handleContinue(event) {
    event.preventDefault();
    const problems = bookingFormProblems(form);
    setFieldErrors(problems);
    setError(null);
    if (Object.keys(problems).length === 0) setConfirming(true);
  }

  async function handleConfirm() {
    setSaving(true);
    setError(null);
    try {
      onBooked(await createBooking(form));
    } catch (err) {
      setError(err.message);
      setFieldErrors(err.fieldErrors ?? {});
      setConfirming(false);
      setSaving(false);
      if (err.status === 409) onRefused();
    }
  }

  return (
    <div className="dn-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <form className="dn-modal" onSubmit={handleContinue} noValidate>
        <div className="dn-modal-title">Book a Donation</div>
        <div className="dn-modal-sub">Choose a blood bank, a day within the next 30 days, and morning or afternoon.</div>

        {error && <div className="dn-modal-alert" role="alert">{error}</div>}
        {banksError && <div className="dn-modal-alert" role="alert">{banksError}</div>}

        <div className="dn-field">
          <label htmlFor="booking-bank">Blood bank</label>
          <select id="booking-bank" value={form.bloodBankId} disabled={confirming || !banks}
                  onChange={(e) => update({ bloodBankId: e.target.value })}>
            <option value="">{banks ? "Select a blood bank…" : "Loading blood banks…"}</option>
            {(banks ?? []).map((bank) => <option key={bank.id} value={bank.id}>{bank.name}</option>)}
          </select>
          {fieldErrors.bloodBankId && <span className="dn-field-err">{fieldErrors.bloodBankId}</span>}
        </div>

        <div className="dn-field">
          <label htmlFor="booking-date">Date</label>
          <input id="booking-date" type="date" min={range.min} max={range.max} value={form.bookingDate}
                 disabled={confirming} onChange={(e) => update({ bookingDate: e.target.value })} />
          {fieldErrors.bookingDate && <span className="dn-field-err">{fieldErrors.bookingDate}</span>}
        </div>

        <fieldset className="dn-field dn-field--choice" disabled={confirming}>
          <legend>Time of day</legend>
          {["MORNING", "AFTERNOON"].map((value) => (
            <label key={value} className="dn-choice">
              <input type="radio" name="booking-time" value={value} checked={form.timeOfDay === value}
                     onChange={() => update({ timeOfDay: value })} />
              {value === "MORNING" ? "Morning" : "Afternoon"}
            </label>
          ))}
          {fieldErrors.timeOfDay && <span className="dn-field-err">{fieldErrors.timeOfDay}</span>}
        </fieldset>

        {confirming ? (
          <div className="dn-modal-confirm" role="alert">
            <strong>{describeBookingQuestion(bankName, form.bookingDate, form.timeOfDay)}</strong>
            <div className="dn-modal-actions">
              <button type="button" className="dn-modal-secondary" onClick={() => setConfirming(false)}>Go back</button>
              <button type="button" className="dn-modal-primary" disabled={saving} onClick={handleConfirm}>
                {saving ? "Booking…" : "Confirm booking"}
              </button>
            </div>
          </div>
        ) : (
          <div className="dn-modal-actions">
            <button type="button" className="dn-modal-secondary" onClick={onClose}>Cancel</button>
            <button type="submit" className="dn-modal-primary">Continue</button>
          </div>
        )}
      </form>
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
function DropletIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z"/></svg>); }
function GiftIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><rect x="3" y="9" width="18" height="12" rx="2"/><path d="M3 13h18M12 9v12M12 9S9.5 3 7 5.5 12 9 12 9zM12 9s2.5-6 5-3.5S12 9 12 9z"/></svg>); }
function HeartFillIcon() { return (<svg viewBox="0 0 24 24" fill="#FFF"><path d="M20.8 6.6a5 5 0 0 0-7.1 0L12 8.3l-1.7-1.7a5 5 0 0 0-7.1 7.1l8.8 8.8 8.8-8.8a5 5 0 0 0 0-7.1z"/></svg>); }
function CalendarFillIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W}><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>); }
function CheckCircleIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W} strokeWidth="2.2"><circle cx="12" cy="12" r="9.5"/><path d="M7.5 12.5 11 16l6-7"/></svg>); }
function AwardFillIcon() { return (<svg viewBox="0 0 24 24" {...ICON_W} strokeWidth="1.9"><circle cx="12" cy="9" r="6"/><path d="M8.2 14.3 7 22l5-2.6L17 22l-1.2-7.7"/></svg>); }
