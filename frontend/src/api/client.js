/* ================================================================
   BloodLink — API client

   One place that knows how to call the Spring Boot API: where it lives,
   how to attach the JWT, and how to turn a failed response into an error
   a form can display.
   ================================================================ */

import { clearSession, getToken } from '../auth/session.js';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/**
 * Error carrying the backend's ApiError payload.
 *
 * `message` is always safe to show the user directly — the backend writes those
 * strings for display. `fieldErrors` is present on validation failures (400) and
 * maps a request field name to its message, so a form can mark the offending input.
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
 * Performs a JSON request against the API.
 *
 * Attaches the bearer token when one is stored, unless `auth: false` — which the
 * register and login calls use, since sending a stale token to them is meaningless.
 *
 * A 401 on an authenticated call means the token expired or was rejected, so the
 * stored session is discarded rather than left to fail every subsequent request.
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
    // fetch only rejects on a network-level failure — server down, DNS, CORS.
    // Distinguished from an HTTP error so the user is told to check the connection
    // rather than shown a misleading validation message.
    throw new ApiError('Could not reach the BloodLink server. Check your connection and try again.', 0);
  }

  if (response.status === 401 && auth && token) {
    clearSession();
  }

  // 204 and other empty bodies would break response.json(), so read as text first.
  const text = await response.text();
  const payload = text ? safeParse(text) : null;

  if (!response.ok) {
    throw new ApiError(
      payload?.message ?? `Request failed (${response.status}).`,
      response.status,
      payload?.fieldErrors,
    );
  }

  return payload;
}

/** Tolerates a non-JSON error body (e.g. a proxy's HTML 502) instead of throwing. */
function safeParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
