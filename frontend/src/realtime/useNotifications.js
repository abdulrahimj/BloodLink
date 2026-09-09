/* ================================================================
   BloodLink — notification state for one screen

   Joins the two halves of delivery into a single piece of state a portal can
   render: the REST read that fills the bell on load, and the live socket that
   keeps it current afterwards. Both portals use this, so the bell behaves
   identically on the Donor and Blood Bank screens rather than being wired
   twice and drifting.
   ================================================================ */

import { useCallback, useEffect, useRef, useState } from 'react';

import { getNotifications, getUnreadCount, markAllNotificationsRead } from '../api/notifications.js';
import { getToken } from '../auth/session.js';
import { connectNotifications } from './notificationSocket.js';

/**
 * @param {object}   [options]
 * @param {function} [options.onNotification] called with each live arrival, for a
 *        screen that has to react to more than the bell — the Blood Bank Dashboard
 *        reloads its stock table and request panel when one of those changes.
 *
 * @returns {{notifications: Array, unreadCount: number, connected: boolean,
 *            markAllRead: function, reload: function}}
 */
export function useNotifications({ onNotification } = {}) {
  const [notifications, setNotifications] = useState([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [connected, setConnected] = useState(false);

  // Held in a ref rather than read from the closure, so that a caller passing an
  // inline arrow function (which is a new function on every render) does not tear
  // down and reopen the WebSocket on every render.
  const onNotificationRef = useRef(onNotification);
  onNotificationRef.current = onNotification;

  /** Re-reads both the list and the count from the API. */
  const reload = useCallback(async () => {
    const [list, count] = await Promise.all([getNotifications(), getUnreadCount()]);
    setNotifications(list);
    setUnreadCount(count.unreadCount);
  }, []);

  // Initial fill. A failure here is deliberately swallowed: the bell is secondary
  // to whatever the screen is actually for, and a page-level error banner because
  // the notification count could not be fetched would be worse than a bell showing
  // nothing. The dashboard's own calls report their own failures.
  useEffect(() => {
    let cancelled = false;

    Promise.all([getNotifications(), getUnreadCount()])
      .then(([list, count]) => {
        if (cancelled) return;
        setNotifications(list);
        setUnreadCount(count.unreadCount);
      })
      .catch(() => {});

    return () => { cancelled = true; };
  }, []);

  // The live session. Opened once per signed-in screen and closed on unmount —
  // signing out unmounts the portal, which is what disconnects the socket.
  useEffect(() => {
    const token = getToken();
    if (!token) return undefined;

    return connectNotifications({
      token,
      onStatusChange: setConnected,
      onNotification: (notification) => {
        // Prepended, because the list is newest-first everywhere it is read.
        // Guarded against a duplicate id: a reconnect can overlap with a reload,
        // and the same notification arriving twice would show as two rows.
        setNotifications((current) =>
          current.some((existing) => existing.id === notification.id)
            ? current
            : [notification, ...current]);

        if (!notification.read) {
          setUnreadCount((current) => current + 1);
        }

        onNotificationRef.current?.(notification);
      },
    });
  }, []);

  /**
   * Clears the badge. The count is taken from the server's response rather than
   * assumed to be zero, so a notification that arrived between the click and the
   * response is still counted instead of being silently swallowed.
   */
  const markAllRead = useCallback(async () => {
    try {
      const { unreadCount: remaining } = await markAllNotificationsRead();
      setUnreadCount(remaining);
      setNotifications((current) => current.map((item) => ({ ...item, read: true })));
    } catch {
      // Leaving the badge as it was is the honest outcome of a failed write.
    }
  }, []);

  return { notifications, unreadCount, connected, markAllRead, reload };
}
