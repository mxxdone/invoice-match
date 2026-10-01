import test from 'node:test';
import assert from 'node:assert/strict';
// (binding tests live at the end of this file)
import {
  claimLines,
  comparisonRows,
  decisionDetail,
  decisionSubjectPayload,
  EXACT_RANGE_MESSAGE,
  formatExactInteger,
  freshnessVerdict,
  isStaleMatch,
  latestBundle,
  parseEvidencePayload,
  presentAuditAction,
  presentDecision,
  presentFreshnessReason,
  presentLineStatus,
  presentMatchException,
  presentOutboxStatus,
  presentPaymentStatus,
  reviewSubjectBinding,
  safeJsonStringify,
  UNSAFE_NUMBER_MARKER,
} from '../src/app/cases/[id]/detail-model.ts';

function matchOf(id, bundleId, bundleVersion, bundleHash, snapVersion, snapHash, { resultNumber = 1, resultHash = 'r', mappingWatermark = 0 } = {}) {
  return {
    id, invoiceCaseId: 'c1', evidenceBundleId: bundleId, resultNumber, resultHash,
    purchasingSnapshotVersion: snapVersion, purchasingSnapshotHash: snapHash, mappingWatermark,
    payload: {
      evidenceBundle: { id: bundleId, version: bundleVersion, payloadHash: bundleHash },
      purchasingSnapshot: { snapshotVersion: snapVersion, payloadHash: snapHash },
    },
    createdAt: 'x',
  };
}
function snapshotOf(id, matchId, bundleId, bundleVersion, bundleHash, snapVersion, snapHash, { payloadHash = 'snap-hash', resultNumber = 1, resultHash = 'r', mappingWatermark = 0 } = {}) {
  return {
    id, invoiceCaseId: 'c1', snapshotNumber: 1, evidenceBundleId: bundleId, evidenceBundleVersion: bundleVersion,
    matchResultId: matchId, matchResultNumber: resultNumber, targetCaseVersion: 3,
    purchasingSnapshotVersion: snapVersion, purchasingSnapshotHash: snapHash, mappingWatermark,
    payloadHash,
    payload: {
      evidenceBundle: { id: bundleId, version: bundleVersion, payloadHash: bundleHash },
      matchResult: { id: matchId, resultNumber, resultHash, mappingWatermark },
      purchasingSnapshot: { snapshotVersion: snapVersion, payloadHash: snapHash },
    },
    createdAt: 'x',
  };
}

const detail = (lines) => ({
  id: 'c1', supplierId: 'SUP-1', purchaseOrderId: 'PO-1', invoiceNumber: 'INV-1',
  submittedBy: 'submitter', status: 'REVIEW_PENDING', version: 3,
  currentRevision: null, lines,
});
const bundled = (lines) => ({
  version: 1, payloadHash: 'hash-1', payload: JSON.stringify({ lines }), submittedAt: 'x',
});

test('the sealed evidence payload is parsed and a malformed payload yields null', () => {
  const parsed = parseEvidencePayload(JSON.stringify({ lines: [{ lineNumber: 1, rawItemName: 'Paper', quantity: 5, unitPrice: 2500, confirmedItemId: null }] }));
  assert.equal(parsed.lines.length, 1);
  assert.equal(parsed.lines[0].quantity, 5);
  assert.equal(parseEvidencePayload('not json'), null);
  assert.equal(parseEvidencePayload(JSON.stringify({ lines: 'nope' })), null);
});

test('claim lines prefer the open draft and fall back to the sealed bundle without merging', () => {
  const draftLine = { lineNumber: 1, rawItemName: 'Draft', quantity: 2, unitPrice: 100, confirmedItemId: null };
  const sealedLine = { lineNumber: 1, rawItemName: 'Sealed', quantity: 9, unitPrice: 900, confirmedItemId: 'ITEM-1' };
  const fromDraft = claimLines(detail([draftLine]), bundled([sealedLine]));
  assert.equal(fromDraft.source, 'draft');
  assert.equal(fromDraft.lines[0].rawItemName, 'Draft');
  const fromSealed = claimLines(detail([]), bundled([sealedLine]));
  assert.equal(fromSealed.source, 'evidence');
  assert.equal(fromSealed.lines[0].rawItemName, 'Sealed');
  assert.deepEqual(claimLines(detail([]), null), { source: 'none', lines: [] });
});

test('comparison rows keep a missing purchase order line missing instead of inventing values', () => {
  const payload = {
    lineOutcomes: [
      {
        lineNumber: 1, rawItemName: 'Paper', confirmedItemId: 'ITEM-1', status: 'MATCHED',
        candidatePoLineIds: ['POL-1'],
        purchaseOrderLine: { purchaseOrderLineId: 'POL-1', itemId: 'ITEM-1', orderedQuantity: 100, unitPrice: 24500 },
        invoiceQuantity: 100, invoiceUnitPrice: 24500, availableConfirmedQuantity: 60, plannedQuantity: 60,
        expectedAllocationPlan: [], exceptions: [{ type: 'QUANTITY_EXCEEDS_RECEIPT_BALANCE', lineNumber: 1, details: {} }],
      },
      {
        lineNumber: 2, rawItemName: 'Toner', confirmedItemId: null, status: 'ITEM_UNCONFIRMED',
        candidatePoLineIds: [],
        purchaseOrderLine: null,
        invoiceQuantity: 12, invoiceUnitPrice: 68000, availableConfirmedQuantity: 0, plannedQuantity: 0,
        expectedAllocationPlan: [], exceptions: [{ type: 'ITEM_UNCONFIRMED', lineNumber: 2, details: {} }],
      },
    ],
  };
  const rows = comparisonRows({ payload });
  assert.equal(rows[0].orderedQuantity, 100);
  assert.equal(rows[0].poUnitPrice, 24500);
  assert.deepEqual(rows[0].issues, [{ type: 'QUANTITY_EXCEEDS_RECEIPT_BALANCE', label: '검수 잔량 초과' }]);
  assert.equal(rows[1].hasPurchaseOrderLine, false);
  assert.equal(rows[1].orderedQuantity, null);
  assert.equal(rows[1].poUnitPrice, null);
  assert.equal(rows[1].issues[0].label, '품목 매핑 미확정');
});

test('presentation maps label known enum values and fall back to the raw value', () => {
  assert.equal(presentMatchException('UNIT_PRICE_MISMATCH'), '단가 불일치');
  assert.equal(presentMatchException('SOMETHING_NEW'), 'SOMETHING_NEW');
  assert.equal(presentLineStatus('EVIDENCE_INSUFFICIENT'), '근거 부족');
  assert.equal(presentDecision('SUPPLEMENT_REQUESTED'), '보완 요청');
  assert.equal(presentFreshnessReason('SUPERSEDED'), '더 새로운 검토 대상이 있음');
  assert.equal(presentPaymentStatus('RESULT_UNKNOWN'), '결과 불명');
  assert.equal(presentOutboxStatus('DELIVERED'), '전달됨');
  assert.equal(presentAuditAction('APPROVE'), '승인');
  assert.equal(presentAuditAction('UNKNOWN_ACTION'), 'UNKNOWN_ACTION');
});

test('freshness and decision detail use server values without re-judging', () => {
  assert.equal(freshnessVerdict({ current: true }), '현재 자료와 일치');
  assert.equal(freshnessVerdict({ current: false }), '현재 자료와 불일치');
  assert.equal(decisionDetail({ decision: 'MAPPING', mappingLineNumber: 2, mappingItemId: 'ITEM-1', mappingPoLineId: 'POL-1', reason: null }), '라인 2 · ITEM-1 / POL-1');
  assert.equal(decisionDetail({ decision: 'REJECTED', reason: '중복 청구', mappingLineNumber: null, mappingItemId: null, mappingPoLineId: null }), '중복 청구');
});

test('the latest bundle is the greatest version', () => {
  assert.equal(latestBundle([]), null);
  assert.equal(latestBundle([{ version: 1 }, { version: 3 }, { version: 2 }]).version, 3);
});

test('a match computed against an older bundle is stale, and a matching hash is not', () => {
  const match = (version, hash) => ({ payload: { evidenceBundle: { version, payloadHash: hash } } });
  assert.equal(isStaleMatch(match(1, 'h1'), 2, 'h2'), true);
  assert.equal(isStaleMatch(match(2, 'h1'), 2, 'h2'), true);
  assert.equal(isStaleMatch(match(2, 'h2'), 2, 'h2'), false);
  assert.equal(isStaleMatch(match(2, 'h2'), 2, null), false);
  assert.equal(isStaleMatch(match(1, 'h1'), null, null), false);
});

test('monetary values beyond the exact integer range are never formatted as exact', () => {
  assert.equal(formatExactInteger(Number.MAX_SAFE_INTEGER), new Intl.NumberFormat('ko-KR').format(Number.MAX_SAFE_INTEGER));
  assert.equal(formatExactInteger(Number.MAX_SAFE_INTEGER + 1), EXACT_RANGE_MESSAGE);
  assert.equal(formatExactInteger(Number.MAX_SAFE_INTEGER + 2), EXACT_RANGE_MESSAGE);
  assert.equal(formatExactInteger(1.5), EXACT_RANGE_MESSAGE);
});

test('unsafe numbers in audit JSON are redacted recursively, never printed as exact', () => {
  const text = safeJsonStringify({
    amount: Number.MAX_SAFE_INTEGER,
    nested: { big: Number.MAX_SAFE_INTEGER + 1, list: [Number.MAX_SAFE_INTEGER + 2, 5] },
  });
  // The exact safe value is preserved.
  assert.match(text, new RegExp(`"amount":${Number.MAX_SAFE_INTEGER}\\b`));
  // The unsafe values are replaced by the marker, not printed.
  assert.equal(text.includes(String(Number.MAX_SAFE_INTEGER + 1)), false);
  assert.equal(text.includes(String(Number.MAX_SAFE_INTEGER + 2)), false);
  assert.equal(text.split(UNSAFE_NUMBER_MARKER).length - 1, 2);
  assert.match(text, /"list":\[".*",5\]/);
});

test('audit action labels reuse the confirmed UI phrasing without a new domain term', () => {
  assert.equal(presentAuditAction('CASE_CREATED'), '청구서 생성');
  assert.equal(presentAuditAction('SUPPLEMENT_REVISION_OPENED'), '보완 작성 시작');
  assert.equal(presentAuditAction('MATCH_RUN'), '비교 결과 생성');
});

function consistent(snapshotOverrides = {}, matchOverrides = {}) {
  const match = matchOf('match-1', 'bundle-1', 2, 'b-hash', 5, 'ps-hash', matchOverrides);
  const snapshot = snapshotOf('snap-1', 'match-1', 'bundle-1', 2, 'b-hash', 5, 'ps-hash', snapshotOverrides);
  return { snapshot, match };
}

test('a decision subject is bound only when the displayed comparison is the frozen snapshots own facts', () => {
  const { snapshot, match } = consistent();
  const bound = reviewSubjectBinding({ snapshot, freshnessCurrent: true, match, currentBundleVersion: 2, currentBundleHash: 'b-hash' });
  assert.equal(bound.bound, true);
  assert.deepEqual(bound.reasons, []);
});

test('a null current bundle hash fails closed', () => {
  const { snapshot, match } = consistent();
  const result = reviewSubjectBinding({ snapshot, freshnessCurrent: true, match, currentBundleVersion: 2, currentBundleHash: null });
  assert.equal(result.bound, false);
  assert.ok(result.reasons.some((reason) => reason.includes('지문을 확인할 수 없습니다')));
});

test('a B snapshot with A display facts is not bound', () => {
  // Snapshot points at match-B, but the screen is showing match-A.
  const match = matchOf('match-A', 'bundle-B', 2, 'b-hash', 5, 'ps-hash');
  const snapshot = snapshotOf('snap-B', 'match-B', 'bundle-B', 2, 'b-hash', 5, 'ps-hash');
  const result = reviewSubjectBinding({ snapshot, freshnessCurrent: true, match, currentBundleVersion: 2, currentBundleHash: 'b-hash' });
  assert.equal(result.bound, false);
  assert.ok(result.reasons.some((reason) => reason.includes('비교 결과')));
});

test('a snapshot whose own frozen payload disagrees with the display is not bound', () => {
  const { match } = consistent();
  const wrongResultHash = consistent({ resultHash: 'different' }).snapshot;
  assert.equal(reviewSubjectBinding({ snapshot: wrongResultHash, freshnessCurrent: true, match, currentBundleVersion: 2, currentBundleHash: 'b-hash' }).bound, false);
  const wrongWatermark = consistent({ mappingWatermark: 9 }).snapshot;
  assert.equal(reviewSubjectBinding({ snapshot: wrongWatermark, freshnessCurrent: true, match, currentBundleVersion: 2, currentBundleHash: 'b-hash' }).bound, false);
  const missingPayload = { ...consistent().snapshot, payload: {} };
  assert.equal(reviewSubjectBinding({ snapshot: missingPayload, freshnessCurrent: true, match, currentBundleVersion: 2, currentBundleHash: 'b-hash' }).bound, false);
});

test('a current flag is not enough: evidence and purchasing facts must match the snapshot', () => {
  const { snapshot } = consistent();
  const wrongBundle = matchOf('match-1', 'bundle-1', 3, 'b-hash', 5, 'ps-hash');
  assert.equal(reviewSubjectBinding({ snapshot, freshnessCurrent: true, match: wrongBundle, currentBundleVersion: 3, currentBundleHash: 'b-hash' }).bound, false);
  const wrongPurchasing = matchOf('match-1', 'bundle-1', 2, 'b-hash', 9, 'ps-hash');
  assert.equal(reviewSubjectBinding({ snapshot, freshnessCurrent: true, match: wrongPurchasing, currentBundleVersion: 2, currentBundleHash: 'b-hash' }).bound, false);
  assert.equal(reviewSubjectBinding({ snapshot, freshnessCurrent: false, match: consistent().match, currentBundleVersion: 2, currentBundleHash: 'b-hash' }).bound, false);
  assert.equal(reviewSubjectBinding({ snapshot, freshnessCurrent: true, match: null, currentBundleVersion: 2, currentBundleHash: 'b-hash' }).bound, false);
  assert.equal(reviewSubjectBinding({ snapshot: null, freshnessCurrent: true, match: null, currentBundleVersion: null, currentBundleHash: null }).bound, false);
});

test('the decision payload always uses the frozen snapshot, not the displayed match', () => {
  const snapshotB = snapshotOf('snap-B', 'match-B', 'bundle-B', 2, 'b-hash', 5, 'ps-hash', { payloadHash: 'hash-B' });
  const payload = decisionSubjectPayload(7, snapshotB);
  assert.deepEqual(payload, { expectedCaseVersion: 7, reviewSnapshotId: 'snap-B', reviewPayloadHash: 'hash-B' });
});
