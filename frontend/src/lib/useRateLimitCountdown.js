/* BloodLink — the live countdown after a 429 (Business Rule 34), shared by Sign in, "Forgot password?" and Register.
   The sums and storage are in rateLimitCountdown.js. */

import { useEffect, useState } from "react";
import {
  TRY_AGAIN_MESSAGE,
  cancelWait,
  clearEndTime,
  loadWait,
  secondsRemaining,
  spokenWaitMessage,
  startWait,
} from "./rateLimitCountdown.js";

// Often enough that a late tick never skips a second; the state only changes once a second.
const TICK_MS = 250;

/**
 * Follows the wait stored under storageKey. waiting blocks submit, secondsLeft drives the banner, finished shows
 * "You can try again now." until dismiss(), and announcement is the once-only text for the live region.
 */
export function useRateLimitCountdown(storageKey) {
  const [stored, setStored] = useState(() => loadWait(storageKey, Date.now()));
  const [, setShownSeconds] = useState(0);
  const [announcement, setAnnouncement] = useState("");

  // A different phone number has its own wait, or none: swapped during render so the button never flickers.
  let current = stored;
  if (stored.key !== storageKey) {
    current = loadWait(storageKey, Date.now());
    setStored(current);
  }
  const { endsAt, finished } = current;

  useEffect(() => {
    if (endsAt === null) return undefined;
    function tick() {
      const seconds = secondsRemaining(endsAt, Date.now());
      setShownSeconds(seconds);
      if (seconds === 0) {
        clearEndTime(storageKey);
        setStored({ key: storageKey, endsAt: null, finished: true });
      }
    }
    tick();
    const timer = setInterval(tick, TICK_MS);
    return () => clearInterval(timer);
  }, [endsAt, storageKey]);

  // Set once per start or end, never per tick, and in an effect so a restored wait is announced too.
  useEffect(() => {
    if (endsAt !== null) setAnnouncement(spokenWaitMessage(secondsRemaining(endsAt, Date.now())));
    else setAnnouncement(finished ? TRY_AGAIN_MESSAGE : "");
  }, [endsAt, finished]);

  /** Starts the wait a refused attempt carries; false means no usable time, so the form shows the server's message. */
  function startFrom(error) {
    const wait = startWait(storageKey, error, Date.now());
    if (wait === null) return false;
    setStored(wait);
    return true;
  }

  /** Drops the wait in this tab; if the lock still stands, the next attempt's 429 starts it again. */
  function cancel() {
    setStored(cancelWait(storageKey));
  }

  /** Clears "You can try again now." when the next attempt begins. */
  function dismiss() {
    setStored((state) => (state.finished ? { ...state, finished: false } : state));
  }

  return {
    waiting: endsAt !== null,
    secondsLeft: endsAt === null ? 0 : secondsRemaining(endsAt, Date.now()),
    finished,
    announcement,
    startFrom,
    cancel,
    dismiss,
  };
}
