import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
globalThis.window = dom.window; globalThis.document = dom.window.document;
for (const key of Object.getOwnPropertyNames(dom.window)) {
  if (!(key in globalThis)) { try { globalThis[key] = dom.window[key]; } catch { /* accessor-only */ } }
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const { createElement, act } = await import('react');
const { createRoot } = await import('react-dom/client');
const { useGraphReview, useGraphRun } = await import('../src/app/cases/[id]/use-graph-review.ts');
const { GraphReviewPanel } = await import('../src/app/cases/[id]/graph-review-panel.tsx');

const credentials = { username: 'operator', password: 'fixture' };
const response = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const page = (id) => ({ enabled: true, latest: null, history: [{ id }] });

let pageHook;
function PageHarness(props) { pageHook = useGraphReview(props); return null; }
let runHook;
function RunHarness(props) { runHook = useGraphRun(props); return null; }

function hookSetup(Harness, props) {
  const original = globalThis.fetch, pending = [], unauthorized = [];
  globalThis.fetch = (url, init) => new Promise(resolve => pending.push({ url: String(url), init, resolve }));
  const container = document.createElement('div'); document.body.appendChild(container);
  const root = createRoot(container), onUnauthorized = () => unauthorized.push('401');
  return { pending, unauthorized,
    async render(overrides = {}) { await act(async () => root.render(createElement(Harness, { credentials, sessionId: 1, caseId: 'A', enabled: true, reloadToken: 0, onUnauthorized, ...props, ...overrides }))); },
    async finish(index, status, body) { await act(async () => pending[index].resolve(response(status, body))); },
    async close() { await act(async () => root.unmount()); container.remove(); globalThis.fetch = original; },
  };
}

test('a late graph page GET and late 401 cannot replace or sign out a different case or session', async () => {
  const t = hookSetup(PageHarness, {});
  try {
    await t.render(); await t.render({ caseId: 'B', sessionId: 2 });
    assert.equal(t.pending[0].init.signal.aborted, true);
    await t.finish(1, 200, page('B')); assert.equal(pageHook.data.history[0].id, 'B');
    await t.finish(0, 401, {}); assert.equal(pageHook.data.history[0].id, 'B'); assert.deepEqual(t.unauthorized, []);
  } finally { await t.close(); }
});

test('a late history run GET for an abandoned selection is discarded', async () => {
  const t = hookSetup(RunHarness, { graphId: 'run-A' });
  try {
    await t.render(); await t.render({ graphId: 'run-B' });
    assert.equal(t.pending[0].init.signal.aborted, true);
    await t.finish(1, 200, { run: { id: 'run-B' }, pending: null, review: null, payload: null, sources: [] });
    assert.equal(runHook.data.run.id, 'run-B');
    await t.finish(0, 200, { run: { id: 'run-A' }, pending: null, review: null, payload: null, sources: [] });
    assert.equal(runHook.data.run.id, 'run-B');
  } finally { await t.close(); }
});

const source = { segmentId: 's1', start: 0, end: 1 };
const candidate = (itemId, poLine) => ({ itemId, purchaseOrderLineId: poLine, reason: '', reasonCodes: [], priorSnapshotId: null });
const run = {
  id: 'g1', status: 'WAITING_HUMAN', segment: 'START', current: true, supported: true, caseVersion: 5,
  contextHash: 'c', payloadHash: null, startAttempts: 1, resumeAttempts: 0, reservedCalls: 1, reservedTokens: 100,
  toolCalls: 0, errorCode: null, completedStages: ['document', 'mapping'], predecessorId: null, createdAt: '2026-01-01T00:00:00Z',
};
const pending = {
  interruptId: 'i1', checkpointHash: 'a'.repeat(64), reviewVersion: 2, documentStageRef: 'd', mappingStageRef: 'm',
  reasonCodes: ['DOCUMENT_REVIEW_REQUIRED', 'NO_ITEM_CANDIDATE', 'AMBIGUOUS_ITEM'],
  document: { fields: [{ name: 'invoiceNumber', value: '<script>untrusted()</script>', source }], lines: [], warnings: [] },
  mapping: { lines: [
    { lineNumber: 1, source, reviewRequired: false, warningCodes: [], candidates: [candidate('A', 'p1')] },
    { lineNumber: 2, source, reviewRequired: true, warningCodes: [], candidates: [] },
    { lineNumber: 3, source, reviewRequired: true, warningCodes: [], candidates: [candidate('A', 'p1'), candidate('B', 'p2')] },
  ] },
};
const view = { run, pending, review: null, payload: null, sources: [{ id: 's1', documentId: 'doc1', text: '가😀나', origin: 'ocr', page: 2, sheet: null, cell: null }] };
const graphPage = { enabled: true, latest: view, history: [run] };
const baseProps = {
  load: { status: 'ready', data: graphPage }, view, historySelectedId: null, onSelectHistory() {},
  historyLoading: false, historyError: null, isOperator: true, isApprover: false, blocked: false, confirmPending: false,
  lastSuccess: null, onReserve() {}, onSuccessor() {}, onConfirm() {}, onRefresh() {}, proofSelected: false, onSelectProof() {},
};

async function renderPanel(overrides = {}) {
  const container = document.createElement('div'); document.body.appendChild(container); const root = createRoot(container);
  await act(async () => root.render(createElement(GraphReviewPanel, { ...baseProps, ...overrides })));
  return { container, async close() { await act(async () => root.unmount()); container.remove(); } };
}

test('the graph panel is hidden for a non-reviewer and shows a single confirm surface for an operator', async () => {
  const hidden = await renderPanel({ load: { status: 'forbidden' } });
  try { assert.equal(hidden.container.textContent, ''); } finally { await hidden.close(); }
  const operator = await renderPanel();
  try {
    assert.match(operator.container.textContent, /사람 확인 저장/);
    assert.match(operator.container.textContent, /문서 후보 확인 필요/);
    assert.equal(operator.container.querySelectorAll('input[type=radio]').length, 2);
  } finally { await operator.close(); }
  const approver = await renderPanel({ isOperator: false, isApprover: true });
  try {
    assert.doesNotMatch(approver.container.textContent, /사람 확인 저장/);
    assert.match(approver.container.textContent, /운영자만 사람 확인을 저장할 수 있습니다/);
  } finally { await approver.close(); }
});

test('only lines without exactly one candidate get a decision select, and unresolved is offered', async () => {
  const panel = await renderPanel();
  try {
    const selects = [...panel.container.querySelectorAll('select')];
    assert.equal(selects.length, 2, 'the single-candidate line 1 must not need a decision');
    assert.deepEqual([...selects[0].options].map((option) => option.textContent), ['미해결로 기록']);
    assert.deepEqual([...selects[1].options].map((option) => option.textContent), ['미해결로 기록', 'A · 발주 라인 p1', 'B · 발주 라인 p2']);
  } finally { await panel.close(); }
});

test('the panel escapes untrusted extracted text and shows the frozen source location', async () => {
  const panel = await renderPanel();
  try {
    assert.equal(panel.container.querySelector('script'), null);
    assert.match(panel.container.textContent, /<script>untrusted\(\)<\/script>/);
    assert.match(panel.container.textContent, /문서 doc1 · 2쪽 \(OCR\)/);
  } finally { await panel.close(); }
});

test('a completed current graph proposal can be selected as the single freeze proof', async () => {
  const completed = { run: { ...run, status: 'COMPLETED', payloadHash: 'hash-g' }, pending: null, review: null, payload: { resolution: { result: { recommendation: 'REVIEW_REQUIRED', summary: 's', warnings: [], citations: [] } }, document: { result: { fields: [], lines: [], warnings: [] } }, mapping: { result: { lines: [] } }, policyEvidence: { status: 'NOT_REQUIRED', result: [] }, facts: {}, schemaVersion: 'advisory-proposal-v2', graphVersion: 'v1', humanReview: { reviewId: 'r', confirmationHash: 'h', confirmation: {} } }, sources: [] };
  const page2 = { enabled: true, latest: completed, history: [completed.run] };
  const selected = [];
  const panel = await renderPanel({ load: { status: 'ready', data: page2 }, view: completed, isOperator: false, isApprover: true, onSelectProof: (proof) => selected.push(proof) });
  try {
    const checkbox = [...panel.container.querySelectorAll('input[type=checkbox]')][0];
    assert.ok(checkbox, 'the approver sees the graph proof checkbox');
    await act(async () => checkbox.click());
    assert.deepEqual(selected, [{ proposalId: 'g1', proposalHash: 'hash-g' }]);
  } finally { await panel.close(); }
});
