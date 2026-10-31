import { test } from 'node:test';
import assert from 'node:assert/strict';

import { manageAccountOptions, resetConfirmationText, suspendConfirmationText } from './manageAccount.js';

const ME = 'admin-me';
const account = (overrides) => ({ id: 'u1', fullName: 'Aminata Sesay', phone: '+23276900005', role: 'DONOR',
  status: 'ACTIVE', ...overrides });

test('another active account offers every section, with Suspend as the status action', () => {
  assert.deepEqual(manageAccountOptions(account(), { currentUserId: ME }), {
    isSelf: false, canEditDetails: true, canResetPassword: true, showStatus: true, statusAction: 'SUSPENDED',
  });
});

test('another active administrator can always be offered Suspend; the server keeps the last-admin rule', () => {
  assert.equal(manageAccountOptions(account({ role: 'ADMIN' }), { currentUserId: ME }).statusAction, 'SUSPENDED');
});

test('your own account offers no details edit, no reset and no status change', () => {
  const options = manageAccountOptions(account({ id: ME, role: 'ADMIN' }), { currentUserId: ME });
  assert.equal(options.isSelf, true);
  assert.equal(options.canEditDetails, false);
  assert.equal(options.canResetPassword, false);
  assert.equal(options.showStatus, false);
});

test('a suspended account can be reset and reactivated, never "suspended" again', () => {
  const options = manageAccountOptions(account({ status: 'SUSPENDED' }), { currentUserId: ME });
  assert.equal(options.canResetPassword, true);
  assert.equal(options.statusAction, 'ACTIVE');
});

test('the confirmations name the account and say what happens', () => {
  assert.equal(resetConfirmationText(account()),
    'Reset the password for Aminata Sesay (+23276900005)? Their current password will stop working.');
  assert.match(suspendConfirmationText(account()), /^Suspend Aminata Sesay \(\+23276900005\)\? They will be signed out/);
});
