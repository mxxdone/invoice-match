import test from 'node:test';
import assert from 'node:assert/strict';
import { Generation } from '../src/app/api/generation.ts';

test('only the latest generation token is current', () => {
  const generation = new Generation();
  assert.equal(generation.current(), 0);
  const first = generation.next();
  assert.equal(generation.isCurrent(first), true);
  const second = generation.next();
  assert.equal(generation.isCurrent(first), false);
  assert.equal(generation.isCurrent(second), true);
});

test('a logout invalidates an in-flight login token', () => {
  const sessions = new Generation();
  const loginToken = sessions.next();
  // logout while the login request is still pending
  sessions.next();
  assert.equal(sessions.isCurrent(loginToken), false);
});

test('a newer filter invalidates a stale list token so its 401 cannot sign out a new session', () => {
  const requests = new Generation();
  const staleToken = requests.next();
  // filters changed and a new request started with a new session
  requests.next();
  assert.equal(requests.isCurrent(staleToken), false);
});
