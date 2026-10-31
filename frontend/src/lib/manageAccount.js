/* BloodLink — the Admin Console's "Manage" box (Business Rule 31): which of its three sections an administrator may
   use on an account, and the box's wording. The server enforces every one of these rules anyway. */

/** The server's own refusal for suspending the last active administrator (AdminUserService), shown before asking. */
export const LAST_ACTIVE_ADMIN_MESSAGE = "This is the last active administrator account, so it cannot be suspended. "
  + "Create or reactivate another administrator first.";

/** Shown on the signed-in administrator's own row instead of the password and status sections. */
export const OWN_ACCOUNT_NOTE = "This is your own account. Change your password in Profile Settings. "
  + "You cannot suspend your own account, and another administrator has to change your phone or facility.";

/** The Password section's explanation, above the Reset password button. */
export const RESET_PASSWORD_HINT = "Creates a temporary password. The user must choose a new one at next sign-in.";

/** Shown under the temporary password, which the box displays exactly once. */
export const TEMPORARY_PASSWORD_NOTE = "Give this to the user. It will not be shown again.";

/** True when this account is the only ACTIVE administrator; activeAdminCount null means not known yet. */
export function isLastActiveAdmin(account, activeAdminCount) {
  return account?.role === "ADMIN" && account?.status === "ACTIVE" && activeAdminCount === 1;
}

/**
 * What the box offers for one account: details, password reset and status, each with any reason it is held back.
 * Your own account gets none of the three; the last active administrator cannot be suspended.
 */
export function manageAccountOptions(account, { currentUserId, activeAdminCount = null } = {}) {
  const isSelf = account?.id === currentUserId;
  const suspended = account?.status === "SUSPENDED";
  const lastAdmin = !suspended && isLastActiveAdmin(account, activeAdminCount);
  return {
    isSelf,
    canEditDetails: !isSelf,
    canResetPassword: !isSelf,
    showStatus: !isSelf,
    statusAction: suspended ? "ACTIVE" : "SUSPENDED",
    suspendBlockedReason: !suspended && lastAdmin ? LAST_ACTIVE_ADMIN_MESSAGE : null,
  };
}

/** True when the box needs the active-administrator count: only for an active ADMIN that is not you. */
export function needsActiveAdminCount(account, currentUserId) {
  return account?.role === "ADMIN" && account?.status === "ACTIVE" && account?.id !== currentUserId;
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
