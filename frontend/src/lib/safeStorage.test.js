import { test } from "node:test";
import assert from "node:assert/strict";
import { createSafeStorage } from "./safeStorage.js";

/** A working store, with the parts the wrapper calls. */
function workingStore() {
  const items = new Map();
  return {
    get length() { return items.size; },
    key: (index) => [...items.keys()][index] ?? null,
    getItem: (key) => (items.has(key) ? items.get(key) : null),
    setItem: (key, value) => { items.set(key, value); },
    removeItem: (key) => { items.delete(key); },
  };
}

const denied = () => { throw new Error("denied"); };
const refusesEverything = { get length() { return denied(); }, key: denied, getItem: denied, setItem: denied, removeItem: denied };

test("a working store is used, and nothing stays in memory", () => {
  const store = workingStore();
  const safe = createSafeStorage(() => store);

  safe.setItem("a", "1");
  assert.equal(store.getItem("a"), "1");
  assert.equal(safe.getItem("a"), "1");
  assert.deepEqual(safe.keys(), ["a"]);

  safe.removeItem("a");
  assert.equal(store.getItem("a"), null);
  assert.equal(safe.getItem("a"), null);
});

test("a missing key reads as null", () => {
  assert.equal(createSafeStorage(() => workingStore()).getItem("nothing"), null);
});

for (const [name, open] of [
  ["opening the store throws", denied],
  ["every call on the store throws", () => refusesEverything],
  ["there is no store at all", () => null],
]) {
  test(`when ${name}, nothing throws and values stay in memory`, () => {
    const safe = createSafeStorage(open);

    assert.equal(safe.getItem("a"), null);
    assert.deepEqual(safe.keys(), []);
    assert.doesNotThrow(() => safe.removeItem("a"));

    safe.setItem("a", "1");
    assert.equal(safe.getItem("a"), "1");
    assert.deepEqual(safe.keys(), ["a"]);

    safe.removeItem("a");
    assert.equal(safe.getItem("a"), null);
    assert.deepEqual(safe.keys(), []);
  });
}

test("a store that reads but refuses writes (a full quota) shows the newest value, not the old one", () => {
  const store = workingStore();
  store.setItem("a", "old");
  const safe = createSafeStorage(() => ({ ...store, length: 1, key: store.key, getItem: store.getItem, setItem: denied, removeItem: store.removeItem }));

  safe.setItem("a", "new");
  assert.equal(safe.getItem("a"), "new");
  assert.deepEqual(safe.keys(), ["a"]);

  safe.removeItem("a");
  assert.equal(safe.getItem("a"), null);
  assert.equal(store.getItem("a"), null);
});

test("a write that works again replaces the value held in memory", () => {
  const store = workingStore();
  let refusing = true;
  const safe = createSafeStorage(() => (refusing ? refusesEverything : store));

  safe.setItem("a", "1");
  refusing = false;
  safe.setItem("a", "2");

  assert.equal(store.getItem("a"), "2");
  assert.equal(safe.getItem("a"), "2");
});

test("two wrapped stores do not share their memory", () => {
  const first = createSafeStorage(denied);
  const second = createSafeStorage(denied);
  first.setItem("a", "1");
  assert.equal(second.getItem("a"), null);
});
