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
const { MutationFailureNotice } = await import('../src/app/cases/mutation-failure-notice.tsx');

const { createElement, act } = React;

async function render(node) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  await act(async () => { root.render(node); });
  return {
    text: container.textContent,
    html: container.innerHTML,
    cleanup: async () => { await act(async () => { root.unmount(); }); container.remove(); },
  };
}

test('a conflict notice shows the structured reasons and shortfalls', async () => {
  const failure = {
    kind: 'conflict', status: 409, code: 'STALE_REVIEW_TARGET', message: 'stale target',
    details: { reasons: ['CASE_VERSION', 'MAPPING'], currentCaseVersion: 7, shortfalls: [{ receiptLineId: 'RCL-1', remaining: 5 }] },
  };
  const view = await render(createElement(MutationFailureNotice, { failure }));
  try {
    assert.match(view.text, /최신 자료와 충돌했습니다/);
    assert.match(view.text, /청구 버전이 변경됨/);
    assert.match(view.text, /매핑이 변경됨/);
    assert.match(view.text, /서버 최신 청구서 버전: v7/);
    assert.match(view.text, /RCL-1/);
    assert.match(view.text, /stale target/);
    assert.match(view.text, /STALE_REVIEW_TARGET/);
  } finally {
    await view.cleanup();
  }
});

test('an uncertain notice says it is not auto-resent and offers no retry button', async () => {
  const failure = { kind: 'uncertain', status: 503, code: 'CORE_API_UNAVAILABLE', message: 'down', details: null };
  const view = await render(createElement(MutationFailureNotice, { failure }));
  try {
    assert.match(view.text, /결과를 확인할 수 없습니다/);
    assert.match(view.text, /자동으로 다시 보내지 않습니다/);
    assert.equal(view.html.includes('<button'), false);
  } finally {
    await view.cleanup();
  }
});
