/* BloodLink — the "Set your own password" gate (Business Rule 27): whether a signed-in user must replace a
   temporary password first, and the form's checks. They mirror the server's; the server still decides. */

import { passwordProblem } from "./passwordRules.js";

/** True when the login said this account must set its own password before reaching anything else. */
export function mustChangePassword(user) {
  return Boolean(user?.mustChangePassword);
}

/**
 * Field errors for the form, keyed like the server's (currentPassword, newPassword) plus confirmPassword;
 * an empty object means it may be sent. The new password follows lib/passwordRules.js. Never echoes a password.
 */
export function passwordChangeErrors({ currentPassword, newPassword, confirmPassword }) {
  const errors = {};
  if (!currentPassword) errors.currentPassword = "Enter the temporary password you were given.";

  const problem = passwordProblem(newPassword);
  if (problem) {
    errors.newPassword = problem;
  } else if (currentPassword && newPassword === currentPassword) {
    errors.newPassword = "Choose a password different from your temporary one.";
  }

  if (confirmPassword !== newPassword) errors.confirmPassword = "The two new passwords do not match.";
  return errors;
}
