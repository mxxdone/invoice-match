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
const { useCaseActions } = await import('../src/app/cases/[id]/use-case-actions.ts');

const { createElement, act } = React;

let latest = null;
function Harness(props) {
  latest = useCaseActions(props);
  return null;
}

const credentials = { username: 'approver', password: 'p' };
const CASE = '11111111-2222-3333-4444-555555555555';

function jsonResponse(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

function setup(responder) {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = (url, init) => {
    const parsed = { url: String(url), method: init?.method, body: init?.body ? JSON.parse(init.body) : null };
    calls.push(parsed);
    return Promise.resolve().then(() => responder(String(url), init));
  };
  const unauthorized = [];
  const completed = [];
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  return {
    calls,
    unauthorized,
    completed,
    async render(options = {}) {
      await act(async () => {
        root.render(createElement(Harness, {
          credentials,
          sessionId: 1,
          caseId: CASE,
          onUnauthorized: () => unauthorized.push('x'),
          onCompleted: (op) => completed.push(op),
          ...options,
        }));
      });
    },
    async run(fn) { await act(async () => { await fn(); }); },
    async unmount() { await act(async () => { root.unmount(); }); },
    restore() { globalThis.fetch = originalFetch; container.remove(); },
  };
}

test('an operator match posts one intent and reloads on success', async () => {
  const t = setup((url, init) => {
    assert.equal(init.method, 'POST');
    assert.equal(url, `/backend/api/invoice-cases/${CASE}/match`);
    return jsonResponse(201, { id: 'm1', payload: {} });
  });
  try {
    await t.render();
    await t.run(() => latest.runMatch());
    assert.deepEqual(t.calls[0].body, { requestId: t.calls[0].body.requestId });
    assert.equal(typeof t.calls[0].body.requestId, 'string');
    assert.deepEqual(t.completed, ['match']);
    assert.equal(latest.failure, null);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('approve sends the frozen subject fields and never an extra bundle version or decidedBy', async () => {
  const t = setup(() => jsonResponse(200, { invoiceCaseId: CASE, status: 'EXPORT_PENDING', caseVersion: 3, allocations: [] }));
  try {
    await t.render();
    await t.run(() => latest.approve({ expectedCaseVersion: 2, reviewSnapshotId: 'snap-1', reviewPayloadHash: 'hash-1' }));
    const body = t.calls[0].body;
    assert.equal(body.expectedCaseVersion, 2);
    assert.equal(body.reviewSnapshotId, 'snap-1');
    assert.equal(body.reviewPayloadHash, 'hash-1');
    assert.equal(body.evidenceBundleVersion, undefined);
    assert.equal(body.decidedBy, undefined);
    assert.deepEqual(t.completed, ['approve']);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a server self-approval denial is surfaced and the page is not treated as completed', async () => {
  const t = setup(() => jsonResponse(403, { code: 'SELF_APPROVAL_FORBIDDEN', message: '본인이 제출한 청구는 승인할 수 없습니다.' }));
  try {
    await t.render();
    let result;
    await t.run(async () => { result = await latest.approve({ expectedCaseVersion: 2, reviewSnapshotId: 'snap-1', reviewPayloadHash: 'hash-1' }); });
    assert.equal(result, false);
    assert.equal(latest.failure.kind, 'forbidden');
    assert.equal(latest.failure.code, 'SELF_APPROVAL_FORBIDDEN');
    assert.equal(latest.failure.message, '본인이 제출한 청구는 승인할 수 없습니다.');
    assert.deepEqual(t.completed, []);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a mapping intent sends the line item id and does not invent a purchase order line', async () => {
  const t = setup(() => jsonResponse(200, { decision: {}, successorSnapshot: {} }));
  try {
    await t.render();
    await t.run(() => latest.recordMapping({ expectedCaseVersion: 2, reviewSnapshotId: 'snap-1', reviewPayloadHash: 'hash-1', lineNumber: 1, itemId: 'ITEM-X' }));
    const body = t.calls[0].body;
    assert.equal(body.lineNumber, 1);
    assert.equal(body.itemId, 'ITEM-X');
    assert.equal(body.mappingPoLineId, undefined);
    assert.equal(body.decidedBy, undefined);
    assert.equal(t.calls[0].url, `/backend/api/invoice-cases/${CASE}/mapping-decisions`);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('an uncertain failure keeps the intent so a retry reuses the exact request id', async () => {
  let attempts = 0;
  const t = setup(() => {
    attempts += 1;
    if (attempts === 1) throw new TypeError('failed to fetch');
    return jsonResponse(200, { invoiceCaseId: CASE, status: 'EXPORT_PENDING' });
  });
  try {
    await t.render();
    await t.run(() => latest.approve({ expectedCaseVersion: 2, reviewSnapshotId: 'snap-1', reviewPayloadHash: 'hash-1' }));
    assert.equal(latest.failure.kind, 'uncertain');
    const firstId = t.calls[0].body.requestId;
    await t.run(() => latest.approve({ expectedCaseVersion: 2, reviewSnapshotId: 'snap-1', reviewPayloadHash: 'hash-1' }));
    assert.equal(t.calls.length, 2);
    assert.equal(t.calls[1].body.requestId, firstId);
    assert.deepEqual(t.completed, ['approve']);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a 409 conflict is surfaced with the server code and is not auto-retried', async () => {
  const t = setup(() => jsonResponse(409, { code: 'REVIEW_SNAPSHOT_STALE', message: '검토 대상이 오래되었습니다.' }));
  try {
    await t.render();
    await t.run(() => latest.requestSupplement({ expectedCaseVersion: 2, reviewSnapshotId: 'snap-1', reviewPayloadHash: 'hash-1', reason: 'x' }));
    assert.equal(latest.failure.kind, 'conflict');
    assert.equal(latest.failure.code, 'REVIEW_SNAPSHOT_STALE');
    assert.deepEqual(t.completed, []);
    assert.equal(latest.pendingAction, null);
  } finally {
    await t.unmount();
    t.restore();
  }
});
