import { afterEach, test } from "node:test";
import assert from "node:assert/strict";
import {
  clearSession,
  getToken,
  getUser,
  isSignedIn,
  replaceSessionToken,
  saveSession,
  updateSessionUser,
} from "./session.js";

const AUTH = { token: "token-1", user: { id: "u1", fullName: "Aminata Kamara", role: "DONOR" } };

function workingStore() {
  const items = new Map();
  return {
    get length() { return items.size; },
    key: (index) => [...items.keys()][index] ?? null,
    getItem: (key) => (items.has(key) ? items.get(key) : null),
    setItem: (key, value) => { items.set(key, String(value)); },
    removeItem: (key) => { items.delete(key); },
  };
}

const denied = () => { throw new Error("storage denied"); };
const refusingStore = { get length() { return denied(); }, key: denied, getItem: denied, setItem: denied, removeItem: denied };

/** Puts a fake browser in place; the session opens its stores on each call, so this takes effect at once. */
function browserWith({ local, session }) {
  globalThis.window = {
    get localStorage() { return local(); },
    get sessionStorage() { return session(); },
  };
}

afterEach(() => {
  clearSession();
  delete globalThis.window;
});

test("with working storage, remember me keeps the session in localStorage", () => {
  const local = workingStore();
  const session = workingStore();
  browserWith({ local: () => local, session: () => session });

  saveSession(AUTH, true);

  assert.equal(local.getItem("bloodlink.token"), "token-1");
  assert.equal(session.getItem("bloodlink.token"), null);
  assert.equal(getToken(), "token-1");
});

test("with working storage, no remember me keeps it in sessionStorage and clears the other", () => {
  const local = workingStore();
  const session = workingStore();
  browserWith({ local: () => local, session: () => session });

  saveSession(AUTH, true);
  saveSession({ ...AUTH, token: "token-2" }, false);

  assert.equal(local.getItem("bloodlink.token"), null);
  assert.equal(session.getItem("bloodlink.token"), "token-2");
  assert.equal(getToken(), "token-2");
});

for (const [name, blocked] of [
  ["reading the storage property throws", { local: denied, session: denied }],
  ["every read and write throws", { local: () => refusingStore, session: () => refusingStore }],
]) {
  test(`when ${name}, the app starts signed out and sign-in works in memory`, () => {
    browserWith(blocked);

    assert.equal(getToken(), null);
    assert.equal(isSignedIn(), false);
    assert.equal(getUser(), null);
    assert.equal(updateSessionUser({ fullName: "X" }), null);
    assert.doesNotThrow(() => replaceSessionToken("nothing"));
    assert.doesNotThrow(() => clearSession());

    for (const remember of [true, false]) {
      saveSession(AUTH, remember);
      assert.equal(getToken(), "token-1");
      assert.equal(isSignedIn(), true);
      assert.deepEqual(getUser(), AUTH.user);

      assert.equal(updateSessionUser({ fullName: "Aminata K." }).fullName, "Aminata K.");
      assert.equal(getUser().fullName, "Aminata K.");

      replaceSessionToken("token-2");
      assert.equal(getToken(), "token-2");

      clearSession();
      assert.equal(getToken(), null);
      assert.equal(getUser(), null);
    }
  });
}

test("with only localStorage blocked, remember me still signs in", () => {
  const session = workingStore();
  browserWith({ local: denied, session: () => session });

  saveSession(AUTH, true);

  assert.equal(getToken(), "token-1");
  assert.deepEqual(getUser(), AUTH.user);
});

test("a damaged stored user reads as none", () => {
  const local = workingStore();
  browserWith({ local: () => local, session: () => workingStore() });
  local.setItem("bloodlink.user", "{not json");

  assert.equal(getUser(), null);
});
