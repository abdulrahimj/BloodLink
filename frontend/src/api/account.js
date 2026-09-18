/* ================================================================
   BloodLink — the signed-in user's own account (Business Rule 16)

   Behind every role's Profile Settings screen. Every call acts on the
   caller's own account, identified by their token — there is no user id
   to pass, and no way to aim these at anyone else.
   ================================================================ */

import { apiRequest } from './client.js';

/** GET /api/users/me — name, email, and the admin-managed facts (phone, role,
 *  facility) the screen shows read-only. */
export function getMyAccount() {
  return apiRequest('/api/users/me');
}

/**
 * PATCH /api/users/me — saves a new full name and email. Both are sent every
 * time. Nothing else may be: the server refuses a request carrying any other
 * field (phone, role, facility) outright, rather than ignoring it.
 */
export function updateMyDetails({ fullName, email }) {
  return apiRequest('/api/users/me', { method: 'PATCH', body: { fullName, email } });
}

/**
 * PATCH /api/users/me/password — changes the password, given the current one.
 *
 * A wrong current password comes back as a 401 with a field error on
 * "currentPassword". client.js deliberately does NOT treat that as an expired
 * session, so the user stays signed in and sees the message under the box.
 * Resolves to null on success (204, no body).
 */
export function changeMyPassword({ currentPassword, newPassword }) {
  return apiRequest('/api/users/me/password', {
    method: 'PATCH',
    body: { currentPassword, newPassword },
  });
}
