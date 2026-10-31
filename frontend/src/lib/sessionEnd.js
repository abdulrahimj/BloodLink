/* BloodLink — why a session ended (Business Rule 32): a token from before the account's last password change is
   refused with a 401 carrying this code, or a socket refused with this message. Mirrors the server's wording. */

export const PASSWORD_CHANGED_CODE = 'PASSWORD_CHANGED';
export const PASSWORD_CHANGED_MESSAGE = 'Your password was changed. Please sign in again.';

/**
 * What a failed REST response means for the session: 'password-changed' (sign out and say why), 'expired' (any
 * other 401 to a request that carried a token: discard it), or null (keep it, e.g. a 401 naming a typed field).
 */
export function sessionEndFor(status, payload, sentToken) {
  if (status !== 401 || !sentToken) return null;
  if (payload?.code === PASSWORD_CHANGED_CODE) return 'password-changed';
  const namesTypedField = payload?.fieldErrors && Object.keys(payload.fieldErrors).length > 0;
  return namesTypedField ? null : 'expired';
}

/** True when a STOMP ERROR frame refused the socket because the password changed since its token was issued. */
export function socketRefusedForPasswordChange(frame) {
  return frame?.headers?.message === PASSWORD_CHANGED_MESSAGE;
}
