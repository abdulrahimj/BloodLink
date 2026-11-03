/* BloodLink — keeping a dashboard's request list current: which notices concern requests, and a quiet
   background re-read that never overlaps itself and never lets an older answer overwrite a newer one. */

/** Every notification type about a blood request: raised, progress, outcome for the hospital, cancelled. */
const REQUEST_NOTICES = new Set(["NEW_REQUEST", "REQUEST_PROGRESS", "REQUEST_STATUS_CHANGE", "REQUEST_CANCELLED"]);

export function isRequestNotice(type) {
  return REQUEST_NOTICES.has(type);
}

/** The bank dashboard also shows stock, so a low-stock alert makes it out of date too. */
export function refreshesBankDashboard(type) {
  return isRequestNotice(type) || type === "LOW_STOCK_ALERT";
}

/**
 * load() fetches, apply(data) shows it. background() is skipped while a read is running; now() always starts a fresh one.
 * Only the newest read is applied; invalidate() discards any read in flight, and stop() ends it all when the page closes.
 */
export function createRefresher(load, apply) {
  let generation = 0;
  let running = null;
  let stopped = false;

  function start() {
    const mine = ++generation;
    const read = (async () => {
      try {
        const data = await load();
        if (!stopped && mine === generation) apply(data);
      } catch {
        // A failed quiet read changes nothing on screen; the next notice or the next 30-second check tries again.
      }
    })();
    running = read;
    return read.finally(() => { if (running === read) running = null; });
  }

  return {
    background() {
      if (stopped) return Promise.resolve();
      return running ?? start();
    },
    now() {
      return stopped ? Promise.resolve() : start();
    },
    invalidate() {
      generation += 1;
    },
    stop() {
      stopped = true;
      generation += 1;
    },
    isRunning() {
      return running !== null;
    },
  };
}
