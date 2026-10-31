import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  LAST_ACTIVE_ADMIN_MESSAGE, isLastActiveAdmin, manageAccountOptions, needsActiveAdminCount,
  resetConfirmationText, suspendConfirmationText,
} from './manageAccount.js';

const ME = 'admin-me';
const account = (overrides) => ({ id: 'u1', fullName: 'Aminata Sesay', phone: '+23276900005', role: 'DONOR',
  status: 'ACTIVE', ...overrides });

test('another active account offers every section, with Suspend as the status action', () => {
  assert.deepEqual(manageAccountOptions(account(), { currentUserId: ME }), {
    isSelf: false, canEditDetails: true, canResetPassword: true, showStatus: true,
    statusAction: 'SUSPENDED', suspendBlockedReason: null,
  });
});

test('your own account offers no details edit, no reset and no status change', () => {
  const options = manageAccountOptions(account({ id: ME, role: 'ADMIN' }), { currentUserId: ME, activeAdminCount: 3 });
  assert.equal(options.isSelf, true);
  assert.equal(options.canEditDetails, false);
  assert.equal(options.canResetPassword, false);
  assert.equal(options.showStatus, false);
});

test('a suspended account can be reset and reactivated, never "suspended" again', () => {
  const options = manageAccountOptions(account({ status: 'SUSPENDED' }), { currentUserId: ME });
  assert.equal(options.canResetPassword, true);
  assert.equal(options.statusAction, 'ACTIVE');
  assert.equal(options.suspendBlockedReason, null);
});

test('the last active administrator cannot be suspended, with the server\'s reason', () => {
  const lastAdmin = account({ role: 'ADMIN' });
  assert.equal(manageAccountOptions(lastAdmin, { currentUserId: ME, activeAdminCount: 1 }).suspendBlockedReason,
    LAST_ACTIVE_ADMIN_MESSAGE);
  assert.equal(manageAccountOptions(lastAdmin, { currentUserId: ME, activeAdminCount: 2 }).suspendBlockedReason, null);
  // Count not loaded yet: the server still refuses, so the button is not held back on a guess.
  assert.equal(manageAccountOptions(lastAdmin, { currentUserId: ME }).suspendBlockedReason, null);
  // The last-admin rule never applies to other roles, or to a suspended administrator.
  assert.equal(isLastActiveAdmin(account({ role: 'BLOOD_BANK_STAFF' }), 1), false);
  assert.equal(isLastActiveAdmin(account({ role: 'ADMIN', status: 'SUSPENDED' }), 1), false);
});

test('the active-administrator count is fetched only for another active administrator', () => {
  assert.equal(needsActiveAdminCount(account({ role: 'ADMIN' }), ME), true);
  assert.equal(needsActiveAdminCount(account({ role: 'ADMIN', id: ME }), ME), false);
  assert.equal(needsActiveAdminCount(account({ role: 'ADMIN', status: 'SUSPENDED' }), ME), false);
  assert.equal(needsActiveAdminCount(account({ role: 'HOSPITAL_STAFF' }), ME), false);
});

test('the confirmations name the account and say what happens', () => {
  assert.equal(resetConfirmationText(account()),
    'Reset the password for Aminata Sesay (+23276900005)? Their current password will stop working.');
  assert.match(suspendConfirmationText(account()), /^Suspend Aminata Sesay \(\+23276900005\)\? They will be signed out/);
});
