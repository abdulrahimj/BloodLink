import { test } from 'node:test';
import assert from 'node:assert/strict';

import { mustChangePassword, passwordChangeErrors } from './passwordChange.js';
import { MAX_PASSWORD_LENGTH } from './passwordRules.js';

const valid = { currentPassword: 'Temp7xKp2mQa', newPassword: 'My-own-pass7', confirmPassword: 'My-own-pass7' };

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

test('the new password follows the shared rule, 8 to 72 characters with every part', () => {
  assert.equal(passwordChangeErrors({ ...valid, newPassword: 'Sh0rt!', confirmPassword: 'Sh0rt!' }).newPassword,
    'Use at least 8 characters.');
  assert.equal(passwordChangeErrors({ ...valid, newPassword: 'my-own-passphrase', confirmPassword: 'my-own-passphrase' })
    .newPassword, 'Add a capital letter and a number.');
  const long = 'Aa1!' + 'x'.repeat(MAX_PASSWORD_LENGTH - 3);
  assert.match(passwordChangeErrors({ ...valid, newPassword: long, confirmPassword: long }).newPassword, /no more than 72/);
  const longest = 'Aa1!' + 'x'.repeat(MAX_PASSWORD_LENGTH - 4);
  assert.deepEqual(passwordChangeErrors({ ...valid, newPassword: longest, confirmPassword: longest }), {});
});

test('keeping the temporary password is refused', () => {
  const same = { currentPassword: 'Temp7xKp2mQ!', newPassword: 'Temp7xKp2mQ!', confirmPassword: 'Temp7xKp2mQ!' };
  assert.match(passwordChangeErrors(same).newPassword, /different from your temporary one/);
});

test('the two new passwords must match', () => {
  assert.match(passwordChangeErrors({ ...valid, confirmPassword: 'something-else' }).confirmPassword, /do not match/);
});
