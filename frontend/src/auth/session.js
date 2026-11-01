/* BloodLink — client-side session: the JWT and user summary from login/register (a convenience, never the authority).
   "Remember me" picks localStorage (survives the tab) or sessionStorage (cleared on close); blocked storage means memory. */

import { safeLocalStorage, safeSessionStorage } from '../lib/safeStorage.js';

const TOKEN_KEY = 'bloodlink.token';
const USER_KEY = 'bloodlink.user';

/**
 * Stores the token and user summary in one store and clears the other, so no stale token is left behind.
 */
export function saveSession({ token, user }, remember = true) {
  const target = remember ? safeLocalStorage : safeSessionStorage;
  const other = remember ? safeSessionStorage : safeLocalStorage;

  other.removeItem(TOKEN_KEY);
  other.removeItem(USER_KEY);

  target.setItem(TOKEN_KEY, token);
  target.setItem(USER_KEY, JSON.stringify(user));
}

/** The raw JWT, or null when signed out. Used to build the Authorization header. */
export function getToken() {
  return safeLocalStorage.getItem(TOKEN_KEY) ?? safeSessionStorage.getItem(TOKEN_KEY);
}

/**
 * The stored user summary or null; try/catch so a damaged store cannot break the app shell.
 */
export function getUser() {
  try {
    const raw = safeLocalStorage.getItem(USER_KEY) ?? safeSessionStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/** Whichever store holds the session, or null when signed out. */
function sessionStore() {
  if (safeLocalStorage.getItem(TOKEN_KEY) !== null) return safeLocalStorage;
  return safeSessionStorage.getItem(TOKEN_KEY) !== null ? safeSessionStorage : null;
}

/**
 * Merges changed fields into the stored user summary after Profile Settings saves,
 * in whichever store holds the session, keeping the "Remember me" choice. No-op when signed out.
 */
export function updateSessionUser(changes) {
  const store = sessionStore();
  const current = getUser();
  if (!store || !current) return null;

  const updated = { ...current, ...changes };
  store.setItem(USER_KEY, JSON.stringify(updated));
  return updated;
}

/**
 * Swaps in the fresh token a password change answers with (Business Rule 32), in whichever store holds the session,
 * so this device stays signed in. No-op when signed out.
 */
export function replaceSessionToken(token) {
  const store = sessionStore();
  if (store && token) store.setItem(TOKEN_KEY, token);
}

/** Fired on window when the server ends the session itself; detail.message says why, for the Sign in screen. */
export const SESSION_ENDED_EVENT = 'bloodlink:session-ended';

/** Signs out because the server refused the token, and tells the app shell why (Business Rule 32). */
export function endSession(message) {
  clearSession();
  window.dispatchEvent(new CustomEvent(SESSION_ENDED_EVENT, { detail: { message } }));
}

/** True when a token is present. Not proof of validity — the server decides that. */
export function isSignedIn() {
  return getToken() !== null;
}

/** Signs out by discarding the local copies; a stateless JWT has nothing to revoke. */
export function clearSession() {
  [safeLocalStorage, safeSessionStorage].forEach((store) => {
    store.removeItem(TOKEN_KEY);
    store.removeItem(USER_KEY);
  });
}
