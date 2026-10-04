import test from 'node:test';
import assert from 'node:assert/strict';
import { eligibleProposal, exactFact, sourceQuote, frozenProposal } from '../src/app/cases/[id]/proposal-model.ts';

const completed = () => ({ run: { id: 'p1', status: 'COMPLETED', current: true, payloadHash: 'hash1' }, payload: {}, sources: [] });

test('only a current completed proposal with its immutable result can be selected', () => {
  assert.deepEqual(eligibleProposal(completed()), { proposalId: 'p1', proposalHash: 'hash1' });
  for (const status of ['QUEUED', 'RUNNING', 'FAILED', 'STALE']) {
    const view = completed(); view.run.status = status;
    assert.equal(eligibleProposal(view), null);
  }
  const stale = completed(); stale.run.current = false;
  assert.equal(eligibleProposal(stale), null);
  const missing = completed(); missing.payload = null;
  assert.equal(eligibleProposal(missing), null);
  const unhashed = completed(); unhashed.run.payloadHash = null;
  assert.equal(eligibleProposal(unhashed), null);
  assert.equal(eligibleProposal(null), null);
});

test('server monetary facts preserve Java long values beyond JavaScript safe integers', () => {
  assert.equal(exactFact('9223372036854775807'), '9,223,372,036,854,775,807');
  assert.equal(exactFact('9007199254740993'), '9,007,199,254,740,993');
  for (const value of ['NaN', '-1', '1e4', '1.1', '', '9'.repeat(20)]) {
    assert.equal(exactFact(value), '표시할 수 없는 수치');
  }
});

test('source spans use code points and exact frozen locations instead of UTF-16 offsets', () => {
  const view = completed();
  view.sources = [{ id: 's1', documentId: 'd1', text: '가😀나', origin: 'ocr', page: 2, sheet: null, cell: null },
    { id: 's2', documentId: 'd2', text: '품목', origin: 'parser', page: null, sheet: 2, cell: 'B3' }];
  assert.deepEqual(sourceQuote(view, { segmentId: 's1', start: 1, end: 2 }), { quote: '😀', location: '문서 d1 · 2쪽 (OCR)' });
  assert.deepEqual(sourceQuote(view, { segmentId: 's2', start: 0, end: 2 }), { quote: '품목', location: '문서 d2 · 시트 2 · 셀 B3' });
  for (const source of [{ segmentId: 'unknown', start: 0, end: 1 }, { segmentId: 's1', start: -1, end: 1 },
    { segmentId: 's1', start: 0, end: 4 }, { segmentId: 's1', start: 1, end: 1 }, { segmentId: 's1', start: 0.5, end: 2 }]) {
    assert.equal(sourceQuote(view, source), null);
  }
});

test('frozen reference uses the snapshot result hash and tolerates legacy snapshots', () => {
  assert.deepEqual(frozenProposal({ proposal: { id: 'frozen', payloadHash: 'old-hash', contextHash: 'context' } }),
    { proposalId: 'frozen', proposalHash: 'old-hash' });
  for (const payload of [null, {}, { proposal: null }, { proposal: { id: 'x', payloadHash: 1 } }]) assert.equal(frozenProposal(payload), null);
});
