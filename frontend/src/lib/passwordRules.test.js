import { test } from 'node:test';
import assert from 'node:assert/strict';

import { meetsPasswordRule, passwordChecklist, passwordProblem } from './passwordRules.js';

const metKeys = (password) => passwordChecklist(password).filter((item) => item.met).map((item) => item.key);

test('the checklist lists the five parts in order, none ticked for an empty box', () => {
  const list = passwordChecklist('');
  assert.deepEqual(list.map((item) => item.key), ['length', 'uppercase', 'lowercase', 'digit', 'special']);
  assert.ok(list.every((item) => !item.met && item.label));
  assert.deepEqual(metKeys(undefined), []);
});

test('the checklist ticks each part as it is typed', () => {
  assert.deepEqual(metKeys('p'), ['lowercase']);
  assert.deepEqual(metKeys('Pa'), ['uppercase', 'lowercase']);
  assert.deepEqual(metKeys('Pa1'), ['uppercase', 'lowercase', 'digit']);
  assert.deepEqual(metKeys('Pa1!'), ['uppercase', 'lowercase', 'digit', 'special']);
  assert.deepEqual(metKeys('Pa1!word'), ['length', 'uppercase', 'lowercase', 'digit', 'special']);
});

test('a password meeting every part is accepted', () => {
  assert.equal(passwordProblem('Password123!'), null);
  assert.equal(meetsPasswordRule('Password123!'), true);
});

test('each missing part is named, with the server\'s wording', () => {
  assert.equal(passwordProblem('Pa12!'), 'Use at least 8 characters.');
  assert.equal(passwordProblem('password123!'), 'Add a capital letter.');
  assert.equal(passwordProblem('PASSWORD123!'), 'Add a small letter.');
  assert.equal(passwordProblem('Password!!!!'), 'Add a number.');
  assert.equal(passwordProblem('Password1234'), 'Add a special character.');
  assert.equal(passwordProblem('password123'), 'Add a capital letter and a special character.');
  assert.equal(passwordProblem('password'), 'Add a capital letter, a number and a special character.');
  assert.equal(passwordProblem('abc'),
    'Use at least 8 characters, and add a capital letter, a number and a special character.');
  assert.equal(meetsPasswordRule('password'), false);
});

test('8 and 72 characters are accepted, 7 and 73 refused', () => {
  assert.equal(passwordProblem('Abcde1!x'), null);
  assert.equal(passwordProblem('Abcd1!x'), 'Use at least 8 characters.');
  const longest = 'Aa1!' + 'x'.repeat(68);
  assert.equal(longest.length, 72);
  assert.equal(passwordProblem(longest), null);
  assert.equal(passwordProblem(longest + 'x'), 'Use no more than 72 characters.');
  assert.equal(meetsPasswordRule(longest + 'x'), false);
  // Every part of the checklist is ticked for a 73-character password; only the maximum refuses it.
  assert.equal(passwordChecklist(longest + 'x').every((item) => item.met), true);
});

test('whitespace is not a special character', () => {
  assert.equal(passwordProblem('Password 123'), 'Add a special character.');
  assert.equal(passwordProblem('Password\t123'), 'Add a special character.');
  assert.equal(passwordProblem('Password 123'), 'Add a special character.');
  assert.equal(passwordProblem('Pass word1!'), null);
});

test('letters and numbers of other scripts are not special, symbols are', () => {
  assert.equal(passwordProblem('Passwordé123'), 'Add a special character.');
  assert.equal(passwordProblem('Password٣abc'), 'Add a number and a special character.');
  assert.equal(passwordProblem('Password123£'), null);
  assert.equal(passwordProblem('Password123\u{1F600}'), null);
  assert.equal(passwordProblem('Password123_'), null);
});
