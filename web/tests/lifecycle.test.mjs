import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

// Real React DOM mount/effect/cleanup regression: the hook is driven through a
// live root with manual deferred responses, which is what the component does in
// the browser. The Generation class alone is not enough.
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
const { useInvoiceCases } = await import('../src/app/cases/use-invoice-cases.ts');
const { AuthProvider, useAuth } = await import('../src/app/auth.tsx');

const { createElement, act } = React;

let latest = null;
function Harness(props) {
  latest = useInvoiceCases(props);
  return null;
}

const baseFilters = {
  status: 'all', supplierId: null, submittedBy: null,
  submittedFrom: null, submittedTo: null,
  searchField: 'invoiceNumber', searchValue: null, page: 0, size: 20,
};

function deferred() {
  let resolve;
  const promise = new Promise((res) => { resolve = res; });
  return { promise, resolve };
}

function jsonResponse(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

function pageOf(id) {
  return {
    items: [{ id, supplierId: 'SUP-1', purchaseOrderId: 'PO-1', invoiceNumber: `INV-${id}`, submittedBy: 'u', status: 'DRAFT', version: 1, createdAt: 'x', updatedAt: 'x', submittedAt: null }],
    page: 0, size: 20, totalItems: 1, totalPages: 1, hasNext: false,
  };
}

function setup() {
  const originalFetch = globalThis.fetch;
  const pending = [];
  globalThis.fetch = () => { const d = deferred(); pending.push(d); return d.promise; };
  const unauthorized = [];
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  const render = (options) => act(async () => { root.render(createElement(Harness, options)); });
  const settle = (d, response) => act(async () => { d.resolve(response); await Promise.resolve(); await Promise.resolve(); });
  return {
    pending,
    unauthorized,
    render,
    settle,
    async unmount() { await act(async () => { root.unmount(); }); },
    restore() { globalThis.fetch = originalFetch; container.remove(); },
  };
}

const sessionA = { credentials: { username: 'A', password: 'p' }, sessionId: 1, filters: baseFilters, reloadToken: 0 };
const sessionB = { credentials: { username: 'B', password: 'p' }, sessionId: 3, filters: baseFilters, reloadToken: 0 };

test('a current 401 still triggers the unauthorized handler', async () => {
  const t = setup();
  try {
    await t.render({ ...sessionA, onUnauthorized: () => t.unauthorized.push('a') });
    assert.equal(t.pending.length, 1);
    await t.settle(t.pending[0], jsonResponse(401, { code: 'UNAUTHENTICATED', message: 'no' }));
    assert.deepEqual(t.unauthorized, ['a']);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a stale 401 from a logged-out session never signs the new session out', async () => {
  const t = setup();
  try {
    await t.render({ ...sessionA, onUnauthorized: () => t.unauthorized.push('a') });
    // logout
    await t.render({ credentials: null, sessionId: 2, filters: baseFilters, reloadToken: 0, onUnauthorized: () => t.unauthorized.push('null') });
    // new login
    await t.render({ ...sessionB, onUnauthorized: () => t.unauthorized.push('b') });
    assert.equal(t.pending.length, 2);
    // the old request finally resolves as 401
    await t.settle(t.pending[0], jsonResponse(401, { code: 'UNAUTHENTICATED', message: 'no' }));
    assert.deepEqual(t.unauthorized, []);
    // the new session loads normally
    await t.settle(t.pending[1], jsonResponse(200, pageOf('B')));
    assert.equal(latest.page.items[0].id, 'B');
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('an old session success cannot overwrite the new session rows', async () => {
  const t = setup();
  try {
    await t.render({ ...sessionA, onUnauthorized: () => t.unauthorized.push('a') });
    await t.render({ ...sessionB, onUnauthorized: () => t.unauthorized.push('b') });
    await t.settle(t.pending[1], jsonResponse(200, pageOf('B')));
    assert.equal(latest.page.items[0].id, 'B');
    await t.settle(t.pending[0], jsonResponse(200, pageOf('A')));
    assert.equal(latest.page.items[0].id, 'B');
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a response that resolves after unmount is ignored', async () => {
  const t = setup();
  try {
    await t.render({ ...sessionA, onUnauthorized: () => t.unauthorized.push('a') });
    await t.unmount();
    await t.settle(t.pending[0], jsonResponse(401, { code: 'UNAUTHENTICATED', message: 'no' }));
    assert.deepEqual(t.unauthorized, []);
  } finally {
    t.restore();
  }
});

function abortError() {
  return Object.assign(new Error('aborted'), { name: 'AbortError' });
}

function setupAuth() {
  const originalFetch = globalThis.fetch;
  const pending = [];
  globalThis.fetch = (_url, init) => new Promise((resolve, reject) => {
    const d = deferred();
    pending.push({ resolve: (response) => d.resolve(response) });
    if (init?.signal) {
      if (init.signal.aborted) reject(abortError());
      else init.signal.addEventListener('abort', () => reject(abortError()), { once: true });
    }
    d.promise.then(resolve, reject);
  });
  let auth = null;
  function Consumer() { auth = useAuth(); return null; }
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  return {
    pending,
    get auth() { return auth; },
    async mount() { await act(async () => { root.render(createElement(AuthProvider, null, createElement(Consumer))); }); },
    async unmount() { await act(async () => { root.unmount(); }); },
    restore() { globalThis.fetch = originalFetch; container.remove(); },
  };
}

test('leaving the login screen cancels its own pending attempt', async () => {
  const t = setupAuth();
  try {
    await t.mount();
    const controller = new AbortController();
    let loginResult = null;
    await act(async () => {
      t.auth.login({ username: 'A', password: 'p' }, controller.signal).then((result) => { loginResult = result; });
    });
    assert.equal(t.pending.length, 1);
    await act(async () => { controller.abort(); await Promise.resolve(); await Promise.resolve(); });
    assert.equal(t.auth.isAuthenticated, false);
    assert.equal(loginResult, false);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a logout during a pending login prevents the late success from authenticating', async () => {
  const t = setupAuth();
  try {
    await t.mount();
    let loginResult = null;
    await act(async () => {
      t.auth.login({ username: 'A', password: 'p' }).then((result) => { loginResult = result; });
    });
    const request = t.pending[0];
    await act(async () => { t.auth.logout(); });
    await act(async () => {
      request.resolve(jsonResponse(200, { username: 'A', roles: ['SUBMITTER'] }));
      await Promise.resolve();
      await Promise.resolve();
    });
    assert.equal(t.auth.isAuthenticated, false);
    assert.equal(loginResult, false);
  } finally {
    await t.unmount();
    t.restore();
  }
});
