/* BloodLink — notification socket: one STOMP-over-SockJS session per tab, server-to-client only
   (no publish function). SockJS falls back to HTTP where WebSocket is blocked. */

import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

import { API_BASE_URL } from '../api/client.js';
import { socketRefusedForPasswordChange } from '../lib/sessionEnd.js';

/** Where the server addresses one user's own notifications. Spring rewrites
 *  this per session, so a client cannot subscribe to anyone else's. */
const USER_QUEUE = '/user/queue/notifications';

/**
 * Opens a notification session (JWT on the CONNECT frame) and returns a disconnect function, safe to call twice.
 * Reconnects every 5s; missed notifications are read over REST, so nothing is replayed.
 */
export function connectNotifications({ getToken, onNotification, onStatusChange, onPasswordChanged }) {
  const report = (isConnected) => onStatusChange?.(isConnected);

  const client = new Client({
    // A factory rather than a brokerURL: SockJS negotiates its own transport,
    // so there is no fixed ws:// URL to hand over.
    webSocketFactory: () => new SockJS(`${API_BASE_URL}/ws`),
    // Read on every attempt: after a password change the server closes this socket, and the reconnect
    // must carry the fresh token, not the one it first opened with (Business Rule 32).
    beforeConnect: () => {
      client.connectHeaders = { Authorization: `Bearer ${getToken()}` };
    },
    reconnectDelay: 5000,

    onConnect: () => {
      client.subscribe(USER_QUEUE, (frame) => {
        try {
          onNotification(JSON.parse(frame.body));
        } catch {
          // An unreadable frame is dropped; the notification is still in the database for the next load.
        }
      });
      report(true);
    },

    // Fired when the transport closes for any reason, including the ordinary
    // one of the tab being closed. The client retries on its own afterwards.
    onWebSocketClose: () => report(false),

    // A STOMP ERROR frame (most likely a rejected token) is terminal, so stop rather than retry forever.
    onStompError: (frame) => {
      report(false);
      client.deactivate();
      if (socketRefusedForPasswordChange(frame)) onPasswordChanged?.();
    },
  });

  client.activate();

  return () => {
    report(false);
    client.deactivate();
  };
}
