/* ================================================================
   BloodLink — notification socket

   The client half of the WebSocket layer in Figure 3.1: one STOMP session
   per signed-in browser tab, carrying server-to-client notifications only.
   Nothing is ever sent up this socket — requests, approvals and stock edits
   all go over REST — so this module has no publish function by design.

   STOMP over SockJS, matching the backend's endpoint. SockJS falls back to
   HTTP streaming or long-polling where a WebSocket upgrade is blocked, which
   on the connections these facilities actually have is the difference
   between real-time working and appearing to be broken.
   ================================================================ */

import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

import { API_BASE_URL } from '../api/client.js';

/** Where the server addresses one user's own notifications. Spring rewrites
 *  this per session, so a client cannot subscribe to anyone else's. */
const USER_QUEUE = '/user/queue/notifications';

/**
 * Opens a notification session and returns a function that closes it.
 *
 * The JWT travels as a header on the STOMP CONNECT frame rather than on the
 * handshake, because the browser's WebSocket API has no way to set a header on
 * the handshake itself. The backend's StompAuthChannelInterceptor reads it there
 * and refuses the session outright if it is missing or expired.
 *
 * Reconnection is automatic and deliberately unobtrusive: a dropped connection
 * retries every five seconds, and because every notification is also a row in the
 * database, whatever arrived during the gap is picked up by the caller's next REST
 * read rather than being lost. That is why nothing here tries to replay missed
 * messages — the socket is an accelerator, never the record.
 *
 * @param {object}   options
 * @param {string}   options.token            the signed-in user's JWT.
 * @param {function} options.onNotification   called with each notification as it arrives.
 * @param {function} [options.onStatusChange] called with true/false as the session opens and closes.
 * @returns {function} disconnect — safe to call more than once.
 */
export function connectNotifications({ token, onNotification, onStatusChange }) {
  const report = (isConnected) => onStatusChange?.(isConnected);

  const client = new Client({
    // A factory rather than a brokerURL: SockJS negotiates its own transport,
    // so there is no fixed ws:// URL to hand over.
    webSocketFactory: () => new SockJS(`${API_BASE_URL}/ws`),
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 5000,

    onConnect: () => {
      client.subscribe(USER_QUEUE, (frame) => {
        try {
          onNotification(JSON.parse(frame.body));
        } catch {
          // A frame we cannot read is not worth taking the session down for —
          // the notification is still in the database and will appear on the
          // next load. Dropping it silently is the least damaging outcome.
        }
      });
      report(true);
    },

    // Fired when the transport closes for any reason, including the ordinary
    // one of the tab being closed. The client retries on its own afterwards.
    onWebSocketClose: () => report(false),

    // A STOMP ERROR frame is terminal for the session — a rejected token, most
    // likely, which reconnecting cannot fix. Stop rather than retry every five
    // seconds forever against a server that has already said no.
    onStompError: () => {
      report(false);
      client.deactivate();
    },
  });

  client.activate();

  return () => {
    report(false);
    client.deactivate();
  };
}
