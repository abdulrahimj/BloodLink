import { test } from "node:test";
import assert from "node:assert/strict";
import { createSafeStorage } from "./safeStorage.js";
import {
  ADMIN_RESET_LINK,
  FORGOT_PASSWORD_FORM,
  REGISTER_FORM,
  SIGN_IN_FORM,
  cancelWait,
  clearEndTime,
  clearSignInCountdowns,
  countdownKey,
  endTimeFor,
  formatCountdown,
  loadWait,
  lockedSignInPhone,
  parseRetryAfter,
  phoneIdentity,
  readEndTime,
  saveEndTime,
  secondsRemaining,
  spokenWaitMessage,
  startWait,
  waitMessage,
} from "./rateLimitCountdown.js";

const NOW = 1_800_000_000_000;

/** A working sessionStorage behind the shared wrapper, as the app has it. */
function memoryStorage() {
  const items = new Map();
  const store = {
    get length() { return items.size; },
    key: (index) => [...items.keys()][index] ?? null,
    getItem: (key) => (items.has(key) ? items.get(key) : null),
    setItem: (key, value) => { items.set(key, String(value)); },
    removeItem: (key) => { items.delete(key); },
  };
  return createSafeStorage(() => store);
}

/** A browser that refuses storage, as private modes and full quotas do. */
function brokenStorage() {
  return createSafeStorage(() => { throw new Error("denied"); });
}

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

test("with storage refused, a wait holds for this page and is lost on a refresh", () => {
  const key = countdownKey(REGISTER_FORM);
  const page = brokenStorage();

  saveEndTime(key, NOW + 1_000, page);
  assert.equal(readEndTime(key, NOW, page), NOW + 1_000);
  clearEndTime(key, page);
  assert.equal(readEndTime(key, NOW, page), null);

  saveEndTime(key, NOW + 1_000, page);
  const afterRefresh = brokenStorage();
  assert.equal(readEndTime(key, NOW, afterRefresh), null);
});

test("with storage refused, Sign in's number and clearing work for this page", () => {
  const page = brokenStorage();
  saveEndTime(signInKey("+23276900005"), NOW + 1_000, page);

  assert.equal(lockedSignInPhone(NOW, page), "+23276900005");
  clearSignInCountdowns(page);
  assert.equal(lockedSignInPhone(NOW, page), null);
});

test("the link's wording is the agreed sentence", () => {
  assert.equal(ADMIN_RESET_LINK, "Did an administrator reset your password? Try now.");
});

test("a 429 with a time starts a stored wait; anything else starts none", () => {
  const storage = memoryStorage();
  const key = signInKey("+23276900005");

  assert.deepEqual(startWait(key, { status: 429, retryAfterSeconds: 900 }, NOW, storage), { key, endsAt: NOW + 900_000, finished: false });
  assert.equal(storage.getItem(key), String(NOW + 900_000));

  const other = signInKey("+23276900006");
  assert.equal(startWait(other, { status: 429, retryAfterSeconds: null }, NOW, storage), null);
  assert.equal(startWait(other, { status: 401, retryAfterSeconds: 900 }, NOW, storage), null);
  assert.equal(startWait(null, { status: 429, retryAfterSeconds: 900 }, NOW, storage), null);
  assert.equal(storage.getItem(other), null);
});

test("Try now clears that number's stored wait, so the button is enabled and a refresh stays clear", () => {
  const storage = memoryStorage();
  const locked = signInKey("+23276900005");
  const running = startWait(locked, { status: 429, retryAfterSeconds: 900 }, NOW, storage);
  assert.notEqual(running.endsAt, null);

  const cancelled = cancelWait(locked, storage);

  assert.deepEqual(cancelled, { key: locked, endsAt: null, finished: false });
  assert.equal(storage.getItem(locked), null);
  assert.equal(loadWait(locked, NOW, storage).endsAt, null);
  assert.equal(lockedSignInPhone(NOW, storage), null);
});

test("Try now leaves another number's wait running", () => {
  const storage = memoryStorage();
  startWait(signInKey("+23276900005"), { status: 429, retryAfterSeconds: 900 }, NOW, storage);
  startWait(signInKey("+23276900006"), { status: 429, retryAfterSeconds: 100 }, NOW, storage);

  cancelWait(signInKey("076900005"), storage);

  assert.equal(loadWait(signInKey("+23276900005"), NOW, storage).endsAt, null);
  assert.equal(loadWait(signInKey("+23276900006"), NOW, storage).endsAt, NOW + 100_000);
});

test("after Try now, a 429 restarts the countdown from the server's Retry-After", () => {
  const storage = memoryStorage();
  const key = signInKey("+23276900005");
  startWait(key, { status: 429, retryAfterSeconds: 900 }, NOW, storage);
  cancelWait(key, storage);

  // 100 seconds later the lock still stands, and the server says how much of it is left.
  const later = NOW + 100_000;
  const restarted = startWait(key, { status: 429, retryAfterSeconds: 800 }, later, storage);

  assert.equal(secondsRemaining(restarted.endsAt, later), 800);
  assert.equal(restarted.endsAt, NOW + 900_000);
  assert.equal(loadWait(key, later, storage).endsAt, NOW + 900_000);
  assert.equal(lockedSignInPhone(later, storage), "+23276900005");
});

test("after Try now, a sign-in that succeeds leaves nothing stored", () => {
  const storage = memoryStorage();
  const key = signInKey("+23276900005");
  startWait(key, { status: 429, retryAfterSeconds: 900 }, NOW, storage);
  cancelWait(key, storage);
  clearSignInCountdowns(storage);

  assert.deepEqual(storage.keys(), []);
});
