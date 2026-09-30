import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

// Real React DOM mount/effect/cleanup regression for the live detail hook. It is
// driven through a live root with a routed fetch stub so an id switch, a late
// 401, an unmount, a missing section and an ownership denial are all exercised
// the way the browser does them.
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
for (const key of Object.getOwnPropertyNames(dom.window)) {
  if (!(key in globalThis)) {
    try {
      globalThis[key] = dom.window[key];
    } catch {
      // Some jsdom window properties are accessor-only; ignore.
    }
  }
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const React = await import('react');
const { createRoot } = await import('react-dom/client');
const { useCaseDetail } = await import('../src/app/cases/[id]/use-case-detail.ts');

const { createElement, act } = React;

let latest = null;
function Harness(props) {
  latest = useCaseDetail(props);
  return null;
}

const credentials = { username: 'approver', password: 'p' };

function jsonResponse(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

function detailOf(id, overrides = {}) {
  return {
    id, supplierId: 'SUP-1', purchaseOrderId: 'PO-1', invoiceNumber: `INV-${id}`, submittedBy: 'submitter',
    status: 'REVIEW_PENDING', version: 2, currentRevision: null, lines: [], ...overrides,
  };
}

function idFrom(url) {
  return url.split('/').pop();
}

function defaultRespond(url) {
  if (/\/invoice-cases\/[^/]+$/.test(url)) return jsonResponse(200, detailOf(idFrom(url)));
  if (url.endsWith('/evidence-bundles')) return jsonResponse(200, []);
  if (url.endsWith('/handoff')) return jsonResponse(200, { invoiceCaseId: 'c', caseStatus: 'REVIEW_PENDING', caseVersion: 1, payment: null });
  if (url.endsWith('/match')) return jsonResponse(404, { code: 'MATCH_RESULT_NOT_FOUND', message: 'none' });
  if (url.endsWith('/review-snapshots/latest')) return jsonResponse(404, { code: 'REVIEW_SNAPSHOT_NOT_FOUND', message: 'none' });
  if (url.endsWith('/review-decisions')) return jsonResponse(200, []);
  if (url.includes('/audit-entries')) return jsonResponse(200, { entries: [], nextCursor: null });
  return jsonResponse(200, {});
}

function setup(respond = defaultRespond) {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = (url, init) => {
    const value = String(url);
    calls.push(value);
    if (init?.signal?.aborted) return Promise.reject(Object.assign(new Error('aborted'), { name: 'AbortError' }));
    return Promise.resolve().then(() => respond(value, init));
  };
  const unauthorized = [];
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  const render = (options) => act(async () => { root.render(createElement(Harness, options)); });
  const flush = () => act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
  return {
    calls,
    unauthorized,
    render,
    flush,
    async unmount() { await act(async () => { root.unmount(); }); },
    restore() { globalThis.fetch = originalFetch; container.remove(); },
  };
}

const base = { credentials, sessionId: 1, caseId: 'A', canReadReview: false, reloadToken: 0 };

test('a current 401 on the primary read triggers the unauthorized handler once', async () => {
  const t = setup((url) => url.endsWith('/invoice-cases/A') ? jsonResponse(401, { code: 'UNAUTHENTICATED' }) : defaultRespond(url));
  try {
    await t.render({ ...base, onUnauthorized: () => t.unauthorized.push('a') });
    await t.flush();
    assert.deepEqual(t.unauthorized, ['a']);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a missing case is notFound and a denied case is forbidden', async () => {
  for (const [status, expected] of [[404, 'notFound'], [403, 'forbidden']]) {
    const t = setup((url) => url.endsWith('/invoice-cases/A') ? jsonResponse(status, { code: 'X' }) : defaultRespond(url));
    try {
      await t.render({ ...base, onUnauthorized: () => t.unauthorized.push('a') });
      await t.flush();
      assert.equal(latest.load.status, expected);
      assert.deepEqual(t.unauthorized, []);
    } finally {
      await t.unmount();
      t.restore();
    }
  }
});

test('a submitter never requests the reviewer-only sections', async () => {
  const t = setup();
  try {
    await t.render({ ...base, onUnauthorized: () => t.unauthorized.push('a') });
    await t.flush();
    await t.flush();
    assert.equal(latest.load.status, 'ready');
    assert.equal(t.calls.some((url) => url.endsWith('/match')), false);
    assert.equal(t.calls.some((url) => url.includes('/review-')), false);
    assert.equal(t.calls.some((url) => url.includes('/audit-entries')), false);
    assert.equal(latest.load.data.match.status, 'forbidden');
    assert.equal(latest.load.data.audit.status, 'forbidden');
    assert.equal(latest.load.data.canReadReview, false);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a reviewer sees missing sections as empty, not as a failed page', async () => {
  const t = setup();
  try {
    await t.render({ ...base, canReadReview: true, onUnauthorized: () => t.unauthorized.push('a') });
    await t.flush();
    await t.flush();
    assert.equal(latest.load.status, 'ready');
    assert.equal(latest.load.data.match.status, 'empty');
    assert.equal(latest.load.data.snapshot.status, 'empty');
    assert.equal(latest.load.data.decisions.status, 'empty');
    assert.equal(latest.load.data.audit.status, 'empty');
    assert.ok(t.calls.some((url) => url.endsWith('/match')));
    assert.ok(t.calls.some((url) => url.includes('/audit-entries')));
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('an old id success can never overwrite the newly selected id', async () => {
  let resolveA;
  const pendingA = new Promise((resolve) => { resolveA = resolve; });
  const t = setup((url) => url.endsWith('/invoice-cases/A') ? pendingA : defaultRespond(url));
  try {
    await t.render({ ...base, onUnauthorized: () => t.unauthorized.push('a') });
    await t.render({ ...base, caseId: 'B', onUnauthorized: () => t.unauthorized.push('a') });
    await t.flush();
    await t.flush();
    assert.equal(latest.load.status, 'ready');
    assert.equal(latest.load.data.detail.id, 'B');
    resolveA(jsonResponse(200, detailOf('A')));
    await t.flush();
    await t.flush();
    assert.equal(latest.load.status, 'ready');
    assert.equal(latest.load.data.detail.id, 'B');
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a late 401 that resolves after unmount never signs the user out', async () => {
  let resolveA;
  const pendingA = new Promise((resolve) => { resolveA = resolve; });
  const t = setup((url) => url.endsWith('/invoice-cases/A') ? pendingA : defaultRespond(url));
  try {
    await t.render({ ...base, onUnauthorized: () => t.unauthorized.push('a') });
    await t.unmount();
    resolveA(jsonResponse(401, { code: 'UNAUTHENTICATED' }));
    await t.flush();
    assert.deepEqual(t.unauthorized, []);
  } finally {
    t.restore();
  }
});

test('a late 401 from a replaced id never signs the user out', async () => {
  let resolveA;
  const pendingA = new Promise((resolve) => { resolveA = resolve; });
  const t = setup((url) => url.endsWith('/invoice-cases/A') ? pendingA : defaultRespond(url));
  try {
    await t.render({ ...base, onUnauthorized: () => t.unauthorized.push('a') });
    await t.render({ ...base, caseId: 'B', onUnauthorized: () => t.unauthorized.push('a') });
    await t.flush();
    await t.flush();
    assert.equal(latest.load.data.detail.id, 'B');
    resolveA(jsonResponse(401, { code: 'UNAUTHENTICATED' }));
    await t.flush();
    await t.flush();
    assert.deepEqual(t.unauthorized, []);
    assert.equal(latest.load.status, 'ready');
    assert.equal(latest.load.data.detail.id, 'B');
  } finally {
    await t.unmount();
    t.restore();
  }
});
