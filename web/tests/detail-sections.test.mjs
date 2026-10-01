import test from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

// Direct React DOM render of the live detail sections with server-shaped
// fixtures: an old comparison, a 503/403 hand-off and evidence read, and a
// failed audit page must render their own state instead of a "no data" or
// "not yet approved" message.
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
for (const key of Object.getOwnPropertyNames(dom.window)) {
  if (!(key in globalThis)) {
    try {
      globalThis[key] = dom.window[key];
    } catch {
      // accessor-only jsdom property
    }
  }
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const React = await import('react');
const { createRoot } = await import('react-dom/client');
const { ComparePanel, EvidencePanel, HandoffStrip, HandoffSummary, AuditPanel } = await import('../src/app/cases/[id]/detail-sections.tsx');

const { createElement, act } = React;

function renderText(element) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => { root.render(element); });
  const text = container.textContent ?? '';
  act(() => { root.unmount(); });
  container.remove();
  return text;
}

function bundle(version, hash) {
  return { version, payloadHash: hash, submittedAt: '2026-09-30T01:00:00Z' };
}

function matchPayload(bundleVersion, bundleHash, normal) {
  return {
    evidenceBundle: { id: `b-${bundleVersion}`, version: bundleVersion, payloadHash: bundleHash },
    lineOutcomes: [],
    exceptions: [],
    normal,
  };
}

function match(bundleVersion, bundleHash, normal = true) {
  return { resultNumber: 1, resultHash: 'result-hash', payload: matchPayload(bundleVersion, bundleHash, normal) };
}

function baseData(overrides = {}) {
  return {
    detail: { id: 'c1', supplierId: 'SUP-1', purchaseOrderId: 'PO-1', invoiceNumber: 'INV-1', submittedBy: 'submitter', status: 'REVIEW_PENDING', version: 2, currentRevision: null, lines: [] },
    bundles: { status: 'ready', data: [bundle(2, 'h2')] },
    sealed: { status: 'empty' },
    handoff: { status: 'ready', data: { invoiceCaseId: 'c1', caseStatus: 'REVIEW_PENDING', caseVersion: 2, payment: null } },
    match: { status: 'ready', data: match(2, 'h2') },
    snapshot: { status: 'empty' },
    freshness: { status: 'empty' },
    decisions: { status: 'empty' },
    audit: { status: 'ready', data: { entries: [], nextCursor: 'cursor-1' } },
    canReadReview: true,
    ...overrides,
  };
}

test('a comparison computed against an older bundle is labelled as an old result', () => {
  const text = renderText(createElement(ComparePanel, { data: baseData({ match: { status: 'ready', data: match(1, 'h1') } }) }));
  assert.match(text, /이전 제출자료 기준 비교 결과/);
  assert.match(text, /오래된 결과/);
  assert.match(text, /당시 자료 서버판정: 정상/);
});

test('a comparison of the current bundle is not labelled old', () => {
  const text = renderText(createElement(ComparePanel, { data: baseData() }));
  assert.equal(text.includes('이전 제출자료 기준 비교 결과'), false);
  assert.match(text, /당시 자료 서버판정: 정상/);
});

test('a hand-off error is not shown as a pre-approval state', () => {
  const text = renderText(createElement(HandoffStrip, { handoff: { status: 'error', message: 'boom' } }));
  assert.match(text, /ERP 인계 상태를 불러오지 못했습니다/);
  assert.equal(text.includes('아직 승인·인계 전'), false);

  const summary = renderText(createElement(HandoffSummary, { handoff: { status: 'error', message: 'boom' }, payment: null }));
  assert.match(summary, /인계 상태 확인 필요/);
  assert.equal(summary.includes('금액 미확정'), false);
});

test('a hand-off 403 is shown as a permission result, not empty', () => {
  const text = renderText(createElement(HandoffStrip, { handoff: { status: 'forbidden' } }));
  assert.match(text, /ERP 인계 상태를 조회할 권한이 없습니다/);
  const summary = renderText(createElement(HandoffSummary, { handoff: { status: 'forbidden' }, payment: null }));
  assert.match(summary, /인계 상태 확인 필요/);

  const evidence = renderText(createElement(EvidencePanel, { data: baseData({ bundles: { status: 'forbidden' } }) }));
  assert.match(evidence, /증빙 목록을 조회할 권한이 없습니다/);
  assert.match(evidence, /제출 이력 권한 없음/);
  assert.equal(evidence.includes('제출된 이력 없음'), false);
});

test('a failed audit page keeps the records and shows a scoped retry', () => {
  const entry = { id: 'a1', invoiceCaseId: 'c1', occurredAt: '2026-09-30T01:00:00Z', actor: 'approver', actorRoles: ['APPROVER'], action: 'MATCH_RUN', targetType: 'CASE', targetId: 'c1', businessVersion: 2, before: null, after: { ok: true }, requestId: 'r1', traceId: 't1' };
  const data = baseData({ audit: { status: 'ready', data: { entries: [entry], nextCursor: 'cursor-1' } } });
  const text = renderText(createElement(AuditPanel, {
    data,
    entries: [entry],
    nextCursor: 'cursor-1',
    loadingMore: false,
    error: { key: 'k', kind: 'error', message: 'page failed' },
    onMore: () => {},
  }));
  assert.match(text, /감사 기록을 더 불러오지 못했습니다/);
  assert.match(text, /이전 기록 다시 시도/);
  assert.match(text, /비교 결과 생성/);
  assert.equal(text.includes('아직 감사 기록이 없습니다'), false);
});
