/* BloodLink — the Admin Console's "Manage" box (Business Rule 31): which of its three sections an administrator may
   use on an account, and the box's wording. The server enforces every one of these rules anyway. */

/** Shown on the signed-in administrator's own row instead of the password and status sections. */
export const OWN_ACCOUNT_NOTE = "This is your own account. Change your password in Profile Settings. "
  + "You cannot suspend your own account, and another administrator has to change your phone or facility.";

/** The Password section's explanation, above the Reset password button. */
export const RESET_PASSWORD_HINT = "Creates a temporary password. The user must choose a new one at next sign-in.";

/** Shown under the temporary password, which the box displays exactly once. */
export const TEMPORARY_PASSWORD_NOTE = "Give this to the user. It will not be shown again.";

/**
 * What the box offers for one account: details, password reset and status. Your own account gets none of the three;
 * the last-active-administrator rule is the server's alone (its 409 is shown if it ever applies).
 */
export function manageAccountOptions(account, { currentUserId } = {}) {
  const isSelf = account?.id === currentUserId;
  return {
    isSelf,
    canEditDetails: !isSelf,
    canResetPassword: !isSelf,
    showStatus: !isSelf,
    statusAction: account?.status === "SUSPENDED" ? "ACTIVE" : "SUSPENDED",
  };
}

/** "Reset the password for Aminata Sesay (+23276900005)? Their current password will stop working." */
export function resetConfirmationText(account) {
  return `Reset the password for ${account.fullName} (${account.phone})? Their current password will stop working.`;
}

/** The Suspend confirmation, naming who and what happens. */
export function suspendConfirmationText(account) {
  return `Suspend ${account.fullName} (${account.phone})? They will be signed out immediately and unable to sign in `
    + "until an administrator reactivates the account.";
}
