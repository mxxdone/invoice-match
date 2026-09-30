import test from 'node:test';
import assert from 'node:assert/strict';
import { fetchCurrentUser, fetchInvoiceCases } from '../src/app/api/client.ts';
import { ApiRequestError } from '../src/app/api/transport.ts';

const credentials = { username: 'approver', password: 'approver-pass' };

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

test('me and list call the fixed same-origin proxy with a per-request Basic header', async () => {
  const calls = [];
  await withStubbedFetch((url, init) => {
    calls.push({ url, init });
    return new Response(JSON.stringify(url.includes('/api/me')
      ? { username: 'approver', roles: ['APPROVER'] }
      : { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0, hasNext: false }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });
  }, async () => {
    const me = await fetchCurrentUser(credentials);
    const page = await fetchInvoiceCases(credentials, { status: 'REVIEW_PENDING', supplierId: 'SUP-1', submittedBy: null, submittedFrom: '2026-09-30T00:00:00.000000+09:00', submittedTo: '2026-09-30T23:59:59.999999+09:00', searchField: 'invoiceNumber', searchValue: 'inv 01', page: 2, size: 50 });
    assert.equal(me.username, 'approver');
    assert.equal(page.totalItems, 0);
  });

  assert.equal(calls[0].url, '/backend/api/me');
  assert.equal(calls[0].init.method, 'GET');
  assert.equal(calls[0].init.headers.authorization, `Basic ${Buffer.from('approver:approver-pass', 'utf8').toString('base64')}`);

  const listUrl = new URL(calls[1].url, 'http://localhost');
  assert.equal(listUrl.pathname, '/backend/api/invoice-cases');
  assert.equal(listUrl.searchParams.get('page'), '2');
  assert.equal(listUrl.searchParams.get('size'), '50');
  assert.equal(listUrl.searchParams.get('status'), 'REVIEW_PENDING');
  assert.equal(listUrl.searchParams.get('supplierId'), 'SUP-1');
  assert.equal(listUrl.searchParams.get('invoiceNumber'), 'inv 01');
  assert.equal(listUrl.searchParams.get('submittedFrom'), '2026-09-30T00:00:00.000000+09:00');
  assert.equal(listUrl.searchParams.get('submittedTo'), '2026-09-30T23:59:59.999999+09:00');
  assert.equal(listUrl.searchParams.has('submittedBy'), false);
});

test('401 and 403 responses surface typed errors the UI can branch on', async () => {
  await withStubbedFetch(() => new Response('', { status: 401 }), async () => {
    await assert.rejects(fetchCurrentUser(credentials), error => {
      assert.ok(error instanceof ApiRequestError);
      assert.equal(error.status, 401);
      assert.equal(error.code, 'UNAUTHENTICATED');
      return true;
    });
  });
  await withStubbedFetch(() => new Response(JSON.stringify({ code: 'FORBIDDEN', message: 'no' }), { status: 403 }), async () => {
    await assert.rejects(fetchInvoiceCases(credentials, { status: 'all', supplierId: null, submittedBy: null, searchField: 'invoiceNumber', searchValue: null, page: 0, size: 20 }), error => {
      assert.equal(error.status, 403);
      assert.equal(error.code, 'FORBIDDEN');
      return true;
    });
  });
});

test('a network failure becomes a typed error and an abort stays an abort', async () => {
  await withStubbedFetch(() => { throw new TypeError('failed to fetch'); }, async () => {
    await assert.rejects(fetchCurrentUser(credentials), error => {
      assert.equal(error.code, 'NETWORK_ERROR');
      return true;
    });
  });
  await withStubbedFetch(() => { const abort = new Error('aborted'); abort.name = 'AbortError'; throw abort; }, async () => {
    await assert.rejects(fetchCurrentUser(credentials), error => {
      assert.equal(error.name, 'AbortError');
      return true;
    });
  });
});

test('an abort while reading the error body stays an abort, not a 401', async () => {
  const stream = new ReadableStream({
    start(controller) {
      setTimeout(() => controller.error(Object.assign(new Error('aborted'), { name: 'AbortError' })), 5);
    },
  });
  await withStubbedFetch(() => new Response(stream, { status: 401 }), async () => {
    await assert.rejects(fetchCurrentUser(credentials), error => {
      assert.equal(error.name, 'AbortError');
      assert.equal(error instanceof ApiRequestError, false);
      return true;
    });
  });
});
