/* ================================================================
   BloodLink — client-side session

   Holds the JWT returned by /api/auth/login and /api/auth/register,
   plus the user summary that came with it, so the app can render a
   role-appropriate view without an extra round trip.

   The token is short-lived and the server re-checks the caller's role on
   every request, so this browser copy is a convenience, never the authority.

   Two stores back the Login screen's "Remember me":
     localStorage   — survives closing the tab, so staff are not signed out
                      mid-shift by a refresh. Used when the box is ticked.
     sessionStorage — cleared when the tab closes. Used when it is not,
                      which matters on the shared machines at a facility.
   ================================================================ */

const TOKEN_KEY = 'bloodlink.token';
const USER_KEY = 'bloodlink.user';

/**
 * Persists the token and user summary from an auth response.
 * Writes to one store and clears the other, so a later sign-in with a different
 * "Remember me" choice can never leave a stale token behind in the unused store.
 */
export function saveSession({ token, user }, remember = true) {
  const target = remember ? localStorage : sessionStorage;
  const other = remember ? sessionStorage : localStorage;

  other.removeItem(TOKEN_KEY);
  other.removeItem(USER_KEY);

  target.setItem(TOKEN_KEY, token);
  target.setItem(USER_KEY, JSON.stringify(user));
}

/** The raw JWT, or null when signed out. Used to build the Authorization header. */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) ?? sessionStorage.getItem(TOKEN_KEY);
}

/**
 * The stored user summary ({ id, fullName, email, phone, role }), or null.
 * Wrapped in try/catch because a partially-cleared or hand-edited store would
 * otherwise throw on JSON.parse and break the whole app shell.
 */
export function getUser() {
  try {
    const raw = localStorage.getItem(USER_KEY) ?? sessionStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/** True when a token is present. Not proof of validity — the server decides that. */
export function isSignedIn() {
  return getToken() !== null;
}

/** Signs out by discarding the local copies; a stateless JWT has nothing to revoke. */
export function clearSession() {
  [localStorage, sessionStorage].forEach((store) => {
    store.removeItem(TOKEN_KEY);
    store.removeItem(USER_KEY);
  });
}
