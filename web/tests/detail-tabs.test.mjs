import test from 'node:test';
import assert from 'node:assert/strict';
import { canReadReview, detailTabs, resolveDetailTab } from '../src/app/cases/[id]/detail-model.ts';

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
