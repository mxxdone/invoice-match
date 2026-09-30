import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
for (const key of Object.getOwnPropertyNames(dom.window)) {
  if (!(key in globalThis)) {
    try { globalThis[key] = dom.window[key]; } catch { /* accessor-only */ }
  }
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const React = await import('react');
const { createRoot } = await import('react-dom/client');
const { useCaseComposer } = await import('../src/app/cases/use-case-composer.ts');

const { createElement, act } = React;

let latest = null;
function Harness(props) {
  latest = useCaseComposer(props);
  return null;
}

const credentials = { username: 'submitter', password: 'p' };
const header = { supplierId: 'SUP-1', purchaseOrderId: 'PO-1', invoiceNumber: 'INV-1' };
const lines = [{ lineNumber: 1, rawItemName: 'Paper', quantity: 2, unitPrice: 100, confirmedItemId: null }];

function jsonResponse(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

function setup(responder) {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = (url, init) => {
    const parsed = { url: String(url), method: init?.method, body: init?.body ? JSON.parse(init.body) : null };
    calls.push(parsed);
    return Promise.resolve(responder(String(url), init, parsed));
  };
  const unauthorized = [];
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  return {
    calls,
    unauthorized,
    async render(options = {}) {
      await act(async () => {
        root.render(createElement(Harness, { credentials, sessionId: 1, onUnauthorized: () => unauthorized.push('x'), ...options }));
      });
    },
    async run(fn) { await act(async () => { await fn(); }); },
    async unmount() { await act(async () => { root.unmount(); }); },
    restore() { globalThis.fetch = originalFetch; container.remove(); },
  };
}

const created = { id: 'case-1', supplierId: 'SUP-1', purchaseOrderId: 'PO-1', invoiceNumber: 'INV-1', submittedBy: 'submitter', status: 'DRAFT', version: 0, currentRevision: { id: 'r', revisionNumber: 1, status: 'OPEN' }, lines: [] };

test('create → draft → submit sends three intents with the version chain', async () => {
  const t = setup((url, init) => {
    if (init.method === 'POST' && url.endsWith('/api/invoice-cases')) return jsonResponse(201, created);
    if (init.method === 'PUT' && url.endsWith('/draft')) return jsonResponse(200, { ...created, version: 1 });
    if (url.endsWith('/submit')) return jsonResponse(200, { caseId: 'case-1', status: 'REVIEW_PENDING', version: 2, evidenceBundle: { version: 1, payloadHash: 'h', submittedAt: 'x' } });
    return jsonResponse(500, { code: 'X', message: 'unexpected' });
  });
  try {
    await t.render();
    await t.run(() => latest.createOrReuse(header));
    assert.equal(latest.caseId, 'case-1');
    await t.run(() => latest.saveDraft(header, lines));
    assert.equal(latest.caseVersion, 1);
    await t.run(() => latest.submit());
    assert.equal(latest.submittedBundleVersion, 1);
    assert.equal(latest.status, 'submitted');

    assert.equal(t.calls.length, 3);
    assert.equal(t.calls[0].method, 'POST');
    assert.equal(t.calls[0].url, '/backend/api/invoice-cases');
    assert.equal(t.calls[1].method, 'PUT');
    assert.equal(t.calls[1].body.expectedCaseVersion, 0);
    assert.equal(t.calls[2].method, 'POST');
    assert.equal(t.calls[2].body.expectedCaseVersion, 1);
    const ids = new Set(t.calls.map((call) => call.body.requestId));
    assert.equal(ids.size, 3, 'each separate intent gets its own request id');
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a create that succeeded is reused after a draft failure and the uncertain draft reuses its request id', async () => {
  let draftAttempts = 0;
  const t = setup((url, init) => {
    if (init.method === 'POST' && url.endsWith('/api/invoice-cases')) return jsonResponse(201, created);
    if (init.method === 'PUT' && url.endsWith('/draft')) {
      draftAttempts += 1;
      return draftAttempts === 1
        ? jsonResponse(503, { code: 'CORE_API_TIMEOUT', message: 'unknown' })
        : jsonResponse(200, { ...created, version: 1 });
    }
    return jsonResponse(500, { code: 'X', message: 'unexpected' });
  });
  try {
    await t.render();
    await t.run(() => latest.saveDraft(header, lines));
    assert.equal(latest.failure.kind, 'uncertain');
    assert.equal(latest.hasPending, true);

    await t.run(() => latest.saveDraft(header, lines));
    assert.equal(draftAttempts, 2);
    const creates = t.calls.filter((call) => call.method === 'POST' && call.url === '/backend/api/invoice-cases');
    assert.equal(creates.length, 1, 'the succeeded create is reused, never repeated');
    const draftIds = t.calls.filter((call) => call.url.endsWith('/draft')).map((call) => call.body.requestId);
    assert.equal(draftIds.length, 2);
    assert.equal(draftIds[0], draftIds[1], 'the uncertain retry keeps the exact request id');
    assert.equal(latest.status, 'draft-saved');
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('an uncertain create is not auto-resent and a manual retry reuses the same request id', async () => {
  let attempts = 0;
  const t = setup((url, init) => {
    if (init.method === 'POST' && url.endsWith('/api/invoice-cases')) {
      attempts += 1;
      if (attempts === 1) throw new TypeError('failed to fetch');
      return jsonResponse(201, created);
    }
    return jsonResponse(500, { code: 'X', message: 'unexpected' });
  });
  try {
    await t.render();
    await t.run(() => latest.createOrReuse(header));
    assert.equal(latest.failure.kind, 'uncertain');
    assert.equal(latest.hasPending, true);
    assert.equal(latest.caseId, null);
    const firstId = t.calls[0].body.requestId;

    await t.run(() => latest.createOrReuse(header));
    assert.equal(t.calls.length, 2);
    assert.equal(t.calls[1].body.requestId, firstId, 'the manual retry reuses the exact request id and payload');
    assert.equal(latest.caseId, 'case-1');
    assert.equal(latest.hasPending, false);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a 409 conflict clears the intent, blocks auto-retry and a refresh adopts the latest version', async () => {
  let submitAttempts = 0;
  const t = setup((url, init) => {
    if (init.method === 'POST' && url.endsWith('/api/invoice-cases')) return jsonResponse(201, created);
    if (init.method === 'PUT' && url.endsWith('/draft')) return jsonResponse(200, { ...created, version: 1 });
    if (url.endsWith('/submit')) {
      submitAttempts += 1;
      return submitAttempts === 1
        ? jsonResponse(409, { code: 'CASE_VERSION_CONFLICT', message: 'stale version' })
        : jsonResponse(200, { caseId: 'case-1', status: 'REVIEW_PENDING', version: 6, evidenceBundle: { version: 1, payloadHash: 'h', submittedAt: 'x' } });
    }
    return jsonResponse(500, { code: 'X', message: 'unexpected' });
  });
  try {
    await t.render();
    await t.run(() => latest.saveDraft(header, lines));
    await t.run(() => latest.submit());
    assert.equal(latest.failure.kind, 'conflict');
    assert.equal(latest.failure.message, 'stale version');
    assert.equal(latest.hasPending, false, 'a conflict is not replayed automatically');
    const conflictedId = t.calls.find((call) => call.url.endsWith('/submit')).body.requestId;

    await t.run(() => latest.adoptLatest('case-1', 5));
    assert.equal(latest.caseVersion, 5);
    assert.equal(latest.failure, null);

    await t.run(() => latest.submit());
    assert.equal(submitAttempts, 2);
    const submits = t.calls.filter((call) => call.url.endsWith('/submit'));
    assert.equal(submits[1].body.expectedCaseVersion, 5);
    assert.notEqual(submits[1].body.requestId, conflictedId, 'a corrected intent gets a new request id');
  } finally {
    await t.unmount();
    t.restore();
  }
});
