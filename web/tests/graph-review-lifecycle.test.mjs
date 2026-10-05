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
const { useGraphReview, useGraphRun, useGraphActionIdentity, graphServerIdentity } = await import('../src/app/cases/[id]/use-graph-review.ts');
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
  historyLoading: false, historyError: null, isOperator: true, isApprover: false, blocked: false, reservePending: false, confirmPending: false,
  lastSuccess: null, onReserve() {}, onSuccessor() {}, onConfirm() {}, onRefresh() {}, proofCandidate: null, proofSelected: false, onSelectProof() {},
};

async function renderPanel(overrides = {}) {
  const container = document.createElement('div'); document.body.appendChild(container); const root = createRoot(container);
  await act(async () => root.render(createElement(GraphReviewPanel, { ...baseProps, ...overrides })));
  return { container, async close() { await act(async () => root.unmount()); container.remove(); } };
}

let identityLatest;
function IdentityHarness({ selection, view: v }) { identityLatest = useGraphActionIdentity(selection, v); return null; }

test('graph action identity survives a read loading but changes on the server wait or history selection', async () => {
  const container = document.createElement('div'); document.body.appendChild(container); const root = createRoot(container);
  const render = (props) => act(async () => root.render(createElement(IdentityHarness, props)));
  try {
    await render({ selection: null, view });
    const loaded = identityLatest;
    assert.equal(loaded, `latest#${graphServerIdentity(view)}`);
    await render({ selection: null, view: null });
    assert.equal(identityLatest, loaded, 'a merely loading read must keep the identity');
    await render({ selection: null, view: { ...view, review: { id: 'r1', actor: 'op', reason: 'x', confirmation: {}, createdAt: '', resumeStatus: 'QUEUED' } } });
    const changed = identityLatest;
    assert.notEqual(changed, loaded, 'a new server wait/review must change the identity');
    await render({ selection: 'history-1', view: null });
    assert.equal(identityLatest, 'history-1#', 'an explicit history selection starts a new identity even while loading');
    assert.notEqual(identityLatest, changed);
  } finally { await act(async () => root.unmount()); container.remove(); }
});

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
  const panel = await renderPanel({ load: { status: 'ready', data: page2 }, view: completed, isOperator: false, isApprover: true, proofCandidate: { proposalId: 'g1', proposalHash: 'hash-g' }, onSelectProof: (proof) => selected.push(proof) });
  try {
    const checkbox = [...panel.container.querySelectorAll('input[type=checkbox]')][0];
    assert.ok(checkbox, 'the approver sees the graph proof checkbox');
    await act(async () => checkbox.click());
    assert.deepEqual(selected, [{ proposalId: 'g1', proposalHash: 'hash-g' }]);
  } finally { await panel.close(); }
});

test('an operator can start the first analysis when there is no run yet', async () => {
  const empty = { enabled: true, latest: null, history: [] };
  let reserved = 0;
  const panel = await renderPanel({ load: { status: 'ready', data: empty }, view: null, onReserve: () => { reserved += 1; } });
  try {
    const button = [...panel.container.querySelectorAll('button')].find((b) => b.textContent.includes('AI 분석 예약'));
    assert.ok(button, 'the first-reserve button is offered');
    await act(async () => button.click());
    assert.equal(reserved, 1);
  } finally { await panel.close(); }
});

test('a disabled workflow hides an empty panel and never offers reserve or confirm, but keeps history readable', async () => {
  const emptyOff = { enabled: false, latest: null, history: [] };
  const hidden = await renderPanel({ load: { status: 'ready', data: emptyOff }, view: null });
  try { assert.equal(hidden.container.textContent, ''); } finally { await hidden.close(); }

  const historyOff = { enabled: false, latest: view, history: [run, { ...run, id: 'g0' }] };
  let reserved = 0;
  const off = await renderPanel({ load: { status: 'ready', data: historyOff }, view, onReserve: () => { reserved += 1; } });
  try {
    assert.doesNotMatch(off.container.textContent, /사람 확인 저장/);
    assert.doesNotMatch(off.container.textContent, /AI 분석 예약/);
    assert.match(off.container.textContent, /AI 분석 이력/);
    assert.match(off.container.textContent, /AI 신규 분석이 비활성화되어 있습니다/);
    assert.equal(reserved, 0);
  } finally { await off.close(); }
});

test('an unsupported stored run shows metadata only and offers no reserve, confirm or proof', async () => {
  const unsupported = { run: { ...run, supported: false, status: 'COMPLETED', payloadHash: 'h' }, pending, review: null, payload: null, sources: [] };
  const page3 = { enabled: true, latest: unsupported, history: [unsupported.run] };
  const panel = await renderPanel({ load: { status: 'ready', data: page3 }, view: unsupported, isApprover: true, proofCandidate: null });
  try {
    assert.match(panel.container.textContent, /지원하지 않는 저장 형식입니다/);
    assert.doesNotMatch(panel.container.textContent, /AI 분석 예약/);
    assert.doesNotMatch(panel.container.textContent, /사람 확인 저장/);
    assert.equal(panel.container.querySelectorAll('input[type=checkbox]').length, 0);
  } finally { await panel.close(); }
});

test('a non-null latest proof cannot be selected next to a different history view', async () => {
  const completed = (id, current, payloadHash) => ({
    run: { ...run, id, status: 'COMPLETED', current, supported: true, payloadHash },
    pending: null, review: null, sources: [],
    payload: {
      resolution: { result: { recommendation: 'REVIEW_REQUIRED', summary: 's', warnings: [], citations: [] } },
      document: { result: { fields: [], lines: [], warnings: [] } },
      mapping: { result: { lines: [] } },
      policyEvidence: { status: 'NOT_REQUIRED', result: [] },
      facts: {}, schemaVersion: 'advisory-proposal-v2', graphVersion: 'v1',
      humanReview: { reviewId: 'r', confirmationHash: 'h', confirmation: {} },
    },
  });
  const latest = completed('g1', true, 'hash-g');
  const history = completed('g0', false, 'hash-old');
  const page4 = { enabled: true, latest, history: [latest.run, history.run] };
  const panel = await renderPanel({ load: { status: 'ready', data: page4 }, view: history, isOperator: false, isApprover: true, proofCandidate: { proposalId: 'g1', proposalHash: 'hash-g' } });
  try {
    assert.equal(panel.container.querySelectorAll('input[type=checkbox]').length, 0, 'the history view is not the eligible proof');
    assert.match(panel.container.textContent, /검토 근거로 선택할 수 없습니다/);
  } finally { await panel.close(); }
  const current = await renderPanel({ load: { status: 'ready', data: page4 }, view: latest, isOperator: false, isApprover: true, proofCandidate: { proposalId: 'g1', proposalHash: 'hash-g' } });
  try {
    assert.equal(current.container.querySelectorAll('input[type=checkbox]').length, 1, 'the exact eligible latest proof is selectable');
  } finally { await current.close(); }
});

test('resume status labels map the Core terminal values', async () => {
  const { presentGraphResumeStatus } = await import('../src/app/cases/[id]/graph-model.ts');
  assert.equal(presentGraphResumeStatus('COMPLETED'), '재개 완료');
  assert.equal(presentGraphResumeStatus('CANCELLED'), '재개 취소');
  assert.equal(presentGraphResumeStatus('QUEUED'), '재개 대기');
});
