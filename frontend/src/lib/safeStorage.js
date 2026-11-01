/* BloodLink — the only code that touches localStorage and sessionStorage. A browser may refuse them, even on reading
   the property, so nothing here throws: what cannot be stored stays in memory until the page reloads. */

/** Wraps the store openStore returns; opened on every call, because the refusal can be the opening itself. */
export function createSafeStorage(openStore) {
  const memory = new Map();

  return {
    getItem(key) {
      if (memory.has(key)) return memory.get(key);
      try {
        return openStore().getItem(key) ?? null;
      } catch {
        return null;
      }
    },

    setItem(key, value) {
      try {
        openStore().setItem(key, String(value));
        memory.delete(key);
      } catch {
        memory.set(key, String(value));
      }
    },

    removeItem(key) {
      memory.delete(key);
      try {
        openStore().removeItem(key);
      } catch {
        // Nothing stored there if the store is refused.
      }
    },

    /** Every key held, stored or in memory, as a list so removing items while looping is safe. */
    keys() {
      let stored = [];
      try {
        const store = openStore();
        stored = Array.from({ length: store.length }, (_, index) => store.key(index));
      } catch {
        // Only what is in memory, then.
      }
      return [...new Set([...stored, ...memory.keys()])];
    },
  };
}

export const safeLocalStorage = createSafeStorage(() => window.localStorage);
export const safeSessionStorage = createSafeStorage(() => window.sessionStorage);
