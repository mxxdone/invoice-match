import test from 'node:test';
import assert from 'node:assert/strict';
import {
  fetchAuditEntries,
  fetchCaseHandoff,
  fetchEvidenceBundle,
  fetchEvidenceBundles,
  fetchInvoiceCase,
  fetchLatestMatch,
  fetchLatestReviewSnapshot,
  fetchReviewDecisions,
  fetchReviewFreshness,
} from '../src/app/api/client.ts';
import { ApiRequestError } from '../src/app/api/transport.ts';

const credentials = { username: 'approver', password: 'approver-pass' };
const CASE = '11111111-2222-3333-4444-555555555555';

function withStubbedFetch(handler, run) {
  const original = globalThis.fetch;
  globalThis.fetch = async (input, init) => handler(String(input), init);
  try {
    return Promise.resolve(run()).finally(() => { globalThis.fetch = original; });
  } catch (error) {
    globalThis.fetch = original;
    throw error;
  }
}

function capture(body) {
  const calls = [];
  return {
    calls,
    handler: (url, init) => { calls.push({ url, init }); return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } }); },
  };
}

test('every live detail read goes to the fixed proxy path with a per-request Basic header', async () => {
  const caseDetail = { id: CASE, lines: [] };
  const c1 = capture(caseDetail);
  await withStubbedFetch(c1.handler, () => fetchInvoiceCase(credentials, CASE));
  assert.equal(c1.calls[0].url, `/backend/api/invoice-cases/${CASE}`);
  assert.equal(c1.calls[0].init.method, 'GET');
  assert.equal(c1.calls[0].init.cache, 'no-store');
  assert.equal(c1.calls[0].init.headers.authorization, `Basic ${Buffer.from('approver:approver-pass', 'utf8').toString('base64')}`);

  const c2 = capture([]);
  await withStubbedFetch(c2.handler, () => fetchEvidenceBundles(credentials, CASE));
  assert.equal(c2.calls[0].url, `/backend/api/invoice-cases/${CASE}/evidence-bundles`);

  const c3 = capture({});
  await withStubbedFetch(c3.handler, () => fetchEvidenceBundle(credentials, CASE, 2));
  assert.equal(c3.calls[0].url, `/backend/api/invoice-cases/${CASE}/evidence-bundles/2`);

  const c4 = capture({});
  await withStubbedFetch(c4.handler, () => fetchLatestMatch(credentials, CASE));
  assert.equal(c4.calls[0].url, `/backend/api/invoice-cases/${CASE}/match`);

  const c5 = capture({});
  await withStubbedFetch(c5.handler, () => fetchLatestReviewSnapshot(credentials, CASE));
  assert.equal(c5.calls[0].url, `/backend/api/invoice-cases/${CASE}/review-snapshots/latest`);

  const c6 = capture({});
  await withStubbedFetch(c6.handler, () => fetchReviewFreshness(credentials, CASE, 3));
  assert.equal(c6.calls[0].url, `/backend/api/invoice-cases/${CASE}/review-snapshots/3/freshness`);

  const c7 = capture([]);
  await withStubbedFetch(c7.handler, () => fetchReviewDecisions(credentials, CASE));
  assert.equal(c7.calls[0].url, `/backend/api/invoice-cases/${CASE}/review-decisions`);

  const c8 = capture({});
  await withStubbedFetch(c8.handler, () => fetchCaseHandoff(credentials, CASE));
  assert.equal(c8.calls[0].url, `/backend/api/invoice-cases/${CASE}/handoff`);
});

test('the audit cursor and limit are forwarded, and an absent cursor is omitted', async () => {
  const first = capture({ entries: [], nextCursor: null });
  await withStubbedFetch(first.handler, () => fetchAuditEntries(credentials, CASE, null, 20));
  assert.equal(first.calls[0].url, `/backend/api/invoice-cases/${CASE}/audit-entries?limit=20`);
  assert.equal(first.calls[0].url.includes('cursor'), false);

  const more = capture({ entries: [], nextCursor: null });
  await withStubbedFetch(more.handler, () => fetchAuditEntries(credentials, CASE, 'opaque/cursor==', 20));
  const url = new URL(more.calls[0].url, 'http://localhost');
  assert.equal(url.pathname, `/backend/api/invoice-cases/${CASE}/audit-entries`);
  assert.equal(url.searchParams.get('limit'), '20');
  assert.equal(url.searchParams.get('cursor'), 'opaque/cursor==');
});

test('a 404 section read surfaces the typed code without inventing a result', async () => {
  await withStubbedFetch(() => new Response(JSON.stringify({ code: 'MATCH_RESULT_NOT_FOUND', message: 'none' }), { status: 404 }), async () => {
    await assert.rejects(fetchLatestMatch(credentials, CASE), (error) => {
      assert.ok(error instanceof ApiRequestError);
      assert.equal(error.status, 404);
      assert.equal(error.code, 'MATCH_RESULT_NOT_FOUND');
      return true;
    });
  });
});
