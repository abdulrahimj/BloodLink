import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  PASSWORD_CHANGED_MESSAGE,
  sessionEndFor,
  socketRefusedForPasswordChange,
} from './sessionEnd.js';

test('the coded 401 signs the user out and says the password changed', () => {
  const payload = { status: 401, code: 'PASSWORD_CHANGED', message: PASSWORD_CHANGED_MESSAGE };
  assert.equal(sessionEndFor(401, payload, true), 'password-changed');
  assert.equal(PASSWORD_CHANGED_MESSAGE, 'Your password was changed. Please sign in again.');
});

test('any other session-level 401 still discards the session, as before', () => {
  assert.equal(sessionEndFor(401, { message: 'Authentication is required to access this resource.' }, true), 'expired');
  assert.equal(sessionEndFor(401, null, true), 'expired');
});

test('a 401 about a typed field keeps the session', () => {
  const wrongCurrent = { message: 'Your current password is incorrect.', fieldErrors: { currentPassword: 'x' } };
  assert.equal(sessionEndFor(401, wrongCurrent, true), null);
});

test('nothing ends without a token sent, or on a status other than 401', () => {
  assert.equal(sessionEndFor(401, { code: 'PASSWORD_CHANGED' }, false), null);
  assert.equal(sessionEndFor(403, { code: 'PASSWORD_CHANGE_REQUIRED' }, true), null);
  assert.equal(sessionEndFor(200, null, true), null);
});

test('a socket refused for the password change is recognised by its message alone', () => {
  assert.equal(socketRefusedForPasswordChange({ headers: { message: PASSWORD_CHANGED_MESSAGE } }), true);
  assert.equal(socketRefusedForPasswordChange({ headers: { message: 'This account has been suspended. Sign in again.' } }), false);
  assert.equal(socketRefusedForPasswordChange(undefined), false);
});
