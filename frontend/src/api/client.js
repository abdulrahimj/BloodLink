/* BloodLink — API client: where the API lives, how the JWT is attached,
   and how a failed response becomes an error a form can show. */

import { clearSession, getToken } from '../auth/session.js';

/** Exported so the WebSocket client can open its socket against the same origin
 *  the REST calls use — two base URLs would eventually disagree. */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/**
 * Error carrying the backend's ApiError: message is safe to show;
 * fieldErrors (400s) maps field names to messages for the form.
 */
export class ApiError extends Error {
  constructor(message, status, fieldErrors) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors ?? {};
  }
}

/**
 * JSON request with the bearer token (unless auth: false). A 401 without field errors discards the session;
 * a 401 naming a field (e.g. wrong current password) is the user's input, so the session is kept.
 */
export async function apiRequest(path, { method = 'GET', body, auth = true } = {}) {
  const headers = { 'Content-Type': 'application/json' };

  const token = auth ? getToken() : null;
  if (token) headers.Authorization = `Bearer ${token}`;

  let response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    // fetch only rejects on a network failure, so the user is told to check the connection
    // rather than shown a misleading error.
    throw new ApiError('Could not reach the BloodLink server. Check your connection and try again.', 0);
  }

  // 204 and other empty bodies would break response.json(), so read as text first.
  const text = await response.text();
  const payload = text ? safeParse(text) : null;

  throwIfFailed(response, payload, Boolean(auth && token));
  return payload;
}

/**
 * Downloads a file the API builds (a Reports CSV) as { blob, fileName }, fetched with the bearer token
 * a plain link can't send; the name comes from Content-Disposition. Errors as in apiRequest.
 */
export async function apiDownload(path) {
  const token = getToken();
  const headers = token ? { Authorization: `Bearer ${token}` } : {};

  let response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { headers });
  } catch {
    throw new ApiError('Could not reach the BloodLink server. Check your connection and try again.', 0);
  }

  if (!response.ok) {
    const text = await response.text();
    throwIfFailed(response, text ? safeParse(text) : null, Boolean(token));
  }

  const disposition = response.headers.get('Content-Disposition') ?? '';
  const fileName = /filename="?([^";]+)"?/i.exec(disposition)?.[1] ?? 'bloodlink-report.csv';
  return { blob: await response.blob(), fileName };
}

/**
 * Shared failure handling: a 401 discards the session unless it names a typed field,
 * and any non-2xx becomes an ApiError with the server's message and field errors.
 */
function throwIfFailed(response, payload, sentToken) {
  const rejectsTypedField = payload?.fieldErrors && Object.keys(payload.fieldErrors).length > 0;
  if (response.status === 401 && sentToken && !rejectsTypedField) {
    clearSession();
  }

  if (!response.ok) {
    throw new ApiError(
      payload?.message ?? `Request failed (${response.status}).`,
      response.status,
      payload?.fieldErrors,
    );
  }
}

/** Tolerates a non-JSON error body (e.g. a proxy's HTML 502) instead of throwing. */
function safeParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
