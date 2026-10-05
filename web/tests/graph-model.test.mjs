import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildConfirmation,
  documentDecisionOptions,
  eligibleGraphProposal,
  hasDocumentReview,
  pendingMappingLines,
  presentGraphReason,
} from '../src/app/cases/[id]/graph-model.ts';

const source = (segmentId) => ({ segmentId, start: 0, end: 1 });
const line = (lineNumber, candidates) => ({ lineNumber, source: source(`s${lineNumber}`), reviewRequired: true, warningCodes: [], candidates });
const candidate = (itemId, purchaseOrderLineId) => ({ itemId, purchaseOrderLineId, reason: '', reasonCodes: [], priorSnapshotId: null });
const pending = (lines, reasonCodes) => ({
  interruptId: 'i1', checkpointHash: 'a'.repeat(64), reviewVersion: 2,
  documentStageRef: 'doc-ref', mappingStageRef: 'map-ref', reasonCodes, document: null, mapping: { lines },
});

test('only lines without exactly one candidate need an explicit human decision', () => {
  const p = pending([line(1, [candidate('A', 'p1')]), line(2, []), line(3, [candidate('B', 'p2'), candidate('C', 'p3')])], ['NO_ITEM_CANDIDATE']);
  assert.deepEqual(pendingMappingLines(p).map((l) => l.lineNumber), [2, 3]);
  assert.deepEqual(pendingMappingLines(null), []);
});

test('document decision options follow the server reason codes', () => {
  assert.deepEqual(documentDecisionOptions(['DOCUMENT_REVIEW_REQUIRED']), ['CONFIRMED', 'NEEDS_CORRECTION']);
  assert.deepEqual(documentDecisionOptions(['AMBIGUOUS_ITEM']), ['NOT_REQUIRED']);
  assert.equal(hasDocumentReview(['AMBIGUOUS_ITEM', 'DOCUMENT_REVIEW_REQUIRED']), true);
  assert.equal(presentGraphReason('NO_ITEM_CANDIDATE'), '품목 후보 없음');
  assert.equal(presentGraphReason('UNKNOWN'), 'UNKNOWN');
});

test('confirmation copies the frozen source verbatim and records unresolved as null pairs', () => {
  const p = pending([line(1, [candidate('A', 'p1')]), line(2, []), line(3, [candidate('B', 'p2'), candidate('C', 'p3')])], ['AMBIGUOUS_ITEM']);
  const choices = new Map([[3, { itemId: 'C', purchaseOrderLineId: 'p3' }]]);
  const confirmation = buildConfirmation(p, 'NOT_REQUIRED', choices);
  assert.deepEqual(confirmation, {
    documentStageRef: 'doc-ref',
    mappingStageRef: 'map-ref',
    documentDecision: 'NOT_REQUIRED',
    itemDecisions: [
      { lineNumber: 2, source: source('s2'), itemId: null, purchaseOrderLineId: null },
      { lineNumber: 3, source: source('s3'), itemId: 'C', purchaseOrderLineId: 'p3' },
    ],
  });
  // The source object is the exact frozen reference, not a rebuilt copy.
  assert.equal(confirmation.itemDecisions[0].source, pendingMappingLines(p)[0].source);
});

test('only a current supported completed graph proposal with its hash can be selected', () => {
  const base = () => ({ run: { id: 'g1', status: 'COMPLETED', current: true, supported: true, payloadHash: 'hash1' }, payload: {}, sources: [] });
  assert.deepEqual(eligibleGraphProposal(base()), { proposalId: 'g1', proposalHash: 'hash1' });
  for (const mutate of [
    (v) => { v.run.status = 'WAITING_HUMAN'; },
    (v) => { v.run.current = false; },
    (v) => { v.run.supported = false; },
    (v) => { v.run.payloadHash = null; },
    (v) => { v.payload = null; },
  ]) {
    const view = base(); mutate(view);
    assert.equal(eligibleGraphProposal(view), null);
  }
  assert.equal(eligibleGraphProposal(null), null);
});
