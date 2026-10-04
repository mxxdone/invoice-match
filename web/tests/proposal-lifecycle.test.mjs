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
const { useProposalReview } = await import('../src/app/cases/[id]/use-proposal-review.ts');
const { ProposalPanel } = await import('../src/app/cases/[id]/proposal-panel.tsx');
let latest;
function Harness(props) { latest = useProposalReview(props); return null; }
const credentials = { username: 'operator', password: 'fixture' };
const response = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const page = id => ({ enabled: true, latest: null, history: [{ id }] });
function setup() {
  const original = globalThis.fetch, pending = [], unauthorized = [];
  globalThis.fetch = (url, init) => new Promise(resolve => pending.push({ url: String(url), init, resolve }));
  const container = document.createElement('div'); document.body.appendChild(container);
  const root = createRoot(container), onUnauthorized = () => unauthorized.push('401');
  return { pending, unauthorized,
    async render(overrides = {}) { await act(async () => root.render(createElement(Harness, { credentials, sessionId: 1, caseId: 'A', enabled: true, reloadToken: 0, onUnauthorized, ...overrides }))); },
    async finish(index, status, body) { await act(async () => pending[index].resolve(response(status, body))); },
    async close() { await act(async () => root.unmount()); container.remove(); globalThis.fetch = original; }
  };
}

test('late responses and late 401 cannot replace or log out a different case or session', async () => {
  const t = setup();
  try {
    await t.render(); await t.render({ caseId: 'B', sessionId: 2 });
    assert.equal(t.pending[0].init.signal.aborted, true);
    await t.finish(1, 200, page('B')); assert.equal(latest.data.history[0].id, 'B');
    await t.finish(0, 401, {}); assert.equal(latest.data.history[0].id, 'B'); assert.deepEqual(t.unauthorized, []);
  } finally { await t.close(); }
});

test('A to B to A and permission toggles cannot resurrect a previous completed response', async () => {
  const t = setup();
  try {
    await t.render(); await t.finish(0, 200, page('old-A'));
    await t.render({ caseId: 'B' }); await t.render(); assert.equal(latest.status, 'loading');
    await t.finish(1, 200, page('late-B')); assert.equal(latest.status, 'loading');
    await t.finish(2, 200, page('new-A')); assert.equal(latest.data.history[0].id, 'new-A');
    await t.render({ enabled: false }); assert.equal(latest.status, 'forbidden');
    await t.render(); assert.equal(latest.status, 'loading');
    await t.finish(3, 200, page('after-permission')); assert.equal(latest.data.history[0].id, 'after-permission');
  } finally { await t.close(); }
});

test('disabled readers never fetch and only the current 401 requests logout', async () => {
  const t = setup();
  try {
    await t.render({ enabled: false }); assert.equal(t.pending.length, 0);
    await t.render(); await t.finish(0, 403, {}); assert.equal(latest.status, 'forbidden'); assert.deepEqual(t.unauthorized, []);
    await t.render({ reloadToken: 1 }); await t.finish(1, 401, {}); assert.deepEqual(t.unauthorized, ['401']);
  } finally { await t.close(); }
});

test('panel escapes untrusted draft text and displays stale results without offering business actions', async () => {
  const container = document.createElement('div'); document.body.appendChild(container); const root = createRoot(container);
  const payload = { resolution: { result: { recommendation: 'REVIEW_REQUIRED', summary: '<script>untrusted()</script>', warnings: [], citations: [] } },
    document: { result: { fields: [], lines: [], warnings: [] } }, mapping: { result: { lines: [] } },
    facts: { invoiceTotal: { value: '9007199254740993', unit: 'KRW' } }, policyEvidence: { status: 'NOT_REQUIRED', result: [] } };
  const view = { run: { id: 'old', status: 'COMPLETED', current: false, completedStages: [], attempt: 1, reservedCalls: 1, reservedTokens: 100, toolCalls: 0 }, payload, sources: [] };
  try {
    await act(async () => root.render(createElement(ProposalPanel, { load: { status: 'ready', data: { enabled: false, latest: view, history: [] } }, match: null, canReserve: false, pending: false, blocked: false, onReserve() {}, onRefresh() {} })));
    assert.equal(container.querySelector('script'), null);
    assert.match(container.textContent, /<script>untrusted\(\)<\/script>/);
    assert.match(container.textContent, /현재 검토 근거로 동결할 수 없습니다/);
    assert.match(container.textContent, /9,007,199,254,740,993/);
    assert.deepEqual([...container.querySelectorAll('button')].map(b => b.textContent), ['분석 상태 새로 조회']);
  } finally { await act(async () => root.unmount()); container.remove(); }
});
