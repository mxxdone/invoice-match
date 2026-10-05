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

const credentials = { username: 'operator', password: 'p' };
const CASE = '11111111-2222-3333-4444-555555555555';
const hash = 'a'.repeat(64);
const confirmation = {
  documentStageRef: 'doc-ref',
  mappingStageRef: 'map-ref',
  documentDecision: 'NOT_REQUIRED',
  itemDecisions: [{ lineNumber: 2, source: { segmentId: 's2', start: 0, end: 1 }, itemId: null, purchaseOrderLineId: null }],
};
const confirmCommand = (overrides = {}) => ({
  graphId: 'graph-1', expectedCaseVersion: 5, interruptId: 'i1', checkpointHash: hash, reviewVersion: 2,
  confirmation, reason: '원문과 후보를 확인했습니다.', ...overrides,
});

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
    calls, unauthorized, completed,
    async render(options = {}) {
      await act(async () => {
        root.render(createElement(Harness, {
          credentials, sessionId: 1, caseId: CASE, graphIdentity: 'graph-1#i1#2#',
          onUnauthorized: () => unauthorized.push('x'),
          onCompleted: (op) => completed.push(op),
          ...options,
        }));
      });
    },
    async renderStrict(options = {}) {
      await act(async () => {
        root.render(createElement(React.StrictMode, null, createElement(Harness, {
          credentials, sessionId: 1, caseId: CASE, graphIdentity: 'graph-1#i1#2#',
          onUnauthorized: () => unauthorized.push('x'),
          onCompleted: (op) => completed.push(op),
          ...options,
        })));
      });
    },
    async run(fn) { await act(async () => { await fn(); }); },
    async unmount() { await act(async () => { root.unmount(); }); },
    restore() { globalThis.fetch = originalFetch; container.remove(); },
  };
}

test('an operator reserves a graph with exactly the request id and expected version', async () => {
  const t = setup(() => jsonResponse(201, { id: 'graph-1', status: 'QUEUED', contextHash: hash }));
  try {
    await t.render();
    await t.run(() => latest.graphReserve(7));
    assert.equal(t.calls[0].url, `/backend/api/invoice-cases/${CASE}/graphs`);
    assert.equal(t.calls[0].method, 'POST');
    assert.deepEqual(Object.keys(t.calls[0].body).sort(), ['expectedCaseVersion', 'requestId']);
    assert.equal(t.calls[0].body.expectedCaseVersion, 7);
    assert.deepEqual(t.completed, ['graphReserve']);
  } finally { await t.unmount(); t.restore(); }
});

test('a successor reservation targets the predecessor path with the same body shape', async () => {
  const t = setup(() => jsonResponse(201, { id: 'graph-2', status: 'QUEUED', contextHash: hash }));
  try {
    await t.render();
    await t.run(() => latest.graphSuccessor('graph-1', 9));
    assert.equal(t.calls[0].url, `/backend/api/invoice-cases/${CASE}/graphs/graph-1/successors`);
    assert.deepEqual(Object.keys(t.calls[0].body).sort(), ['expectedCaseVersion', 'requestId']);
    assert.equal(t.calls[0].body.expectedCaseVersion, 9);
    assert.deepEqual(t.completed, ['graphSuccessor']);
  } finally { await t.unmount(); t.restore(); }
});

test('a single confirm posts the exact GraphReviewController body once', async () => {
  const t = setup(() => jsonResponse(202, { graphExecutionId: 'graph-1', reviewId: 'r1', reviewStatus: 'SAVED', resumeStatus: 'QUEUED' }));
  try {
    await t.render();
    await t.run(() => latest.graphConfirm(confirmCommand()));
    assert.equal(t.calls.length, 1);
    assert.equal(t.calls[0].url, `/backend/api/invoice-cases/${CASE}/graphs/graph-1/reviews`);
    assert.deepEqual(Object.keys(t.calls[0].body).sort(), ['checkpointHash', 'confirmation', 'expectedCaseVersion', 'interruptId', 'reason', 'requestId', 'reviewVersion']);
    assert.equal(t.calls[0].body.interruptId, 'i1');
    assert.equal(t.calls[0].body.reviewVersion, 2);
    assert.deepEqual(t.calls[0].body.confirmation, confirmation);
    assert.deepEqual(t.completed, ['graphConfirm']);
    assert.equal(latest.unresolved, null);
  } finally { await t.unmount(); t.restore(); }
});

test('a lost confirm response keeps the exact intent and retry resends the same request id and body', async () => {
  let attempts = 0;
  const t = setup(() => ++attempts === 1 ? Promise.reject(new TypeError('connection lost')) : jsonResponse(202, { reviewStatus: 'SAVED' }));
  try {
    await t.render();
    await t.run(() => latest.graphConfirm(confirmCommand()));
    assert.equal(latest.unresolved.operation, 'graphConfirm');
    const first = t.calls[0].body;
    await t.run(() => latest.retry());
    assert.equal(t.calls.length, 2);
    assert.deepEqual(t.calls[1].body, first);
    assert.deepEqual(t.completed, ['graphConfirm']);
    assert.equal(latest.unresolved, null);
  } finally { await t.unmount(); t.restore(); }
});

test('a graph/interrupt/review version change drops the old unresolved graph intent', async () => {
  let resolveConfirm;
  const pending = new Promise((resolve) => { resolveConfirm = resolve; });
  const t = setup(() => pending);
  try {
    await t.render();
    let promise;
    await act(async () => { promise = latest.graphConfirm(confirmCommand()); });
    await t.render({ graphIdentity: 'graph-1#i1#3#' });
    assert.equal(latest.unresolved, null);
    assert.equal(latest.pendingAction, null);
    resolveConfirm(jsonResponse(202, { reviewStatus: 'SAVED' }));
    await act(async () => { await promise; });
    assert.deepEqual(t.completed, [], 'a late confirm from the previous review version must not complete the new identity');
  } finally { await t.unmount(); t.restore(); }
});

test('a late confirm from the previous session never signs out the new one', async () => {
  let resolveConfirm;
  const pending = new Promise((resolve) => { resolveConfirm = resolve; });
  const t = setup(() => pending);
  try {
    await t.render({ sessionId: 1, onUnauthorized: () => t.unauthorized.push('old') });
    let promise;
    await act(async () => { promise = latest.graphConfirm(confirmCommand()); });
    await t.render({ sessionId: 2, credentials: { username: 'B', password: 'p' }, onUnauthorized: () => t.unauthorized.push('new') });
    resolveConfirm(jsonResponse(401, { code: 'UNAUTHENTICATED' }));
    await act(async () => { await promise; });
    assert.deepEqual(t.unauthorized, []);
    assert.deepEqual(t.completed, []);
  } finally { await t.unmount(); t.restore(); }
});

test('an unresolved graph intent blocks a different page action until the exact request is retried', async () => {
  let attempts = 0;
  const t = setup((url) => {
    if (url.endsWith('/reviews')) {
      attempts += 1;
      return attempts === 1 ? jsonResponse(503, { code: 'CORE_API_UNAVAILABLE', message: 'x' }) : jsonResponse(202, { reviewStatus: 'SAVED' });
    }
    return jsonResponse(500, { code: 'X', message: 'unexpected' });
  });
  try {
    await t.render();
    await t.run(() => latest.graphConfirm(confirmCommand()));
    assert.equal(latest.unresolved.operation, 'graphConfirm');
    const before = t.calls.length;
    await t.run(() => latest.approve({ expectedCaseVersion: 5, reviewSnapshotId: 'snap-1', reviewPayloadHash: 'h' }));
    assert.equal(t.calls.length, before, 'a page action must not be sent while a graph intent is unresolved');
    const firstId = t.calls[0].body.requestId;
    await t.run(() => latest.retry());
    const confirms = t.calls.filter((call) => call.url.endsWith('/reviews'));
    assert.equal(confirms.length, 2);
    assert.equal(confirms[1].body.requestId, firstId);
    assert.deepEqual(t.completed, ['graphConfirm']);
  } finally { await t.unmount(); t.restore(); }
});

test('a page unresolved intent blocks a different graph action', async () => {
  let attempts = 0;
  const t = setup((url) => {
    if (url.endsWith('/approve')) {
      attempts += 1;
      return attempts === 1 ? jsonResponse(503, { code: 'CORE_API_UNAVAILABLE', message: 'x' }) : jsonResponse(200, {});
    }
    return jsonResponse(500, { code: 'X', message: 'unexpected' });
  });
  try {
    await t.render();
    await t.run(() => latest.approve({ expectedCaseVersion: 5, reviewSnapshotId: 'snap-1', reviewPayloadHash: 'h' }));
    assert.equal(latest.unresolved.operation, 'approve');
    const before = t.calls.length;
    await t.run(() => latest.graphReserve(5));
    assert.equal(t.calls.length, before, 'a graph action must not be sent while a page intent is unresolved');
  } finally { await t.unmount(); t.restore(); }
});

test('under React StrictMode a graph confirm still completes exactly once', async () => {
  const t = setup(() => jsonResponse(202, { reviewStatus: 'SAVED' }));
  try {
    await t.renderStrict();
    await t.run(() => latest.graphConfirm(confirmCommand()));
    assert.deepEqual(t.completed, ['graphConfirm']);
    assert.equal(t.calls.length, 1);
  } finally { await t.unmount(); t.restore(); }
});

test('an A to B to A graph identity change cannot revive the first late confirm', async () => {
  let resolveConfirm;
  const pending = new Promise((resolve) => { resolveConfirm = resolve; });
  const t = setup(() => pending);
  try {
    await t.render({ graphIdentity: 'graph-1#i1#2#' });
    let promise;
    await act(async () => { promise = latest.graphConfirm(confirmCommand()); });
    await t.render({ graphIdentity: 'graph-1#i9#2#' });
    await t.render({ graphIdentity: 'graph-1#i1#2#' });
    resolveConfirm(jsonResponse(202, { reviewStatus: 'SAVED' }));
    await act(async () => { await promise; });
    assert.deepEqual(t.completed, [], 'a late confirm after an A to B to A return must not complete');
    assert.equal(latest.unresolved, null);
  } finally { await t.unmount(); t.restore(); }
});

test('a retry whose graph identity changes mid-flight cannot sign out or complete', async () => {
  let confirmAttempts = 0;
  let resolveRetry;
  const retryPending = new Promise((resolve) => { resolveRetry = resolve; });
  const t = setup((url) => {
    if (!url.endsWith('/reviews')) return jsonResponse(500, { code: 'X', message: 'x' });
    confirmAttempts += 1;
    if (confirmAttempts === 1) return jsonResponse(503, { code: 'CORE_API_UNAVAILABLE', message: 'x' });
    return retryPending;
  });
  try {
    await t.render({ graphIdentity: 'graph-1#i1#2#' });
    await t.run(() => latest.graphConfirm(confirmCommand()));
    assert.equal(latest.unresolved.operation, 'graphConfirm');
    let retryPromise;
    await act(async () => { retryPromise = latest.retry(); });
    await t.render({ graphIdentity: 'graph-1#i1#3#', onUnauthorized: () => t.unauthorized.push('late') });
    resolveRetry(jsonResponse(401, { code: 'UNAUTHENTICATED' }));
    await act(async () => { await retryPromise; });
    assert.deepEqual(t.unauthorized, [], 'a late retry 401 from the old review version must not log out');
    assert.deepEqual(t.completed, []);
  } finally { await t.unmount(); t.restore(); }
});

test('mutating the caller confirmation after a lost response cannot rewrite the retried body', async () => {
  let attempts = 0;
  const t = setup(() => {
    attempts += 1;
    return attempts === 1 ? Promise.reject(new TypeError('connection lost')) : jsonResponse(202, { reviewStatus: 'SAVED' });
  });
  try {
    await t.render();
    const command = confirmCommand();
    await t.run(() => latest.graphConfirm(command));
    const first = t.calls[0].body;
    const firstBodyText = JSON.stringify(first);
    command.reason = '사후에 바뀐 사유';
    command.confirmation.itemDecisions[0].itemId = 'MUTATED';
    command.confirmation.documentDecision = 'NEEDS_CORRECTION';
    await t.run(() => latest.retry());
    assert.equal(JSON.stringify(t.calls[1].body), firstBodyText, 'the retry must resend the exact frozen body');
    assert.deepEqual(t.completed, ['graphConfirm']);
  } finally { await t.unmount(); t.restore(); }
});
