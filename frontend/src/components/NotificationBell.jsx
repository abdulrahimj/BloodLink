import React, { useEffect, useRef, useState } from 'react';

/* BloodLink — notification bell, shared by the portals: shows how many and, when opened, what.
   Declares no colours of its own, inheriting each portal's CSS variables. */

const ICON = { fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round' };

/** "8 mins ago" down to a plain date for each row's timestamp, kept local so the component fits any portal. */
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function relativeTime(instantIso) {
  const minutes = Math.floor((Date.now() - new Date(instantIso).getTime()) / 60000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes} min${minutes === 1 ? '' : 's'} ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hr${hours === 1 ? '' : 's'} ago`;
  const days = Math.floor(hours / 24);
  if (days === 1) return 'Yesterday';
  if (days < 7) return `${days} days ago`;
  const d = new Date(instantIso);
  return `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
}

/**
 * Dot colour by notification type: red shortage, expired stock or a password reset by an administrator (31), amber
 * awaiting a decision or coming up soon (a password reset request too, 33), navy settled, green an appeal or booked visit.
 */
const TYPE_TONE = {
  LOW_STOCK_ALERT: 'var(--red)',
  NEW_REQUEST: 'var(--amber)',
  REQUEST_STATUS_CHANGE: 'var(--navy)',
  REQUEST_CANCELLED: 'var(--navy)',
  DONATION_APPEAL: 'var(--green)',
  DONATION_APPEAL_ACCEPTED: 'var(--navy)',
  BATCH_EXPIRY_REMINDER: 'var(--amber)',
  BATCH_EXPIRED: 'var(--red)',
  DONATION_BOOKED: 'var(--green)',
  DONATION_BOOKING_CANCELLED: 'var(--navy)',
  DONATION_BOOKING_REMINDER: 'var(--amber)',
  PASSWORD_RESET_BY_ADMIN: 'var(--red)',
  PASSWORD_RESET_REQUESTED: 'var(--amber)',
};

/**
 * notifications newest first; unreadCount drives the badge (hidden at zero); connected is the live socket state;
 * onMarkAllRead clears the badge; optional onOpen { canOpen(item), open(item) } makes some notices clickable.
 */
export default function NotificationBell({ notifications, unreadCount, connected, onMarkAllRead, onOpen }) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef(null);

  // A click elsewhere or Escape closes the panel, bound at the document since it is not a native popover.
  useEffect(() => {
    if (!open) return undefined;

    const onPointerDown = (event) => {
      if (!rootRef.current?.contains(event.target)) setOpen(false);
    };
    const onKeyDown = (event) => {
      if (event.key === 'Escape') setOpen(false);
    };

    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  // Capped at 99+ so a long-neglected bell cannot widen the header it sits in.
  const badge = unreadCount > 99 ? '99+' : String(unreadCount);

  return (
    <div className="nb-root" ref={rootRef}>
      <style>{`
        .nb-root { position: relative; display: flex; align-items: center; }

        .nb-button {
          position: relative; display: grid; place-items: center;
          background: transparent; border: none; padding: 0; cursor: pointer;
          color: inherit; font: inherit;
        }
        .nb-button svg { width: clamp(16px, 2.3vh, 20px); height: clamp(16px, 2.3vh, 20px); display: block; }
        .nb-button:focus-visible { outline: 2px solid var(--navy); outline-offset: 3px; border-radius: 4px; }

        /* Replaces the mockups' fixed red dot. Same position, same red — but it
           now appears only when there is genuinely something unread, and carries
           the number instead of implying "one thing". */
        .nb-badge {
          position: absolute; top: -5px; right: -6px;
          min-width: 15px; height: 15px; padding: 0 4px;
          border-radius: 999px; background: var(--red); color: #FFF;
          font-size: 9px; font-weight: 800; line-height: 15px; text-align: center;
          border: 1.5px solid var(--page-bg);
        }

        /* Live-session indicator: a small dot, shown only while the socket is
           NOT connected. Present-and-quiet is the normal state, so the absence
           of any mark means "live" and the mark itself means "not live" —
           otherwise a permanent green dot would just be decoration. */
        .nb-offline {
          position: absolute; bottom: -4px; right: -5px;
          width: 6px; height: 6px; border-radius: 50%;
          background: var(--ink-muted); border: 1.5px solid var(--page-bg);
        }

        .nb-panel {
          position: absolute; top: calc(100% + 10px); right: 0; z-index: 60;
          width: min(340px, 78vw);
          background: var(--card-bg); border-radius: var(--radius);
          box-shadow: 0 12px 40px rgba(0,0,0,.18);
          border: 1px solid var(--line);
          display: flex; flex-direction: column;
          overflow: hidden;
        }
        .nb-head {
          display: flex; align-items: baseline; justify-content: space-between; gap: 10px;
          padding: 11px 14px 9px; border-bottom: 1px solid var(--line);
        }
        .nb-title { font-size: 13.5px; font-weight: 700; letter-spacing: -.01em; }
        .nb-clear {
          background: transparent; border: none; padding: 0; cursor: pointer;
          font-family: inherit; font-size: 11.5px; font-weight: 600; color: var(--red);
        }
        .nb-clear:disabled { color: var(--ink-muted); cursor: default; }

        /* Capped and scrollable: fifty notifications must not push the panel off
           the bottom of a laptop screen. */
        .nb-list { max-height: min(46vh, 380px); overflow-y: auto; }
        .nb-item {
          display: flex; gap: 9px; align-items: flex-start;
          padding: 9px 14px; border-bottom: 1px solid var(--line);
        }
        .nb-item:last-child { border-bottom: none; }
        .nb-item--open {
          width: 100%; border: none; border-bottom: 1px solid var(--line); background: transparent;
          font: inherit; text-align: left; cursor: pointer;
        }
        .nb-item--open.nb-item--unread { background: #F7F9FB; }
        .nb-item--open:hover { background: #EEF2F6; }
        .nb-item--open:focus-visible { outline: 2px solid var(--navy); outline-offset: -2px; }
        /* An unread row is tinted rather than badged — it reads at a glance
           without adding another piece of furniture to each line. */
        .nb-item--unread { background: #F7F9FB; }
        .nb-dot { flex: 0 0 auto; width: 7px; height: 7px; border-radius: 50%; margin-top: 5px; }
        .nb-text { flex: 1 1 auto; min-width: 0; }
        .nb-message { font-size: 12px; line-height: 1.45; color: var(--ink); }
        .nb-when { font-size: 10px; color: var(--ink-muted); margin-top: 3px; }

        .nb-empty { padding: 18px 14px; font-size: 12px; color: var(--ink-muted); text-align: center; }
        .nb-foot { padding: 8px 14px; font-size: 10px; color: var(--ink-muted); border-top: 1px solid var(--line); }
      `}</style>

      <button
        type="button"
        className="nb-button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={unreadCount > 0 ? `Notifications, ${unreadCount} unread` : 'Notifications'}
        onClick={() => setOpen((current) => !current)}
      >
        <BellIcon />
        {unreadCount > 0 && <span className="nb-badge">{badge}</span>}
        {!connected && <span className="nb-offline" title="Live updates are reconnecting" />}
      </button>

      {open && (
        <div className="nb-panel" role="dialog" aria-label="Notifications">
          <div className="nb-head">
            <div className="nb-title">Notifications</div>
            <button
              type="button"
              className="nb-clear"
              disabled={unreadCount === 0}
              onClick={onMarkAllRead}
            >
              Mark all read
            </button>
          </div>

          <div className="nb-list">
            {notifications.length === 0 && (
              <div className="nb-empty">Nothing yet. Alerts will appear here as they happen.</div>
            )}
            {notifications.map((item) => {
              const body = (
                <>
                  <span className="nb-dot" style={{ background: TYPE_TONE[item.type] ?? 'var(--ink-muted)' }} />
                  <div className="nb-text">
                    <div className="nb-message">{item.message}</div>
                    <div className="nb-when">{relativeTime(item.createdAt)}</div>
                  </div>
                </>
              );
              const className = `nb-item${item.read ? '' : ' nb-item--unread'}`;
              // A notice the portal can open (e.g. a password reset request) is a button that closes the panel.
              return onOpen?.canOpen(item) ? (
                <button
                  type="button" className={`${className} nb-item--open`} key={item.id}
                  onClick={() => { setOpen(false); onOpen.open(item); }}
                >
                  {body}
                </button>
              ) : (
                <div className={className} key={item.id}>{body}</div>
              );
            })}
          </div>

          {!connected && (
            <div className="nb-foot">
              Live updates are reconnecting — this list is still correct as of the last refresh.
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/* The mockups' own bell, unchanged — the same path both portal files draw. */
function BellIcon() {
  return (
    <svg viewBox="0 0 24 24" {...ICON}>
      <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0" />
    </svg>
  );
}
