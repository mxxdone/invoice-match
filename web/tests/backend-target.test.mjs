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

const CASE = '11111111-2222-3333-4444-555555555555';

test('the live detail read endpoints resolve with a validated case id and number', () => {
  const base = 'http://localhost:8080';
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE], ''), `${base}/api/invoice-cases/${CASE}`);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'evidence-bundles'], ''), `${base}/api/invoice-cases/${CASE}/evidence-bundles`);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'evidence-bundles', '2'], ''), `${base}/api/invoice-cases/${CASE}/evidence-bundles/2`);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'match'], ''), `${base}/api/invoice-cases/${CASE}/match`);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'review-snapshots', 'latest'], ''), `${base}/api/invoice-cases/${CASE}/review-snapshots/latest`);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'review-snapshots', '3', 'freshness'], ''), `${base}/api/invoice-cases/${CASE}/review-snapshots/3/freshness`);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'review-decisions'], ''), `${base}/api/invoice-cases/${CASE}/review-decisions`);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'audit-entries'], '?limit=20'), `${base}/api/invoice-cases/${CASE}/audit-entries?limit=20`);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'handoff'], ''), `${base}/api/invoice-cases/${CASE}/handoff`);
});

test('a non-UUID id, a non-positive number and any write suffix never resolve', () => {
  const base = 'http://localhost:8080';
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', 'not-a-uuid'], ''), null);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'evidence-bundles', '0'], ''), null);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'review-snapshots', '0', 'freshness'], ''), null);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'review-snapshots', 'latest', 'freshness'], ''), null);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'review-snapshots'], ''), null);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'matches'], ''), null);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'approve'], ''), null);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'draft'], ''), null);
  assert.equal(resolveBackendTarget(base, ['api', 'invoice-cases', CASE, 'evidence-bundles', '2', 'extra'], ''), null);
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
