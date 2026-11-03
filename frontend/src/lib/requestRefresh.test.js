import assert from "node:assert/strict";
import test from "node:test";

import { createRefresher, isRequestNotice, refreshesBankDashboard } from "./requestRefresh.js";

/** A load() whose answers the test hands out by hand, in any order. */
function controlledLoad() {
  const calls = [];
  const load = () => new Promise((resolve, reject) => calls.push({ resolve, reject }));
  return { load, calls };
}

test("every request notice counts, and nothing else", () => {
  for (const type of ["NEW_REQUEST", "REQUEST_PROGRESS", "REQUEST_STATUS_CHANGE", "REQUEST_CANCELLED"]) {
    assert.equal(isRequestNotice(type), true, type);
  }
  for (const type of ["LOW_STOCK_ALERT", "DONATION_BOOKED", "BATCH_EXPIRED", "PASSWORD_RESET_BY_ADMIN", undefined]) {
    assert.equal(isRequestNotice(type), false, String(type));
  }
});

test("the bank dashboard also re-reads on a low-stock alert, which changes its stock table", () => {
  assert.equal(refreshesBankDashboard("LOW_STOCK_ALERT"), true);
  assert.equal(refreshesBankDashboard("NEW_REQUEST"), true);
  assert.equal(refreshesBankDashboard("DONATION_BOOKED"), false);
});

test("a background read is skipped while one is already running", async () => {
  const { load, calls } = controlledLoad();
  const applied = [];
  const refresher = createRefresher(load, (data) => applied.push(data));

  const first = refresher.background();
  refresher.background();
  refresher.background();
  assert.equal(calls.length, 1);
  assert.equal(refresher.isRunning(), true);

  calls[0].resolve("list");
  await first;
  assert.deepEqual(applied, ["list"]);
  assert.equal(refresher.isRunning(), false);

  refresher.background();
  assert.equal(calls.length, 2, "a new one starts once the last has finished");
});

test("now() starts a fresh read, and the older one in flight is never applied", async () => {
  const { load, calls } = controlledLoad();
  const applied = [];
  const refresher = createRefresher(load, (data) => applied.push(data));

  const quiet = refresher.background();
  const fresh = refresher.now();
  calls[1].resolve("after the action");
  calls[0].resolve("from before the action");
  await Promise.all([quiet, fresh]);

  assert.deepEqual(applied, ["after the action"]);
});

test("invalidate() discards a read already in flight", async () => {
  const { load, calls } = controlledLoad();
  const applied = [];
  const refresher = createRefresher(load, (data) => applied.push(data));

  const read = refresher.background();
  refresher.invalidate();
  calls[0].resolve("stale");
  await read;

  assert.deepEqual(applied, []);
});

test("a failed read changes nothing and the next one still runs", async () => {
  const { load, calls } = controlledLoad();
  const applied = [];
  const refresher = createRefresher(load, (data) => applied.push(data));

  const failing = refresher.background();
  calls[0].reject(new Error("offline"));
  await failing;
  assert.deepEqual(applied, []);

  const next = refresher.background();
  calls[1].resolve("back online");
  await next;
  assert.deepEqual(applied, ["back online"]);
});

test("after stop() nothing starts and a late answer is ignored", async () => {
  const { load, calls } = controlledLoad();
  const applied = [];
  const refresher = createRefresher(load, (data) => applied.push(data));

  const read = refresher.background();
  refresher.stop();
  calls[0].resolve("arrived after the page closed");
  await read;
  await refresher.background();
  await refresher.now();

  assert.deepEqual(applied, []);
  assert.equal(calls.length, 1);
});
