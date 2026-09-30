import test from 'node:test';
import assert from 'node:assert/strict';
import { coreApiUrl, resolveBackendTarget, DEFAULT_CORE_API_URL, CORE_API_TIMEOUT_MS } from '../src/app/backend/target.ts';

test('only the allowlisted read endpoints resolve to a target', () => {
  assert.equal(resolveBackendTarget('http://localhost:8080/', ['api', 'me'], ''), 'http://localhost:8080/api/me');
  assert.equal(resolveBackendTarget('http://localhost:8080', ['api', 'invoice-cases'], '?page=0&size=20'), 'http://localhost:8080/api/invoice-cases?page=0&size=20');
});

test('write paths, subresources and unknown routes never resolve', () => {
  assert.equal(resolveBackendTarget('http://localhost:8080', ['api', 'invoice-cases', 'abc'], ''), null);
  assert.equal(resolveBackendTarget('http://localhost:8080', ['api', 'invoice-cases', 'abc', 'approve'], ''), null);
  assert.equal(resolveBackendTarget('http://localhost:8080', ['actuator', 'health'], ''), null);
  assert.equal(resolveBackendTarget('http://localhost:8080', ['api', 'me', 'x'], ''), null);
});

test('the base URL comes from the server env, defaulting to localhost', () => {
  assert.equal(coreApiUrl(undefined), DEFAULT_CORE_API_URL);
  assert.equal(coreApiUrl('  '), DEFAULT_CORE_API_URL);
  assert.equal(coreApiUrl('http://core-api:8080'), 'http://core-api:8080');
});

test('the upstream deadline is finite and positive', () => {
  assert.ok(Number.isFinite(CORE_API_TIMEOUT_MS));
  assert.ok(CORE_API_TIMEOUT_MS > 0);
});
