import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  MAX_NOTE_LENGTH, REQUEST_SENT_MESSAGE, badgeLabel, noteProblem, phoneFieldProblem, requestActions,
  resolutionLabel, telHref, waitingBannerText,
} from './passwordRequests.js';

const ME = 'admin-me';
const request = (overrides = {}) => ({
  id: 'r1', status: 'WAITING', account: { id: 'u1', fullName: 'Aminata Sesay', phone: '+23276900005' }, ...overrides,
});

test('the form always says the same thing afterwards', () => {
  assert.equal(REQUEST_SENT_MESSAGE,
    'If this number is registered, an administrator will contact you. Keep your phone nearby.');
});

test('a tel: link keeps the digits and a leading plus, whatever the spacing', () => {
  assert.equal(telHref('+23276900005'), 'tel:+23276900005');
  assert.equal(telHref(' +232 76 000 111 '), 'tel:+23276000111');
  assert.equal(telHref('076-000-111'), 'tel:076000111');
  assert.equal(telHref(''), null);
  assert.equal(telHref(null), null);
});

test('the phone field only needs something typed, as at sign-in', () => {
  assert.equal(phoneFieldProblem('  '), 'Enter your phone number.');
  assert.equal(phoneFieldProblem('076900005'), null);
});

test('the banner and the badge count waiting requests, and vanish at zero', () => {
  assert.equal(waitingBannerText(1), '1 password reset request waiting.');
  assert.equal(waitingBannerText(3), '3 password reset requests waiting.');
  assert.equal(waitingBannerText(0), null);
  assert.equal(badgeLabel(0), null);
  assert.equal(badgeLabel(7), '7');
  assert.equal(badgeLabel(140), '99+');
});

test('done requests read "Password reset" or "Closed"', () => {
  assert.equal(resolutionLabel('RESET'), 'Password reset');
  assert.equal(resolutionLabel('CLOSED'), 'Closed');
});

test('the note is optional and capped at 200 characters', () => {
  assert.equal(noteProblem(''), null);
  assert.equal(noteProblem('x'.repeat(MAX_NOTE_LENGTH)), null);
  assert.equal(noteProblem('x'.repeat(MAX_NOTE_LENGTH + 1)), 'Keep the note to 200 characters or fewer.');
});

test('a waiting request offers Call, Reset password and Mark done', () => {
  assert.deepEqual(requestActions(request(), ME), { canCall: true, canReset: true, canMarkDone: true, ownRequest: false });
});

test('your own waiting request hides Reset password and says why', () => {
  const own = requestActions(request({ account: { id: ME, phone: '+23276900001' } }), ME);
  assert.equal(own.canReset, false);
  assert.equal(own.ownRequest, true);
  assert.equal(own.canCall, true);
});

test('a done request offers nothing', () => {
  assert.deepEqual(requestActions(request({ status: 'DONE' }), ME),
    { canCall: false, canReset: false, canMarkDone: false, ownRequest: false });
});
