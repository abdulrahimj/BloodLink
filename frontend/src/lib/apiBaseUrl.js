/* BloodLink — where the backend is, read once from VITE_API_BASE_URL at build time.
   The WebSocket uses the same address plus /ws, so there is no second setting to drift. */

export const DEFAULT_API_BASE_URL = 'http://localhost:8080';

/** The configured address without trailing slashes (so "/api" is never doubled); unset or blank is the local default. */
export function apiBaseUrlFrom(configured) {
  const trimmed = (configured ?? '').trim().replace(/\/+$/, '');
  return trimmed === '' ? DEFAULT_API_BASE_URL : trimmed;
}

/** Why a hosted build's address is unusable, or null: it must be set and https, or the site would call localhost. */
export function problemWithHostedApiUrl(configured) {
  const value = (configured ?? '').trim();
  if (value === '') {
    return 'VITE_API_BASE_URL is not set, so the site would call http://localhost:8080. Set it to the backend address, e.g. https://your-service.onrender.com';
  }
  if (!/^https:\/\/[^/\s]+/.test(value)) {
    return `VITE_API_BASE_URL must start with https:// (got "${value}"), or browsers will block the calls from an https page.`;
  }
  return null;
}
