/* BloodLink — the "Set your own password" gate (Business Rule 27): whether a signed-in user must replace a
   temporary password first, and the form's checks. They mirror the server's; the server still decides. */

/** The app's password rule, as registration and ChangePasswordPayload apply it (PasswordPolicy on the server). */
export const MIN_PASSWORD_LENGTH = 8;
export const MAX_PASSWORD_LENGTH = 72;

/** True when the login said this account must set its own password before reaching anything else. */
export function mustChangePassword(user) {
  return Boolean(user?.mustChangePassword);
}

/**
 * Field errors for the form, keyed like the server's (currentPassword, newPassword) plus confirmPassword;
 * an empty object means it may be sent. Never echoes a password back.
 */
export function passwordChangeErrors({ currentPassword, newPassword, confirmPassword }) {
  const errors = {};
  if (!currentPassword) errors.currentPassword = "Enter the temporary password you were given.";

  if (newPassword.length < MIN_PASSWORD_LENGTH) {
    errors.newPassword = `Your new password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
  } else if (newPassword.length > MAX_PASSWORD_LENGTH) {
    errors.newPassword = `Your new password must be at most ${MAX_PASSWORD_LENGTH} characters.`;
  } else if (currentPassword && newPassword === currentPassword) {
    errors.newPassword = "Choose a password different from your temporary one.";
  }

  if (confirmPassword !== newPassword) errors.confirmPassword = "The two new passwords do not match.";
  return errors;
}
