/* ================================================================
   BloodLink — notification calls

   The REST side of the bell icon. Every route is scoped server-side to
   whoever owns the JWT, so there is no user id to pass here — see
   NotificationService on the backend.

   These exist alongside the live WebSocket push, not instead of it. A socket
   only carries what arrives while it is open, so this is what fills the bell
   with everything that happened while the browser was closed.
   ================================================================ */

import { apiRequest } from './client.js';

/** GET /api/notifications — the caller's 50 most recent, newest first. */
export function getNotifications() {
  return apiRequest('/api/notifications');
}

/** GET /api/notifications/unread-count — just the badge number. */
export function getUnreadCount() {
  return apiRequest('/api/notifications/unread-count');
}

/**
 * PATCH /api/notifications/read-all — clears the badge.
 * Returns the count afterwards (zero), so the caller can set the badge from the
 * response rather than assuming the write landed.
 */
export function markAllNotificationsRead() {
  return apiRequest('/api/notifications/read-all', { method: 'PATCH' });
}

/** PATCH /api/notifications/{id} — marks one read. 404 if it isn't the caller's. */
export function markNotificationRead(notificationId) {
  return apiRequest(`/api/notifications/${notificationId}`, { method: 'PATCH' });
}
