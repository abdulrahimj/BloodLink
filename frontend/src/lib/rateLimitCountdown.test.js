import { test } from "node:test";
import assert from "node:assert/strict";
import {
  FORGOT_PASSWORD_FORM,
  REGISTER_FORM,
  SIGN_IN_FORM,
  clearEndTime,
  clearSignInCountdowns,
  countdownKey,
  endTimeFor,
  formatCountdown,
  lockedSignInPhone,
  parseRetryAfter,
  phoneIdentity,
  readEndTime,
  saveEndTime,
  secondsRemaining,
  spokenWaitMessage,
  waitMessage,
} from "./rateLimitCountdown.js";

const NOW = 1_800_000_000_000;

/** The parts of sessionStorage the countdown uses. */
function memoryStorage() {
  const items = new Map();
  return {
    get length() { return items.size; },
    key: (index) => [...items.keys()][index] ?? null,
    getItem: (key) => (items.has(key) ? items.get(key) : null),
    setItem: (key, value) => { items.set(key, String(value)); },
    removeItem: (key) => { items.delete(key); },
  };
}

/** A browser that refuses storage, as private modes and full quotas do. */
const brokenStorage = {
  get length() { throw new Error("denied"); },
  key() { throw new Error("denied"); },
  getItem() { throw new Error("denied"); },
  setItem() { throw new Error("denied"); },
  removeItem() { throw new Error("denied"); },
};

const signInKey = (phone) => countdownKey(SIGN_IN_FORM, phone);

test("seconds are shown as minutes and two-digit seconds", () => {
  assert.equal(formatCountdown(900), "15:00");
  assert.equal(formatCountdown(872), "14:32");
  assert.equal(formatCountdown(60), "1:00");
  assert.equal(formatCountdown(59), "0:59");
  assert.equal(formatCountdown(5), "0:05");
  assert.equal(formatCountdown(0), "0:00");
  assert.equal(formatCountdown(3600), "60:00");
});

test("the banner sentence carries the ticking time", () => {
  assert.equal(waitMessage(872), "Too many attempts. Please wait 14:32 before trying again.");
});

test("the spoken sentence uses words, not a clock reading", () => {
  assert.equal(spokenWaitMessage(900), "Too many attempts. Please wait 15 minutes before trying again.");
  assert.equal(spokenWaitMessage(61), "Too many attempts. Please wait 2 minutes before trying again.");
  assert.equal(spokenWaitMessage(60), "Too many attempts. Please wait 1 minute before trying again.");
  assert.equal(spokenWaitMessage(1), "Too many attempts. Please wait 1 second before trying again.");
});

test("the time left comes from the stored end, rounded up, and never goes below zero", () => {
  assert.equal(secondsRemaining(NOW + 900_000, NOW), 900);
  assert.equal(secondsRemaining(NOW + 900_000, NOW + 28_000), 872);
  assert.equal(secondsRemaining(NOW + 1_000, NOW + 1), 1);
  assert.equal(secondsRemaining(NOW, NOW), 0);
  assert.equal(secondsRemaining(NOW, NOW + 5_000), 0);
});

test("a 429 with Retry-After gives an end time from the header", () => {
  assert.equal(endTimeFor({ status: 429, retryAfterSeconds: 900 }, NOW), NOW + 900_000);
});

test("a missing or unusable Retry-After falls back to the server's fixed message", () => {
  assert.equal(parseRetryAfter(null), null);
  assert.equal(parseRetryAfter(undefined), null);
  assert.equal(parseRetryAfter(""), null);
  assert.equal(parseRetryAfter("soon"), null);
  assert.equal(parseRetryAfter("-5"), null);
  assert.equal(parseRetryAfter("1.5"), null);
  assert.equal(parseRetryAfter("0"), null);
  assert.equal(parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"), null);
  assert.equal(parseRetryAfter("99999999999999999999"), null);
  assert.equal(endTimeFor({ status: 429, retryAfterSeconds: null }, NOW), null);
  assert.equal(endTimeFor({ status: 429 }, NOW), null);
});

test("the header is read as whole seconds", () => {
  assert.equal(parseRetryAfter("900"), 900);
  assert.equal(parseRetryAfter(" 60 "), 60);
});

test("only a 429 starts a countdown", () => {
  assert.equal(endTimeFor({ status: 401, retryAfterSeconds: 900 }, NOW), null);
  assert.equal(endTimeFor(new Error("offline"), NOW), null);
});

test("each form has its own key, and Sign in has one per number", () => {
  assert.notEqual(countdownKey(REGISTER_FORM), countdownKey(FORGOT_PASSWORD_FORM));
  assert.notEqual(signInKey("+23276900005"), signInKey("+23276900006"));
  assert.equal(signInKey(""), null);
  assert.equal(signInKey("   "), null);
});

test("the same number typed another way is the same number", () => {
  assert.equal(phoneIdentity("076900005"), "+23276900005");
  assert.equal(phoneIdentity("+232 76 900 005"), "+23276900005");
  assert.equal(signInKey("076 900 005"), signInKey("+23276900005"));
});

test("a stored end time is read back, so a refresh continues the same countdown", () => {
  const storage = memoryStorage();
  const key = countdownKey(REGISTER_FORM);
  saveEndTime(key, NOW + 900_000, storage);

  const later = NOW + 100_000;
  const endsAt = readEndTime(key, later, storage);
  assert.equal(endsAt, NOW + 900_000);
  assert.equal(secondsRemaining(endsAt, later), 800);
});

test("Sign in stops being blocked when the box holds a different number, and resumes on the locked one", () => {
  const storage = memoryStorage();
  saveEndTime(signInKey("+23276900005"), NOW + 900_000, storage);

  assert.notEqual(readEndTime(signInKey("+23276900005"), NOW, storage), null);
  assert.equal(readEndTime(signInKey("+23276900006"), NOW, storage), null);
  assert.equal(readEndTime(signInKey(""), NOW, storage), null);
  assert.notEqual(readEndTime(signInKey("076 900 005"), NOW, storage), null);
});

test("a finished or garbled end time is removed", () => {
  const storage = memoryStorage();
  const key = countdownKey(FORGOT_PASSWORD_FORM);

  saveEndTime(key, NOW, storage);
  assert.equal(readEndTime(key, NOW, storage), null);
  assert.equal(storage.getItem(key), null);

  storage.setItem(key, "not a time");
  assert.equal(readEndTime(key, NOW, storage), null);
  assert.equal(storage.getItem(key), null);
});

test("clearing removes the stored end time", () => {
  const storage = memoryStorage();
  const key = countdownKey(REGISTER_FORM);
  saveEndTime(key, NOW + 1_000, storage);
  clearEndTime(key, storage);
  assert.equal(storage.getItem(key), null);
});

test("after a refresh the box is refilled with the number whose lock ends last", () => {
  const storage = memoryStorage();
  saveEndTime(signInKey("+23276900005"), NOW + 100_000, storage);
  saveEndTime(signInKey("+23276900006"), NOW + 900_000, storage);
  saveEndTime(signInKey("+23276900007"), NOW - 1, storage);
  saveEndTime(countdownKey(REGISTER_FORM), NOW + 999_000, storage);

  assert.equal(lockedSignInPhone(NOW, storage), "+23276900006");
  assert.equal(lockedSignInPhone(NOW + 950_000, memoryStorage()), null);
});

test("a successful sign-in clears every Sign in wait and leaves other forms alone", () => {
  const storage = memoryStorage();
  const register = countdownKey(REGISTER_FORM);
  saveEndTime(signInKey("+23276900005"), NOW + 100_000, storage);
  saveEndTime(signInKey("+23276900006"), NOW + 100_000, storage);
  saveEndTime(register, NOW + 100_000, storage);

  clearSignInCountdowns(storage);

  assert.equal(lockedSignInPhone(NOW, storage), null);
  assert.notEqual(storage.getItem(register), null);
});

test("storage that refuses every call never breaks the form", () => {
  const key = countdownKey(REGISTER_FORM);
  assert.doesNotThrow(() => saveEndTime(key, NOW + 1_000, brokenStorage));
  assert.equal(readEndTime(key, NOW, brokenStorage), null);
  assert.doesNotThrow(() => clearEndTime(key, brokenStorage));
  assert.equal(lockedSignInPhone(NOW, brokenStorage), null);
  assert.doesNotThrow(() => clearSignInCountdowns(brokenStorage));
});

test("no storage at all is the same as nothing stored", () => {
  const key = countdownKey(REGISTER_FORM);
  assert.equal(readEndTime(key, NOW, null), null);
  assert.equal(lockedSignInPhone(NOW, null), null);
});
