import test from 'node:test';
import assert from 'node:assert/strict';
import { canReadReview, detailTabHref, detailTabs, resolveDetailTab } from '../src/app/cases/[id]/detail-model.ts';

const ids = (roles) => detailTabs(roles).map(([id]) => id);

test('a submitter-only account gets only the submission history tab', () => {
  assert.equal(canReadReview(['SUBMITTER']), false);
  assert.deepEqual(ids(['SUBMITTER']), ['evidence']);
  assert.deepEqual(resolveDetailTab(['SUBMITTER'], null), { tab: 'evidence', unsupported: false });
});

test('an approver or operator gets the reviewer tabs with compare as default', () => {
  for (const role of ['APPROVER', 'OPERATOR']) {
    assert.equal(canReadReview([role]), true);
    assert.deepEqual(ids([role]), ['compare', 'evidence', 'decisions', 'audit']);
    assert.deepEqual(resolveDetailTab([role], null), { tab: 'compare', unsupported: false });
  }
});

test('a multi-role account gets the union of the tabs', () => {
  assert.deepEqual(ids(['SUBMITTER', 'APPROVER']), ['compare', 'evidence', 'decisions', 'audit']);
  assert.deepEqual(ids(['SUBMITTER', 'OPERATOR']), ['compare', 'evidence', 'decisions', 'audit']);
});

test('an allowed explicit tab is honored and defaults otherwise', () => {
  assert.deepEqual(resolveDetailTab(['APPROVER'], 'audit'), { tab: 'audit', unsupported: false });
  assert.deepEqual(resolveDetailTab(['SUBMITTER'], 'evidence'), { tab: 'evidence', unsupported: false });
  assert.deepEqual(resolveDetailTab(['APPROVER'], 'nope'), { tab: 'compare', unsupported: true });
});

test('a restricted tab is unsupported for a submitter, not silently allowed', () => {
  for (const requested of ['compare', 'decisions', 'audit']) {
    assert.deepEqual(resolveDetailTab(['SUBMITTER'], requested), { tab: 'evidence', unsupported: true });
  }
});

test('an account with no roles only sees the submission history', () => {
  assert.deepEqual(ids([]), ['evidence']);
});

test('a tab click sets tab while preserving other query params', () => {
  const next = new URLSearchParams(detailTabHref('from=status%3DREVIEW_PENDING%26page%3D1', 'audit'));
  assert.equal(next.get('tab'), 'audit');
  assert.equal(next.get('from'), 'status=REVIEW_PENDING&page=1');
});

test('an unsupported URL tab becomes valid after clicking an allowed tab', () => {
  // A submitter opening ?tab=audit is told it is not allowed...
  const first = resolveDetailTab(['SUBMITTER'], 'audit');
  assert.deepEqual(first, { tab: 'evidence', unsupported: true });
  // ...and the click writes tab=evidence into the URL, which then resolves as
  // allowed (the warning is a function of the URL, not a sticky local flag).
  const clicked = new URLSearchParams(detailTabHref('tab=audit', 'evidence')).get('tab');
  assert.deepEqual(resolveDetailTab(['SUBMITTER'], clicked), { tab: 'evidence', unsupported: false });
});

test('a role change re-resolves the tab from the URL, never a stale local tab', () => {
  // Approver had ?tab=compare; the submitter-only view must not keep it.
  assert.deepEqual(resolveDetailTab(['SUBMITTER'], 'compare'), { tab: 'evidence', unsupported: true });
  // A default (no tab) always resolves to the role-allowed first tab.
  assert.deepEqual(resolveDetailTab(['SUBMITTER'], null), { tab: 'evidence', unsupported: false });
  assert.deepEqual(resolveDetailTab(['APPROVER'], null), { tab: 'compare', unsupported: false });
});
