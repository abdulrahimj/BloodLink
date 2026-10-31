/* BloodLink — the password rule for every password chosen, the twin of the server's PasswordPolicy word for word:
   8–72 characters with a capital, a small letter, a number and a special character. The server still decides. */

export const MIN_PASSWORD_LENGTH = 8;
export const MAX_PASSWORD_LENGTH = 72;

// Anything but a letter, a number or whitespace, in any script; spelled out exactly as PasswordPolicy.SPECIAL.
const SPECIAL = /[^\p{L}\p{N}\p{Z}\t\n\v\f\r]/u;

/** The five parts, in the order the checklist shows them; `phrase` is how the error message names a missing one. */
export const PASSWORD_REQUIREMENTS = [
  { key: "length", label: `At least ${MIN_PASSWORD_LENGTH} characters`, test: (p) => p.length >= MIN_PASSWORD_LENGTH },
  { key: "uppercase", label: "A capital letter (A–Z)", phrase: "a capital letter", test: (p) => /[A-Z]/.test(p) },
  { key: "lowercase", label: "A small letter (a–z)", phrase: "a small letter", test: (p) => /[a-z]/.test(p) },
  { key: "digit", label: "A number (0–9)", phrase: "a number", test: (p) => /[0-9]/.test(p) },
  { key: "special", label: "A special character (! @ # ?)", phrase: "a special character", test: (p) => SPECIAL.test(p) },
];

/** The checklist as the user types: one entry per part, `met` true once it is satisfied. */
export function passwordChecklist(password) {
  const value = password ?? "";
  return PASSWORD_REQUIREMENTS.map(({ key, label, test }) => ({ key, label, met: test(value) }));
}

/** True when the password may be sent: every part met and no longer than the maximum. */
export function meetsPasswordRule(password) {
  return passwordProblem(password) === null;
}

/** The same sentence the server's 400 gives ("Add a capital letter and a special character."), or null. */
export function passwordProblem(password) {
  const value = password ?? "";
  const missing = PASSWORD_REQUIREMENTS.filter((r) => !r.test(value));
  const parts = [];
  if (missing.some((r) => r.key === "length")) parts.push(`use at least ${MIN_PASSWORD_LENGTH} characters`);
  else if (value.length > MAX_PASSWORD_LENGTH) parts.push(`use no more than ${MAX_PASSWORD_LENGTH} characters`);
  const toAdd = missing.filter((r) => r.key !== "length").map((r) => r.phrase);
  if (toAdd.length > 0) parts.push(`add ${joinWithAnd(toAdd)}`);
  if (parts.length === 0) return null;
  const sentence = `${parts.join(", and ")}.`;
  return sentence.charAt(0).toUpperCase() + sentence.slice(1);
}

/** "a", "a and b", "a, b and c". */
function joinWithAnd(items) {
  if (items.length === 1) return items[0];
  return `${items.slice(0, -1).join(", ")} and ${items[items.length - 1]}`;
}
