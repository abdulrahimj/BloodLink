/* ================================================================
   BloodLink — authentication calls

   Wraps the two public endpoints. Both return the same AuthResponse
   shape, and both persist the session on success, so Login and Register
   share one post-submit path.
   ================================================================ */

import { apiRequest } from './client.js';
import { saveSession } from '../auth/session.js';

/**
 * Registers a voluntary donor (creates the User and its linked Donor row).
 *
 * `auth: false` — this is a public endpoint, and any token already in storage is
 * irrelevant to creating a new account.
 *
 * The backend signs the new donor in immediately, so the session is stored here and
 * the caller can route straight to the dashboard.
 */
export async function registerDonor(payload) {
  const auth = await apiRequest('/api/auth/register', { method: 'POST', body: payload, auth: false });
  // A brand-new account persists: there is no "remember me" on the sign-up screen,
  // and bouncing someone to the login form straight after registering is pointless.
  saveSession(auth, true);
  return auth;
}

/**
 * Exchanges phone + password for a JWT and stores the resulting session.
 * `remember` comes from the Login screen's checkbox and picks the storage that
 * survives closing the tab (see session.js).
 */
export async function login(phone, password, remember = true) {
  const auth = await apiRequest('/api/auth/login', {
    method: 'POST',
    body: { phone, password },
    auth: false,
  });
  saveSession(auth, remember);
  return auth;
}
