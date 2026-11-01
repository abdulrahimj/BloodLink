/* BloodLink — the waiting time shown after a 429 (Business Rule 34): wording, arithmetic and the end times kept in
   sessionStorage. The server's Retry-After sets the time; the server still decides every attempt. */

import { safeSessionStorage } from "./safeStorage.js";

export const SIGN_IN_FORM = "signin";
export const FORGOT_PASSWORD_FORM = "forgot";
export const REGISTER_FORM = "register";

export const TRY_AGAIN_MESSAGE = "You can try again now.";
export const ADMIN_RESET_LINK = "Did an administrator reset your password? Try now.";

const KEY_PREFIX = "bloodlink.rateLimit.";
const SIGN_IN_PREFIX = `${KEY_PREFIX}${SIGN_IN_FORM}.`;

/** Seconds as M:SS: 900 is "15:00", 59 is "0:59", 0 is "0:00". */
export function formatCountdown(seconds) {
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(seconds % 60).padStart(2, "0")}`;
}

/** The sentence on screen, changing every second. */
export function waitMessage(seconds) {
  return `Too many attempts. Please wait ${formatCountdown(seconds)} before trying again.`;
}

/** What a screen reader says once at the start: whole minutes, rounded up, or seconds under a minute. */
export function spokenWaitMessage(seconds) {
  if (seconds < 60) return `Too many attempts. Please wait ${seconds} second${seconds === 1 ? "" : "s"} before trying again.`;
  const minutes = Math.ceil(seconds / 60);
  return `Too many attempts. Please wait ${minutes} minute${minutes === 1 ? "" : "s"} before trying again.`;
}

/** The Retry-After header as whole seconds above zero; null when missing or anything else. */
export function parseRetryAfter(value) {
  const text = String(value ?? "").trim();
  if (!/^\d+$/.test(text)) return null;
  const seconds = Number(text);
  return seconds > 0 && Number.isSafeInteger(seconds) ? seconds : null;
}

/** When a refused attempt's wait ends (ms); null when the form should show the server's own message instead. */
export function endTimeFor(error, now) {
  if (error?.status !== 429 || !error.retryAfterSeconds) return null;
  return now + error.retryAfterSeconds * 1000;
}

/** Whole seconds left, rounded up so 0:00 means the wait is really over. */
export function secondsRemaining(endsAt, now) {
  return Math.max(0, Math.ceil((endsAt - now) / 1000));
}

/** The same number however it was typed, as the server's PhoneNumber.normalize reads it: 076900005 is +23276900005. */
export function phoneIdentity(phone) {
  const compact = String(phone ?? "").replace(/\s/g, "");
  return compact.startsWith("0") ? `+232${compact.slice(1)}` : compact;
}

/** The storage key for a form; Sign in has one per phone number, and none while the box is empty. */
export function countdownKey(form, phone) {
  if (form !== SIGN_IN_FORM) return `${KEY_PREFIX}${form}`;
  const identity = phoneIdentity(phone);
  return identity ? `${SIGN_IN_PREFIX}${identity}` : null;
}

/** The stored end time if it is still ahead; a finished or unreadable one is removed. */
export function readEndTime(key, now, storage = safeSessionStorage) {
  if (!key) return null;
  const raw = storage.getItem(key);
  if (raw === null) return null;
  const endsAt = Number(raw);
  if (Number.isFinite(endsAt) && endsAt > now) return endsAt;
  storage.removeItem(key);
  return null;
}

export function saveEndTime(key, endsAt, storage = safeSessionStorage) {
  storage.setItem(key, String(endsAt));
}

export function clearEndTime(key, storage = safeSessionStorage) {
  storage.removeItem(key);
}

/** The wait stored under a key: endsAt is null when there is none, and finished shows "You can try again now." */
export function loadWait(key, now, storage = safeSessionStorage) {
  return { key, endsAt: readEndTime(key, now, storage), finished: false };
}

/** The wait a refused attempt carries, stored; null when it carries none, so the form shows the server's message. */
export function startWait(key, error, now, storage = safeSessionStorage) {
  const endsAt = key ? endTimeFor(error, now) : null;
  if (endsAt === null) return null;
  saveEndTime(key, endsAt, storage);
  return { key, endsAt, finished: false };
}

/** "Try now": forgets the wait in this tab only. The server's lock is untouched, so it still decides the next attempt. */
export function cancelWait(key, storage = safeSessionStorage) {
  clearEndTime(key, storage);
  return { key, endsAt: null, finished: false };
}

function signInKeys(storage) {
  return storage.keys().filter((key) => key.startsWith(SIGN_IN_PREFIX));
}

/** The number whose lock ends last, to refill the box after a refresh; null when none is running. */
export function lockedSignInPhone(now, storage = safeSessionStorage) {
  let latest = null;
  for (const key of signInKeys(storage)) {
    const endsAt = readEndTime(key, now, storage);
    if (endsAt !== null && (latest === null || endsAt > latest.endsAt)) {
      latest = { phone: key.slice(SIGN_IN_PREFIX.length), endsAt };
    }
  }
  return latest?.phone ?? null;
}

/** After a successful sign-in, so the next person at this tab is not shown someone else's number. */
export function clearSignInCountdowns(storage = safeSessionStorage) {
  signInKeys(storage).forEach((key) => storage.removeItem(key));
}
