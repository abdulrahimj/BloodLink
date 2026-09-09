import React, { useCallback, useEffect, useMemo, useState } from "react";
import { getBloodBanks } from "../api/bloodBanks.js";
import { getInventory } from "../api/inventory.js";
import { getRequests, submitBloodRequest } from "../api/requests.js";
import { formatHeaderDate, formatRelativeTime, initials } from "../lib/dates.js";
import NotificationBell from "../components/NotificationBell.jsx";
import { useNotifications } from "../realtime/useNotifications.js";

/* ================================================================
   BloodLink — Hospital Portal

   Covers the hospital-staff use cases from Chapters One & Three:
     • Submit Blood Request  (blood group, units, urgency, ward)
     • Track Request Status  (Pending / Approved / Fulfilled / Rejected)
     • Check live blood availability across Makeni blood banks

   The hospital half of Figure 3.5. Wired to:
     POST /api/requests            — the form
     GET  /api/requests            — the tracking list, scoped server-side to
                                     this hospital's own requests
     GET  /api/blood-banks + /api/inventory/{id}  — live availability

   Layout, styling and copy are the mockup's, unchanged. The AVAILABILITY and
   REQUESTS fixtures are replaced with fetched data, the stat cards are derived
   from the real request list, and a loading/error/submitting set of states was
   added using the same idiom (and the same tokens) as the other wired portals.

   The screen is live. A WebSocket session (see realtime/useNotifications) feeds
   the bell in the header, and the one type a hospital receives —
   REQUEST_STATUS_CHANGE — is precisely the news this screen exists to show: a
   request the clerk raised has been approved, rejected or fulfilled by a blood
   bank. That is a decision taken on another screen in another building, so
   without the push it would sit unnoticed until someone thought to refresh.
   Arrival triggers a reload rather than patching the row from the message text:
   the notification is what says something changed, never what the new state is.
   ================================================================ */

const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-"];

/** The form's three buttons, paired with the UrgencyLevel enum they post. */
const URGENCIES = [
  { label: "Routine", value: "ROUTINE" },
  { label: "Urgent", value: "URGENT" },
  { label: "Emergency", value: "EMERGENCY" },
];

/** PENDING -> Pending, EMERGENCY -> Emergency: the enum name as the mockup's
 *  badge text and CSS class suffixes already spell it. */
function titleCase(value) {
  return value.charAt(0) + value.slice(1).toLowerCase();
}

/** SUFFICIENT/LOW/CRITICAL -> the tile's dot modifier (ok/low/critical). */
const DOT_CLASS = { SUFFICIENT: "ok", LOW: "low", CRITICAL: "critical" };

/** Ranked best-first, for the aggregation in citywideAvailability below. */
const STATUS_RANK = { SUFFICIENT: 0, LOW: 1, CRITICAL: 2 };

/**
 * Folds every bank's per-group stock into the one citywide row this panel shows.
 *
 * Units are simply summed — that is a real total of the blood available in Makeni.
 * The status dot is the *best* status any single bank reports for that group,
 * rather than a status recomputed from the sum, because Business Rule 3's
 * thresholds are defined per blood bank and applying them to a citywide total
 * would be reading them as something they are not. Best-of is also the question
 * the hospital is actually asking: a request is filled by one bank, so what
 * matters is whether some bank's stock of that group is healthy. Two banks
 * sitting at "Low" therefore still read Low here, even though their combined
 * units would clear the Sufficient threshold on their own.
 */
function citywideAvailability(inventoriesByBank) {
  return BLOOD_GROUPS.map((group) => {
    let units = 0;
    let best = "CRITICAL";

    inventoriesByBank.forEach((rows) => {
      const row = rows.find((r) => r.bloodType === group);
      if (!row) return;
      units += row.unitsAvailable;
      if (STATUS_RANK[row.status] < STATUS_RANK[best]) best = row.status;
    });

    return { group, units, state: DOT_CLASS[best] };
  });
}

export default function HospitalDashboard({ hospital, user, onSignOut }) {
  const [group, setGroup] = useState("O-");
  const [units, setUnits] = useState(2);
  const [urgency, setUrgency] = useState("EMERGENCY");
  const [ward, setWard] = useState("");

  const [requests, setRequests] = useState([]);
  const [availability, setAvailability] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState(null);
  const [formNotice, setFormNotice] = useState(null);
  const [today] = useState(() => new Date());

  /**
   * Availability needs one call per bank on top of the bank listing, so it is
   * loaded once here rather than refreshed after every submission — submitting a
   * request does not change stock (only a bank fulfilling it does), so there
   * would be nothing new to show.
   *
   * A bank fulfilling one is exactly the case that clause names, and it now has a
   * trigger: see refresh below, which a REQUEST_STATUS_CHANGE calls.
   */
  const load = useCallback(async () => {
    const [myRequests, banks] = await Promise.all([getRequests(), getBloodBanks()]);
    // Sequential rather than folded into the Promise.all above: which banks exist
    // is only known once that first call returns.
    const inventories = await Promise.all(banks.map((bank) => getInventory(bank.id)));
    return { myRequests, inventories };
  }, []);

  /**
   * Reloads and applies, without touching the page-level loading or error state.
   *
   * This reload is something the screen did on its own rather than something the
   * clerk asked for, so it must not blank a populated desk to "Loading…", and must
   * not replace it with an error banner if one background refresh fails. Either
   * would be far more disruptive than briefly showing a status a few seconds out
   * of date. Same reasoning, and same shape, as the Blood Bank Dashboard's.
   *
   * A half-typed request in the form is untouched — none of the form's state is
   * written here, so a notification arriving mid-typing cannot clear the ward
   * field or reset the blood group under the clerk's hands.
   */
  const refresh = useCallback(async () => {
    try {
      const { myRequests, inventories } = await load();
      setRequests(myRequests);
      setAvailability(citywideAvailability(inventories));
    } catch {
      // Left as it was; the next notification or page load will try again.
    }
  }, [load]);

  /**
   * The header bell, plus the live trigger for the refresh above.
   *
   * Every type this account can receive is acted on, which is why there is no
   * type check here as there is on the Blood Bank Dashboard: a hospital is only
   * ever sent REQUEST_STATUS_CHANGE, and that always makes both the tracking list
   * and — when the change was a fulfilment — the citywide availability panel
   * stale at once. Reloading both is the honest response to either.
   */
  const { notifications, unreadCount, connected, markAllRead } = useNotifications({
    onNotification: () => refresh(),
  });

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    load()
      .then(({ myRequests, inventories }) => {
        if (cancelled) return;
        setRequests(myRequests);
        setAvailability(citywideAvailability(inventories));
      })
      .catch((err) => { if (!cancelled) setError(err.message); })
      // Cleared last, so the panels never render for a frame with the data still
      // empty — otherwise a full request list flashes "No requests yet" first.
      .finally(() => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
  }, [load]);

  /**
   * The four stat cards, derived from the request list already in hand rather
   * than fetched separately — the list is this hospital's whole history and is
   * small enough to count over, so a dedicated summary endpoint would be a round
   * trip that told us nothing new.
   */
  const stats = useMemo(() => {
    const now = new Date();
    let thisMonth = 0;
    let pending = 0;
    let unitsReceived = 0;
    let openEmergencies = 0;

    requests.forEach((r) => {
      const created = new Date(r.createdAt);
      if (created.getFullYear() === now.getFullYear() && created.getMonth() === now.getMonth()) {
        thisMonth += 1;
      }
      if (r.status === "PENDING") pending += 1;
      if (r.status === "FULFILLED") unitsReceived += r.unitsRequested;
      // "Highest priority" means the ones still needing attention — an emergency
      // that has already been fulfilled or rejected is no longer a live case.
      if (r.urgencyLevel === "EMERGENCY" && (r.status === "PENDING" || r.status === "APPROVED")) {
        openEmergencies += 1;
      }
    });

    return { thisMonth, pending, unitsReceived, openEmergencies };
  }, [requests]);

  /**
   * Submits the form and puts the new request straight at the top of the tracking
   * list from the 201 response, so the clerk sees it land without a refetch.
   */
  async function handleSubmit() {
    setSubmitting(true);
    setFormError(null);
    setFormNotice(null);
    try {
      const created = await submitBloodRequest({
        bloodType: group,
        unitsRequested: Number(units),
        urgencyLevel: urgency,
        patientReference: ward,
      });
      setRequests((current) => [created, ...current]);
      setFormNotice(`Request sent — ${created.unitsRequested} unit${created.unitsRequested > 1 ? "s" : ""} of ${created.bloodType}.`);
      // Only the ward clears: the group and urgency a ward is working through are
      // usually the same across consecutive requests, so re-typing them is friction.
      setWard("");
    } catch (err) {
      setFormError(err.message);
    } finally {
      setSubmitting(false);
    }
  }

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
        .hp-role { margin: clamp(2px, .8vh, 8px) 6px 0; font-size: clamp(8px, 1.1vh, 10px); font-weight: 700; letter-spacing: .1em; color: rgba(255,255,255,.5); }

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
        /* The bell moved to <NotificationBell>, which brings its own styles and
           inherits the tokens declared above. The mockup's .hp-bell rules were a
           fixed red dot that was always on; a count that is sometimes zero cannot
           be drawn with a ::after pseudo-element, and leaving them here would mean
           two sets of bell styles with only one bell. */
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
        .hp-submit:disabled { background: #C9CDD3; cursor: not-allowed; }

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
        .hp-list { flex: 1 1 auto; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: clamp(4px, .75vh, 8px); }
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

        /* Wiring states — additive, same tokens, for real async data and a real form. */
        .hp-loading, .hp-error { flex: 1 1 auto; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; text-align: center; }
        .hp-error-msg { color: var(--red); font-weight: 600; font-size: clamp(11px, 1.6vh, 14px); }
        .hp-empty { font-size: clamp(10px, 1.5vh, 13px); color: var(--ink-muted); padding: clamp(8px, 1.4vh, 14px) 2px; }
        .hp-form-msg { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; line-height: 1.35; }
        .hp-form-msg--error { color: var(--red); }
        .hp-form-msg--ok { color: var(--green); }

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

        <div className="hp-role">{user.role === "ADMIN" ? "ADMINISTRATOR" : "HOSPITAL STAFF"}</div>

        <nav className="hp-nav">
          <button type="button" className="is-active"><GridIcon /> Dashboard</button>
          <button type="button"><PlusIcon /> New Request</button>
          <button type="button"><ClockIcon /> Request History</button>
          <button type="button"><DropletIcon /> Blood Availability</button>
          <button type="button"><ChartIcon /> Report</button>
          <button type="button"><GearIcon /> Settings</button>
        </nav>

        <button type="button" className="hp-logout" onClick={onSignOut}><LogoutIcon /> Logout</button>
      </aside>

      {/* ================= MAIN ================= */}
      <main className="hp-main">
        <div className="hp-top">
          <div>
            <div className="hp-title">{hospital.name ?? "Hospital Portal"}</div>
            <div className="hp-sub">Blood request desk</div>
          </div>
          <div className="hp-top-right">
            <div className="hp-date"><CalendarIcon /> Today, {formatHeaderDate(today)}</div>
            <NotificationBell
              notifications={notifications}
              unreadCount={unreadCount}
              connected={connected}
              onMarkAllRead={markAllRead}
            />
            <div className="hp-avatar">{initials(user.fullName)}</div>
          </div>
        </div>

        {loading && (
          <div className="hp-loading"><div className="hp-sub">Loading request desk…</div></div>
        )}

        {!loading && error && (
          <div className="hp-error"><div className="hp-error-msg">{error}</div></div>
        )}

        {!loading && !error && (
          <>
            {/* ---- stat cards ---- */}
            <div className="hp-stats">
              <Stat tone="navy" icon={<FileIcon />} figure={stats.thisMonth} label="Total Requests" note="This month" />
              <Stat tone="amber" icon={<HourglassIcon />} figure={stats.pending} label="Pending Approval" note="Awaiting blood bank" />
              <Stat tone="green" icon={<CheckIcon />} figure={stats.unitsReceived} label="Fulfilled" note="Units received" />
              <Stat tone="red" icon={<AlertIcon />} figure={stats.openEmergencies} label="Emergency Cases" note="Highest priority" />
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
                        {URGENCIES.map((u) => (
                          <button key={u.value} type="button"
                                  className={urgency === u.value ? `on-${u.label.toLowerCase()}` : ""}
                                  onClick={() => setUrgency(u.value)}>
                            {u.label}
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

                    {formError && <div className="hp-form-msg hp-form-msg--error">{formError}</div>}
                    {formNotice && <div className="hp-form-msg hp-form-msg--ok">{formNotice}</div>}

                    <button type="button" className="hp-submit" onClick={handleSubmit} disabled={submitting}>
                      <SendIcon /> {submitting ? "Sending…" : "Send Request"}
                    </button>
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
                    {availability.map((a) => (
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
                    {requests.length === 0 && (
                      <div className="hp-empty">No requests yet. Submit one on the left.</div>
                    )}
                    {requests.map((r) => (
                      <div className="hp-item" key={r.id}>
                        <div className="hp-item-group">{r.bloodType}</div>
                        <div className="hp-item-body">
                          <div className="hp-item-main">{r.bloodType} · {r.unitsRequested} Unit{r.unitsRequested > 1 ? "s" : ""}</div>
                          <div className="hp-item-sub">
                            {/* No bank is named until one approves (Business Rule 6) —
                                saying so is more useful than an empty gap. */}
                            {r.fulfilledByBloodBankName ?? "Awaiting a blood bank"} · {formatRelativeTime(r.createdAt)}
                            {r.patientReference ? ` · ${r.patientReference}` : ""}
                          </div>
                        </div>
                        <div className={`hp-badge hp-b-${r.status.toLowerCase()}`}>{titleCase(r.status)}</div>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          </>
        )}
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
function FileIcon() { return (<svg viewBox="0 0 24 24" fill="none" stroke="#FFF" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M9 14h6M9 18h4"/></svg>); }
function HourglassIcon() { return (<svg viewBox="0 0 24 24" fill="none" stroke="#FFF" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"/></svg>); }
function CheckIcon() { return (<svg viewBox="0 0 24 24" fill="none" stroke="#FFF" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round"><path d="M4 12.5 9.5 18 20 7"/></svg>); }
function AlertIcon() { return (<svg viewBox="0 0 24 24" fill="none" stroke="#FFF" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 3 2 20h20L12 3z"/><path d="M12 10v4M12 17.5v.01"/></svg>); }
function SendIcon() { return (<svg viewBox="0 0 24 24" fill="none" stroke="#FFF" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M22 2 11 13M22 2l-7 20-4-9-9-4 20-7z"/></svg>); }
