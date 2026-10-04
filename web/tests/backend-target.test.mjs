import test from 'node:test';
import assert from 'node:assert/strict';
import {
  CORE_API_TIMEOUT_MS,
  DEFAULT_CORE_API_URL,
  REQUEST_BODY_LIMIT_BYTES,
  coreApiUrl,
  resolveBackendMutationTarget,
  resolveBackendTarget,
} from '../src/app/backend/target.ts';

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

test('original reads validate both UUIDs and keep upload mutations inaccessible', () => {
  const base = 'http://localhost:8080';
  const path = ['api', 'invoice-cases', CASE, 'documents'];
  assert.equal(resolveBackendTarget(base, path, '?page=1&size=20'), `${base}/api/invoice-cases/${CASE}/documents?page=1&size=20`);
  assert.equal(resolveBackendTarget(base, [...path, CASE, 'download-url'], '?disposition=inline'), `${base}/api/invoice-cases/${CASE}/documents/${CASE}/download-url?disposition=inline`);
  assert.equal(resolveBackendTarget(base, [...path, 'invalid', 'download-url'], ''), null);
  assert.equal(resolveBackendTarget(base, [...path, 'presign'], ''), null);
  assert.equal(resolveBackendMutationTarget(base, 'POST', [...path, 'complete']), null);
});

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

test('the request body limit matches the Core API 256 KiB cap', () => {
  assert.equal(REQUEST_BODY_LIMIT_BYTES, 262144);
});

test('a write resolves only with its exact method and path', () => {
  const base = 'http://localhost:8080';
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases']), `${base}/api/invoice-cases`);
  assert.equal(resolveBackendMutationTarget(base, 'PUT', ['api', 'invoice-cases', CASE, 'draft']), `${base}/api/invoice-cases/${CASE}/draft`);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases', CASE, 'submit']), `${base}/api/invoice-cases/${CASE}/submit`);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases', CASE, 'revisions']), `${base}/api/invoice-cases/${CASE}/revisions`);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases', CASE, 'match']), `${base}/api/invoice-cases/${CASE}/match`);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases', CASE, 'review-snapshots']), `${base}/api/invoice-cases/${CASE}/review-snapshots`);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases', CASE, 'mapping-decisions']), `${base}/api/invoice-cases/${CASE}/mapping-decisions`);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases', CASE, 'supplement-requests']), `${base}/api/invoice-cases/${CASE}/supplement-requests`);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases', CASE, 'reject']), `${base}/api/invoice-cases/${CASE}/reject`);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases', CASE, 'approve']), `${base}/api/invoice-cases/${CASE}/approve`);
});

test('a method or path mismatch never resolves a write', () => {
  const base = 'http://localhost:8080';
  assert.equal(resolveBackendMutationTarget(base, 'GET', ['api', 'invoice-cases', CASE, 'submit']), null);
  assert.equal(resolveBackendMutationTarget(base, 'PUT', ['api', 'invoice-cases', CASE, 'submit']), null);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases', CASE, 'draft']), null);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'me']), null);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases', 'not-a-uuid', 'submit']), null);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api', 'invoice-cases', CASE, 'approve', 'extra']), null);
});

test('analysis operations have exact human paths and no machine reachability', () => {
  const base = 'http://localhost:8080';
  assert.equal(resolveBackendTarget(base, ['api','analysis-runs'], '?page=1'), base+'/api/analysis-runs?page=1');
  assert.equal(resolveBackendTarget(base, ['api','analysis-runs',CASE,'failures'], ''), base+`/api/analysis-runs/${CASE}/failures`);
  assert.equal(resolveBackendMutationTarget(base, 'POST', ['api','analysis-runs',CASE,'retries']), base+`/api/analysis-runs/${CASE}/retries`);
  assert.equal(resolveBackendTarget(base, ['internal','analysis-runs',CASE,'claim'], ''), null);
  assert.equal(resolveBackendMutationTarget(base, 'PUT', ['api','analysis-runs',CASE,'retries']), null);
  assert.equal(resolveBackendTarget(base, ['api','analysis-runs','bad','failures'], ''), null);
});

test('advisory reads and reservation reach exact human endpoints while worker actions remain inaccessible', () => {
  const base = 'http://localhost:8080';
  const root = ['api', 'invoice-cases', CASE, 'proposals'];
  assert.equal(resolveBackendTarget(base, root, ''), `${base}/api/invoice-cases/${CASE}/proposals`);
  assert.equal(resolveBackendTarget(base, [...root, CASE], ''), `${base}/api/invoice-cases/${CASE}/proposals/${CASE}`);
  assert.equal(resolveBackendMutationTarget(base, 'POST', root), `${base}/api/invoice-cases/${CASE}/proposals`);
  assert.equal(resolveBackendMutationTarget(base, 'PUT', root), null);
  assert.equal(resolveBackendTarget(base, [...root, 'bad'], ''), null);
  assert.equal(resolveBackendMutationTarget(base, 'POST', [...root, CASE, 'complete']), null);
  assert.equal(resolveBackendTarget(base, ['internal', 'proposal-runs', CASE, 'claim'], ''), null);
});
