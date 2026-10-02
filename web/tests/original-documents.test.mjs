import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><body></body>', { url: 'http://localhost/' });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const React = await import('react');
const { createRoot } = await import('react-dom/client');
const { useCaseDocuments } = await import('../src/app/cases/[id]/use-case-documents.ts');
const { OriginalDocuments } = await import('../src/app/cases/[id]/original-documents.tsx');
const { act, createElement } = React;
const credentials = { username: 'submitter', password: 'p' };
const pdf = { documentId: 'pdf', fileName: '청구서.pdf', mediaType: 'application/pdf', sizeBytes: 100 };
const xlsx = { ...pdf, documentId: 'xlsx', fileName: '청구서.xlsx', mediaType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' };
const response = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const page = (items = [pdf, xlsx], page = 0, hasNext = false) => response({ items, page, size: 20, hasNext });
const link = (expiresAt = new Date(Date.now() + 120000).toISOString()) => response({ documentId: 'pdf', fileName: pdf.fileName, mediaType: pdf.mediaType, url: 'http://localhost:9000/private/file?signature=example', method: 'GET', expiresAt });
const deferred = () => { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; };
let latest;
function Harness(props) { latest = useCaseDocuments(props); return null; }

function mount(respond, component = Harness) {
  const previous = globalThis.fetch;
  const calls = [];
  globalThis.fetch = (url, init) => { calls.push({ url: String(url), init }); return Promise.resolve(respond(String(url), init)); };
  const container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);
  const unauthorized = [];
  const onUnauthorized = () => unauthorized.push(true);
  const props = { credentials, sessionId: 1, caseId: 'A', onUnauthorized };
  return {
    calls, container, unauthorized,
    render: (extra = {}) => act(async () => { const value = { ...props, ...extra }; root.render(createElement(component, { ...value, key: `${value.sessionId}:${value.caseId}` })); }),
    flush: () => act(async () => { await new Promise((done) => setTimeout(done, 0)); }),
    cleanup: async () => { await act(async () => root.unmount()); container.remove(); globalThis.fetch = previous; },
  };
}

test('pagination reaches subsequent documents, prevents duplicate concurrent reads and preserves records after failure', async () => {
  const next = deferred(); let fail = true;
  const t = mount((url) => url.includes('page=1') ? (fail ? next.promise : page([xlsx], 1)) : page([pdf], 0, true));
  try {
    await t.render();
    await act(async () => { void latest.more(); void latest.more(); });
    assert.equal(t.calls.filter(({ url }) => url.includes('page=1')).length, 1);
    next.resolve(response({ code: 'DOWN' }, 503)); await t.flush();
    assert.deepEqual(latest.list.items.map((item) => item.documentId), ['pdf']);
    assert.ok(latest.error); assert.equal(latest.loadingMore, false);
    fail = false; await act(async () => { await latest.more(); });
    assert.deepEqual(latest.list.items.map((item) => item.documentId), ['pdf', 'xlsx']);
    assert.equal(latest.list.hasNext, false);
  } finally { await t.cleanup(); }
});

test('refresh cancels an in-flight link and permits a new request without accepting its late response', async () => {
  const old = deferred(); let links = 0;
  const t = mount((url) => url.includes('download-url') ? (++links === 1 ? old.promise : link()) : page());
  try {
    await t.render(); await act(async () => { void latest.open('pdf', 'inline'); });
    assert.equal(latest.pending, true);
    await act(async () => latest.refresh());
    assert.equal(latest.pending, false);
    assert.equal(t.calls.find(({ url }) => url.includes('download-url')).init.signal.aborted, true);
    old.resolve(response({ code: 'UNAUTHENTICATED' }, 401)); await t.flush();
    assert.deepEqual(t.unauthorized, []); assert.equal(latest.preview, null);
    await act(async () => { await latest.open('pdf', 'inline'); });
    assert.ok(latest.preview); assert.equal(latest.pending, false);
    assert.equal(t.calls.some(({ url }) => url.startsWith('http://localhost:9000')), false);
  } finally { await t.cleanup(); }
});

test('case changes, logout session changes and unmount discard old successful links and late 401s', async () => {
  for (const change of [{ caseId: 'B' }, { sessionId: 2 }]) {
    const old = deferred();
    const t = mount((url) => url.includes('download-url') ? old.promise : page());
    try {
      await t.render(); await act(async () => { void latest.open('pdf', 'attachment'); });
      await t.render(change);
      old.resolve(link()); await t.flush();
      assert.equal(latest.download, null); assert.equal(latest.pending, false);
      assert.deepEqual(t.unauthorized, []);
    } finally { await t.cleanup(); }
  }
  const old = deferred(); const t = mount((url) => url.includes('download-url') ? old.promise : page());
  await t.render(); await act(async () => { void latest.open('pdf', 'inline'); });
  await t.cleanup(); old.resolve(response({}, 401)); await t.flush();
  assert.deepEqual(t.unauthorized, []);
});

test('expired preview is removed and a new click obtains a new URL; denied links never render', async () => {
  let denied = false;
  const t = mount((url) => url.includes('download-url') ? (denied ? response({ message: 'secret-detail' }, 403) : link(new Date(Date.now() + 80).toISOString())) : page());
  try {
    await t.render(); await act(async () => { await latest.open('pdf', 'inline'); }); assert.ok(latest.preview);
    await act(async () => { await new Promise((done) => setTimeout(done, 120)); }); assert.equal(latest.preview, null);
    await act(async () => { await latest.open('pdf', 'inline'); }); assert.ok(latest.preview);
    denied = true; await act(async () => latest.refresh());
    await act(async () => { await latest.open('pdf', 'inline'); });
    assert.equal(latest.preview, null); assert.match(latest.error, /권한/); assert.doesNotMatch(latest.error, /secret/);
  } finally { await t.cleanup(); }
});

test('the live panel offers PDF preview and attachment for both formats, with a native viewer and fallback', async () => {
  const t = mount((url) => url.includes('download-url') ? link() : page(), OriginalDocuments);
  try {
    await t.render();
    const buttons = [...t.container.querySelectorAll('button')];
    assert.equal(buttons.filter((button) => button.textContent === 'PDF 미리보기').length, 1);
    assert.equal(buttons.filter((button) => button.textContent === '다운로드 링크 받기').length, 2);
    await act(async () => buttons.find((button) => button.textContent === 'PDF 미리보기').click());
    const frame = t.container.querySelector('iframe'); assert.ok(frame); assert.match(frame.title, /청구서.pdf/);
    assert.equal(frame.getAttribute('referrerpolicy'), 'no-referrer');
    assert.match(t.container.textContent, /PDF 새 탭에서 열기/);
    assert.ok(t.calls.some(({ url }) => url.endsWith('disposition=inline')));
    await act(async () => buttons.find((button) => button.textContent === '다운로드 링크 받기').click());
    assert.ok(t.calls.some(({ url }) => url.endsWith('disposition=attachment')));
    assert.ok(t.container.querySelector('a[rel="noopener noreferrer"]'));
  } finally { await t.cleanup(); }
});
