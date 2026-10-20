/* BloodLink — client-side session: the JWT and user summary from login/register (a convenience, never the authority).
   "Remember me" picks localStorage (survives the tab) or sessionStorage (cleared on close). */

const TOKEN_KEY = 'bloodlink.token';
const USER_KEY = 'bloodlink.user';

/**
 * Stores the token and user summary in one store and clears the other, so no stale token is left behind.
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
 * The stored user summary or null; try/catch so a damaged store cannot break the app shell.
 */
export function getUser() {
  try {
    const raw = localStorage.getItem(USER_KEY) ?? sessionStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/**
 * Merges changed fields into the stored user summary after Profile Settings saves,
 * in whichever store holds the session, keeping the "Remember me" choice. No-op when signed out.
 */
export function updateSessionUser(changes) {
  const store = localStorage.getItem(TOKEN_KEY) !== null ? localStorage
    : sessionStorage.getItem(TOKEN_KEY) !== null ? sessionStorage
    : null;
  const current = getUser();
  if (!store || !current) return null;

  const updated = { ...current, ...changes };
  store.setItem(USER_KEY, JSON.stringify(updated));
  return updated;
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
