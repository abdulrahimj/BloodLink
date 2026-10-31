/* BloodLink — notification state for one screen: the REST read that fills the bell on load
   plus the live socket, shared so every portal's bell behaves the same. */

import { useCallback, useEffect, useRef, useState } from 'react';

import { getNotifications, getUnreadCount, markAllNotificationsRead } from '../api/notifications.js';
import { endSession, getToken } from '../auth/session.js';
import { PASSWORD_CHANGED_MESSAGE } from '../lib/sessionEnd.js';
import { connectNotifications } from './notificationSocket.js';

/**
 * options.onNotification is called on each live arrival for screens that react beyond the bell.
 * Returns { notifications, unreadCount, connected, markAllRead, reload }.
 */
export function useNotifications({ onNotification } = {}) {
  const [notifications, setNotifications] = useState([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [connected, setConnected] = useState(false);

  // Held in a ref so an inline callback doesn't reopen the WebSocket on every render.
  const onNotificationRef = useRef(onNotification);
  onNotificationRef.current = onNotification;

  /** Re-reads both the list and the count from the API. */
  const reload = useCallback(async () => {
    const [list, count] = await Promise.all([getNotifications(), getUnreadCount()]);
    setNotifications(list);
    setUnreadCount(count.unreadCount);
  }, []);

  // Initial fill; a failure is swallowed, since the bell is secondary to the screen.
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

  // The socket is an accelerator; the REST rows are the record. Re-reading every 30s means a dropped
  // socket still shows a donor's acceptance (or anything else) without reloading the portal.
  useEffect(() => {
    const intervalId = window.setInterval(() => {
      reload().catch(() => {});
    }, 30_000);

    return () => window.clearInterval(intervalId);
  }, [reload]);

  // The live session. Opened once per signed-in screen and closed on unmount —
  // signing out unmounts the portal, which is what disconnects the socket.
  useEffect(() => {
    if (!getToken()) return undefined;

    return connectNotifications({
      getToken,
      onStatusChange: setConnected,
      // Refused because the password changed since this sign-in: back to Sign in (Business Rule 32).
      onPasswordChanged: () => endSession(PASSWORD_CHANGED_MESSAGE),
      onNotification: (notification) => {
        // Prepended (newest first) and de-duplicated by id, since a reconnect can overlap a reload.
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
   * Clears the badge using the server's returned count, so a notification arriving meanwhile is still counted.
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

  /** Applies a server-returned notification after an action such as appeal acceptance. */
  const updateNotification = useCallback((notification) => {
    const previous = notifications.find((item) => item.id === notification.id);
    if (previous && !previous.read && notification.read) {
      setUnreadCount((count) => Math.max(0, count - 1));
    }
    setNotifications((current) => current.map((item) => item.id === notification.id ? notification : item));
  }, [notifications]);

  return { notifications, unreadCount, connected, markAllRead, reload, updateNotification };
}
