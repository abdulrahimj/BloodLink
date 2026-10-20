/* BloodLink — notification calls: the REST side of the bell, scoped to the JWT's owner;
   fills the bell with whatever arrived while no socket was open. */

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
 * PATCH /api/notifications/read-all: clears the badge and returns the resulting count (zero).
 */
export function markAllNotificationsRead() {
  return apiRequest('/api/notifications/read-all', { method: 'PATCH' });
}

/** PATCH /api/notifications/{id} — marks one read. 404 if it isn't the caller's. */
export function markNotificationRead(notificationId) {
  return apiRequest(`/api/notifications/${notificationId}`, { method: 'PATCH' });
}
