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
    async renderStrict(options = {}) {
      await act(async () => {
        root.render(createElement(React.StrictMode, null, createElement(Harness, { credentials, sessionId: 1, onUnauthorized: () => unauthorized.push('x'), ...options })));
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

test('an unresolved create blocks a different intent until the exact request is retried', async () => {
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
    assert.equal(latest.unresolved.operation, 'create');
    const before = t.calls.length;
    await t.run(() => latest.createOrReuse({ ...header, invoiceNumber: 'INV-EDITED' }));
    assert.equal(t.calls.length, before, 'a different intent must not be sent while unresolved');
    assert.equal(latest.failure.code, 'UNRESOLVED_INTENT');
    await t.run(() => latest.retry());
    assert.equal(latest.caseId, 'case-1');
    assert.equal(latest.unresolved, null);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('an unresolved submit is retried exactly, without re-saving a draft, and edits are blocked', async () => {
  let submitAttempts = 0;
  const t = setup((url, init) => {
    if (init.method === 'POST' && url.endsWith('/api/invoice-cases')) return jsonResponse(201, created);
    if (init.method === 'PUT' && url.endsWith('/draft')) return jsonResponse(200, { ...created, version: 1 });
    if (url.endsWith('/submit')) {
      submitAttempts += 1;
      return submitAttempts === 1
        ? jsonResponse(503, { code: 'CORE_API_UNAVAILABLE', message: 'x' })
        : jsonResponse(200, { caseId: 'case-1', status: 'REVIEW_PENDING', version: 2, evidenceBundle: { version: 1, payloadHash: 'h', submittedAt: 'x' } });
    }
    return jsonResponse(500, { code: 'X', message: 'unexpected' });
  });
  try {
    await t.render();
    await t.run(() => latest.saveDraft(header, lines));
    await t.run(() => latest.submit());
    assert.equal(latest.unresolved.operation, 'submit');
    const submitCalls = t.calls.filter((call) => call.url.endsWith('/submit'));
    const firstSubmitId = submitCalls[0].body.requestId;

    const beforeEdit = t.calls.length;
    await t.run(() => latest.saveDraft(header, [{ ...lines[0], quantity: 9 }]));
    assert.equal(t.calls.length, beforeEdit, 'editing must not send a new draft while a submit is unresolved');

    await t.run(() => latest.retry());
    const submitCalls2 = t.calls.filter((call) => call.url.endsWith('/submit'));
    assert.equal(submitCalls2.length, 2);
    assert.equal(submitCalls2[1].body.requestId, firstSubmitId, 'retry reuses the exact submit request id');
    assert.equal(t.calls.filter((call) => call.url.endsWith('/draft')).length, 1, 'retry must not re-save a draft');
    assert.equal(latest.submittedBundleVersion, 1);
    assert.equal(latest.status, 'submitted', 'a successful retry must reach the completed status so the page routes');
    assert.equal(latest.unresolved, null);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a late create success after a session change is discarded and does not restore the case', async () => {
  let resolveCreate;
  const pending = new Promise((resolve) => { resolveCreate = resolve; });
  const t = setup((url, init) => (init.method === 'POST' && url.endsWith('/api/invoice-cases') ? pending : jsonResponse(500, { code: 'X', message: 'unexpected' })));
  try {
    await t.render({ sessionId: 1 });
    let result = 'unset';
    let promise;
    await act(async () => { promise = latest.createOrReuse(header); promise.then((value) => { result = value; }); });
    await t.render({ sessionId: 2 });
    resolveCreate(jsonResponse(201, created));
    await act(async () => { await promise; });
    assert.equal(result, null);
    assert.equal(latest.caseId, null);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('under React StrictMode the create to submit flow still completes exactly once', async () => {
  const t = setup((url, init) => {
    if (init.method === 'POST' && url.endsWith('/api/invoice-cases')) return jsonResponse(201, created);
    if (init.method === 'PUT' && url.endsWith('/draft')) return jsonResponse(200, { ...created, version: 1 });
    if (url.endsWith('/submit')) return jsonResponse(200, { caseId: 'case-1', status: 'REVIEW_PENDING', version: 2, evidenceBundle: { version: 1, payloadHash: 'h', submittedAt: 'x' } });
    return jsonResponse(500, { code: 'X', message: 'unexpected' });
  });
  try {
    await t.renderStrict();
    await t.run(() => latest.saveDraft(header, lines));
    assert.equal(latest.caseId, 'case-1');
    await t.run(() => latest.submit());
    assert.equal(latest.submittedBundleVersion, 1);
    assert.equal(t.calls.filter((call) => call.url.endsWith('/draft')).length, 1);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a server re-read of an unresolved write keeps the frozen intent and does not change version', async () => {
  let draftAttempts = 0;
  const t = setup((url, init) => {
    if (init.method === 'POST' && url.endsWith('/api/invoice-cases')) return jsonResponse(201, created);
    if (init.method === 'PUT' && url.endsWith('/draft')) {
      draftAttempts += 1;
      return draftAttempts === 1 ? jsonResponse(503, { code: 'CORE_API_UNAVAILABLE', message: 'x' }) : jsonResponse(200, { ...created, version: 1 });
    }
    return jsonResponse(500, { code: 'X', message: 'unexpected' });
  });
  try {
    await t.render();
    await t.run(() => latest.saveDraft(header, lines));
    assert.equal(latest.unresolved.operation, 'draft');
    const versionBefore = latest.caseVersion;
    // A server read claiming a different version must not change identity/version
    // or clear the frozen request.
    await t.run(() => latest.adoptLatest('case-1', 99));
    assert.equal(latest.caseVersion, versionBefore);
    assert.equal(latest.unresolved.operation, 'draft');
    assert.match(latest.notice, /같은 요청을 다시 시도/);

    const before = t.calls.length;
    await t.run(() => latest.saveDraft(header, [{ ...lines[0], quantity: 9 }]));
    assert.equal(t.calls.length, before, 'a changed input must remain blocked while unresolved');

    const firstDraftId = t.calls.filter((call) => call.url.endsWith('/draft'))[0].body.requestId;
    await t.run(() => latest.retry());
    const drafts = t.calls.filter((call) => call.url.endsWith('/draft'));
    assert.equal(drafts.length, 2);
    assert.equal(drafts[1].body.requestId, firstDraftId, 'the retry must reuse the exact draft request id');
    assert.equal(drafts[1].body.expectedCaseVersion, versionBefore);
    assert.equal(latest.status, 'draft-saved');
    assert.equal(latest.unresolved, null);
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('a retried supplement revision marks the revision opened so a later save does not open a new one', async () => {
  let revisionAttempts = 0;
  const t = setup((url, init) => {
    if (init.method === 'POST' && url.endsWith('/revisions')) {
      revisionAttempts += 1;
      return revisionAttempts === 1
        ? jsonResponse(503, { code: 'CORE_API_UNAVAILABLE', message: 'x' })
        : jsonResponse(201, { ...created, version: 1 });
    }
    if (init.method === 'PUT' && url.endsWith('/draft')) return jsonResponse(200, { ...created, version: 2 });
    return jsonResponse(500, { code: 'X', message: 'unexpected' });
  });
  try {
    await t.render();
    await t.run(() => latest.openRevision('case-1', 0));
    assert.equal(latest.unresolved.operation, 'revision');
    assert.equal(latest.revisionOpened, false);

    await t.run(() => latest.retry());
    assert.equal(latest.revisionOpened, true, 'a successful retried revision is opened');
    assert.equal(latest.caseVersion, 1);
    assert.equal(latest.unresolved, null);

    // The page's ensureRevision now skips opening again; a save only replaces the draft.
    await t.run(() => latest.saveDraft(header, lines));
    const revisions = t.calls.filter((call) => call.url.endsWith('/revisions'));
    assert.equal(revisions.length, 2, 'no new revision may be opened after the retried one');
    assert.equal(revisions[1].body.requestId, revisions[0].body.requestId, 'the retried revision reused its request id');
    assert.equal(t.calls.filter((call) => call.url.endsWith('/draft')).length, 1);
    assert.equal(latest.status, 'draft-saved');
  } finally {
    await t.unmount();
    t.restore();
  }
});

test('retry after a session change cannot open a revision for another view', async () => {
  const t = setup(() => jsonResponse(503, { code: 'CORE_API_UNAVAILABLE', message: 'x' }));
  try {
    await t.render({ sessionId: 1 });
    await t.run(() => latest.openRevision('case-1', 0));
    assert.equal(latest.unresolved.operation, 'revision');
    await t.render({ sessionId: 2 });
    const before = t.calls.length;
    let result = true;
    await t.run(async () => { result = await latest.retry(); });
    assert.equal(result, false);
    assert.equal(t.calls.length, before, 'a late retry must not send a revision for the replaced view');
  } finally {
    await t.unmount();
    t.restore();
  }
});
