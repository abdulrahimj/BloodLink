import { test } from 'node:test';
import assert from 'node:assert/strict';

import { MAX_PASSWORD_LENGTH, mustChangePassword, passwordChangeErrors } from './passwordChange.js';

const valid = { currentPassword: 'Temp7xKp2mQa', newPassword: 'my-own-passphrase', confirmPassword: 'my-own-passphrase' };

test('only a user the login flagged is sent to the gate', () => {
  assert.equal(mustChangePassword({ role: 'ADMIN', mustChangePassword: true }), true);
  assert.equal(mustChangePassword({ role: 'DONOR', mustChangePassword: false }), false);
  // A session stored before Business Rule 27 has no flag at all, and is never blocked.
  assert.equal(mustChangePassword({ role: 'HOSPITAL_STAFF' }), false);
  assert.equal(mustChangePassword(null), false);
});

test('a complete, different, matching password has no errors', () => {
  assert.deepEqual(passwordChangeErrors(valid), {});
});

test('the temporary password is required', () => {
  assert.match(passwordChangeErrors({ ...valid, currentPassword: '' }).currentPassword, /temporary password/);
});

test('the new password follows the app rule of 8 to 72 characters', () => {
  assert.match(passwordChangeErrors({ ...valid, newPassword: 'short', confirmPassword: 'short' }).newPassword, /at least 8/);
  const long = 'x'.repeat(MAX_PASSWORD_LENGTH + 1);
  assert.match(passwordChangeErrors({ ...valid, newPassword: long, confirmPassword: long }).newPassword, /at most 72/);
  const longest = 'x'.repeat(MAX_PASSWORD_LENGTH);
  assert.deepEqual(passwordChangeErrors({ ...valid, newPassword: longest, confirmPassword: longest }), {});
});

test('keeping the temporary password is refused', () => {
  const same = { currentPassword: 'Temp7xKp2mQa', newPassword: 'Temp7xKp2mQa', confirmPassword: 'Temp7xKp2mQa' };
  assert.match(passwordChangeErrors(same).newPassword, /different from your temporary one/);
});

test('the two new passwords must match', () => {
  assert.match(passwordChangeErrors({ ...valid, confirmPassword: 'something-else' }).confirmPassword, /do not match/);
});
