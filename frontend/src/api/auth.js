/* BloodLink — authentication calls: both public endpoints return AuthResponse and store the session,
   so Login and Register share one success path. */

import { apiRequest } from './client.js';
import { saveSession } from '../auth/session.js';

/**
 * Registers a donor (User + Donor) without a token (public endpoint),
 * storing the returned session so the caller can go straight to the dashboard.
 */
export async function registerDonor(payload) {
  const auth = await apiRequest('/api/auth/register', { method: 'POST', body: payload, auth: false });
  // A brand-new account persists: there is no "remember me" on the sign-up screen,
  // and bouncing someone to the login form straight after registering is pointless.
  saveSession(auth, true);
  return auth;
}

/**
 * Exchanges phone and password for a JWT and stores the session; remember picks storage that survives the tab.
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
