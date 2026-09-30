import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiRequestError, buildBasicAuthHeader, toApiRequestError } from '../src/app/api/transport.ts';

test('basic auth header encodes the credentials for one request', () => {
  const header = buildBasicAuthHeader({ username: 'submitter', password: 'submitter-pass' });
  assert.equal(header, `Basic ${Buffer.from('submitter:submitter-pass', 'utf8').toString('base64')}`);
});

test('401 and 403 without a body map to the shared authentication codes', () => {
  const unauthorized = toApiRequestError(401, '');
  assert.ok(unauthorized instanceof ApiRequestError);
  assert.equal(unauthorized.status, 401);
  assert.equal(unauthorized.code, 'UNAUTHENTICATED');
  const forbidden = toApiRequestError(403, '');
  assert.equal(forbidden.status, 403);
  assert.equal(forbidden.code, 'FORBIDDEN');
});

test('a structured server error keeps its machine code', () => {
  const conflict = toApiRequestError(409, JSON.stringify({ code: 'STALE_CASE_VERSION', message: 'stale' }));
  assert.equal(conflict.status, 409);
  assert.equal(conflict.code, 'STALE_CASE_VERSION');
  assert.equal(conflict.message, 'stale');
});

test('an unparseable body still yields a typed error', () => {
  const server = toApiRequestError(500, '<html>boom</html>');
  assert.equal(server.status, 500);
  assert.equal(server.code, 'HTTP_500');
  assert.ok(server instanceof Error);
});
