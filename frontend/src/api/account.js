/* BloodLink — the signed-in user's own account (Business Rule 16), behind every role's Profile Settings.
   Every call acts on the caller's own account via the token; there is no user id to pass. */

import { apiRequest } from './client.js';
import { replaceSessionToken } from '../auth/session.js';

/** GET /api/users/me — name, email, and the admin-managed facts (phone, role,
 *  facility) the screen shows read-only. */
export function getMyAccount() {
  return apiRequest('/api/users/me');
}

/**
 * PATCH /api/users/me: saves full name and email, both always sent; any other field is refused by the server.
 */
export function updateMyDetails({ fullName, email }) {
  return apiRequest('/api/users/me', { method: 'PATCH', body: { fullName, email } });
}

/**
 * PATCH /api/users/me/password: a wrong current password is a 401 on "currentPassword", which keeps the session.
 * Answers like sign-in; the fresh token is stored at once, since older ones stop working (Business Rule 32).
 */
export async function changeMyPassword({ currentPassword, newPassword }) {
  const auth = await apiRequest('/api/users/me/password', {
    method: 'PATCH',
    body: { currentPassword, newPassword },
  });
  replaceSessionToken(auth?.token);
  return auth;
}
