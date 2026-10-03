import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
const dom = new JSDOM('<html><body></body></html>', { url: 'http://localhost/' });
globalThis.window = dom.window;globalThis.document = dom.window.document;
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const { createElement, act } = await import('react');
const { createRoot } = await import('react-dom/client');
const { useAnalysisRetry } = await import('../src/app/operations/analysis/use-analysis-retry.ts');
const credentials = { username: 'operator', password: 'test' };
const job = { runId: '11111111-2222-3333-4444-555555555555', executionAttempt: 3, status: 'DEAD_LETTERED' };
let current;
function Harness({ session = 1, enabled = true, completed, unauthorized }) {
  current = useAnalysisRetry(credentials, session, enabled, completed, unauthorized);return null;
}
async function setup(responder) {
  const original = globalThis.fetch;const calls = [];const completed = [];const unauthorized = [];
  globalThis.fetch = (url, options) => { calls.push({ url, body: JSON.parse(options.body) });return responder(url, options); };
  const root = createRoot(document.createElement('div'));
  const render = async props => act(async () => { root.render(createElement(Harness, { completed: () => completed.push(true), unauthorized: () => unauthorized.push(true), ...props })); });
  await render({});
  return { calls, completed, unauthorized, render, close: async () => { await act(async () => root.unmount());globalThis.fetch = original; } };
}
const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
test('uncertain retry retains exact id and body despite edited reason', async () => {
  let attempt = 0;
  const t = await setup(() => Promise.resolve(++attempt === 1 ? json(503, {}) : json(200, { status: 'QUEUED' })));
  try {
    await act(async () => { await current.execute(job, 'fixed'); });assert.equal(current.uncertain, true);
    await act(async () => { await current.execute(job, 'different'); });
    assert.deepEqual(t.calls[0], t.calls[1]);assert.equal(t.completed.length, 1);assert.equal(current.uncertain, false);
  } finally { await t.close(); }
});
test('late 401 from replaced login never signs out the new user', async () => {
  let resolve;const t = await setup(() => new Promise(done => { resolve = done; }));
  try {
    let pending;await act(async () => { pending = current.execute(job, 'fixed'); });
    await t.render({ session: 2 });
    await act(async () => { resolve(json(401, {}));await pending; });
    assert.equal(t.unauthorized.length, 0);assert.equal(current.intent, null);assert.equal(current.pending, false);
  } finally { await t.close(); }
});
test('non operator and parser failure never issue retry', async () => {
  const t = await setup(() => Promise.resolve(json(200, {})));
  try {
    await t.render({ enabled: false });await act(async () => { await current.execute(job, 'fixed'); });
    await t.render({});await act(async () => { await current.execute({ ...job, status: 'FAILED' }, 'fixed'); });
    assert.equal(t.calls.length, 0);
  } finally { await t.close(); }
});
