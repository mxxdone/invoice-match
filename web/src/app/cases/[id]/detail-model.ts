// Pure live-detail adapters.
//
// Every value here comes from a server DTO field. A missing field stays missing
// ("—" or an explicit "아직 없습니다") rather than being filled from a design
// fixture, and no server calculation (match verdict, freshness, totals) is
// re-derived in the browser.

import type {
  EvidenceBundleDetail,
  EvidenceBundleSummary,
  InvoiceCaseDetail,
  InvoiceLineDetail,
  MatchResultView,
  PlannedAllocation,
  ReviewDecisionView,
  ReviewFreshness,
  ReviewSnapshotView,
} from '../../api/contract';

const numberFormat = new Intl.NumberFormat('ko-KR');
export const formatNumber = (value: number) => numberFormat.format(value);

// Monetary server fields are Java `long`. A value above 2^53-1 cannot be held
// exactly by a JavaScript number, so it is never formatted or compared as if it
// were exact; the screen states that the value is outside the supported exact
// range instead. Replacing the wire type with an exact integer/string contract
// is a backend decision and is left to the Head.
export const EXACT_RANGE_MESSAGE = '지원 범위 초과 (정확 표시 불가)';
export const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;

export function isExactInteger(value: number): boolean {
  return Number.isInteger(value) && Number.isSafeInteger(value);
}

export function formatExactInteger(value: number): string {
  return isExactInteger(value) ? numberFormat.format(value) : EXACT_RANGE_MESSAGE;
}

export type ComparisonRow = {
  lineNumber: number;
  rawItemName: string;
  confirmedItemId: string | null;
  status: string;
  hasPurchaseOrderLine: boolean;
  orderedQuantity: number | null;
  availableConfirmedQuantity: number;
  plannedQuantity: number;
  invoiceQuantity: number;
  invoiceUnitPrice: number;
  poUnitPrice: number | null;
  plannedAllocations: PlannedAllocation[];
  issues: Array<{ type: string; label: string }>;
};

export type ClaimLineSource = 'draft' | 'evidence' | 'none';

export type ClaimLines = {
  source: ClaimLineSource;
  lines: InvoiceLineDetail[];
};

export function latestBundle(bundles: EvidenceBundleSummary[]): EvidenceBundleSummary | null {
  return bundles.reduce<EvidenceBundleSummary | null>(
    (latest, bundle) => (latest === null || bundle.version > latest.version ? bundle : latest),
    null,
  );
}

// The stored evidence payload is a JSON string, so it is parsed defensively and
// only lines with a numeric quantity and price are accepted. A malformed
// payload yields null rather than a fabricated bundle.
export function parseEvidencePayload(raw: string): { lines: InvoiceLineDetail[] } | null {
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) return null;
  const candidate = parsed as { lines?: unknown };
  if (!Array.isArray(candidate.lines)) return null;
  const lines: InvoiceLineDetail[] = [];
  for (const entry of candidate.lines) {
    if (typeof entry !== 'object' || entry === null) continue;
    const line = entry as Partial<InvoiceLineDetail>;
    if (typeof line.lineNumber !== 'number' || typeof line.quantity !== 'number') continue;
    if (typeof line.unitPrice !== 'number') continue;
    lines.push({
      lineNumber: line.lineNumber,
      rawItemName: typeof line.rawItemName === 'string' ? line.rawItemName : '',
      quantity: line.quantity,
      unitPrice: line.unitPrice,
      confirmedItemId: typeof line.confirmedItemId === 'string' ? line.confirmedItemId : null,
    });
  }
  return { lines };
}

// A submitted case has no OPEN draft, so its claim lines come from the latest
// sealed evidence bundle. This chooses the authoritative source without ever
// merging the two.
export function claimLines(detail: InvoiceCaseDetail, sealed: EvidenceBundleDetail | null): ClaimLines {
  if (detail.lines.length > 0) {
    return { source: 'draft', lines: detail.lines };
  }
  if (sealed) {
    const parsed = parseEvidencePayload(sealed.payload);
    if (parsed && parsed.lines.length > 0) {
      return { source: 'evidence', lines: parsed.lines };
    }
  }
  return { source: 'none', lines: [] };
}

export function comparisonRows(match: MatchResultView): ComparisonRow[] {
  return match.payload.lineOutcomes.map((outcome) => ({
    lineNumber: outcome.lineNumber,
    rawItemName: outcome.rawItemName,
    confirmedItemId: outcome.confirmedItemId,
    status: outcome.status,
    hasPurchaseOrderLine: outcome.purchaseOrderLine !== null,
    orderedQuantity: outcome.purchaseOrderLine?.orderedQuantity ?? null,
    availableConfirmedQuantity: outcome.availableConfirmedQuantity,
    plannedQuantity: outcome.plannedQuantity,
    invoiceQuantity: outcome.invoiceQuantity,
    invoiceUnitPrice: outcome.invoiceUnitPrice,
    poUnitPrice: outcome.purchaseOrderLine?.unitPrice ?? null,
    plannedAllocations: outcome.expectedAllocationPlan,
    issues: outcome.exceptions.map((exception) => ({
      type: exception.type,
      label: presentMatchException(exception.type),
    })),
  }));
}

const MATCH_EXCEPTION_LABELS: Record<string, string> = {
  ITEM_UNCONFIRMED: '품목 매핑 미확정',
  EVIDENCE_INSUFFICIENT: '판단 근거 부족',
  QUANTITY_EXCEEDS_RECEIPT_BALANCE: '검수 잔량 초과',
  UNIT_PRICE_MISMATCH: '단가 불일치',
  DUPLICATE_INVOICE_SUSPECTED: '청구번호 중복 의심',
};

export function presentMatchException(type: string): string {
  return MATCH_EXCEPTION_LABELS[type] ?? type;
}

const MATCH_LINE_STATUS_LABELS: Record<string, string> = {
  MATCHED: '비교됨',
  ITEM_UNCONFIRMED: '품목 미확정',
  EVIDENCE_INSUFFICIENT: '근거 부족',
};

export function presentLineStatus(status: string): string {
  return MATCH_LINE_STATUS_LABELS[status] ?? status;
}

const DECISION_LABELS: Record<string, string> = {
  MAPPING: '품목 매핑',
  SUPPLEMENT_REQUESTED: '보완 요청',
  REJECTED: '청구 거절',
  APPROVED: '승인',
};

export function presentDecision(decision: string): string {
  return DECISION_LABELS[decision] ?? decision;
}

const FRESHNESS_REASON_LABELS: Record<string, string> = {
  CASE_STATE: '청구 상태가 변경됨',
  CASE_VERSION: '청구 버전이 변경됨',
  EVIDENCE_BUNDLE: '증빙 버전이 변경됨',
  MATCH_RESULT: '대사 결과가 변경됨',
  MAPPING: '매핑이 변경됨',
  PURCHASING_SNAPSHOT: '구매 스냅샷이 변경됨',
  SUPERSEDED: '더 새로운 검토 대상이 있음',
};

export function presentFreshnessReason(reason: string): string {
  return FRESHNESS_REASON_LABELS[reason] ?? reason;
}

const PAYMENT_STATUS_LABELS: Record<string, string> = {
  NOT_SENT: '전송 전',
  SENDING: '전송 중',
  ACKNOWLEDGED: '인계 완료',
  RETRY_SCHEDULED: '재시도 예약',
  FAILED: '인계 실패',
  RESULT_UNKNOWN: '결과 불명',
};

export function presentPaymentStatus(status: string): string {
  return PAYMENT_STATUS_LABELS[status] ?? status;
}

const OUTBOX_STATUS_LABELS: Record<string, string> = {
  READY: '대기',
  CLAIMED: '선점됨',
  SENDING: '전송 중',
  DELIVERED: '전달됨',
  FAILED: '실패',
  RESULT_UNKNOWN: '결과 불명',
};

export function presentOutboxStatus(status: string): string {
  return OUTBOX_STATUS_LABELS[status] ?? status;
}

// The human-readable labels reuse the confirmed UI phrasing (청구서, 보완, 비교
// 결과, 청구 거절); no new domain term is invented here.
const AUDIT_ACTION_LABELS: Record<string, string> = {
  CASE_CREATED: '청구서 생성',
  DRAFT_LINES_REPLACED: '초안 저장',
  CASE_SUBMITTED: '청구 제출',
  SUPPLEMENT_REVISION_OPENED: '보완 작성 시작',
  MATCH_RUN: '비교 결과 생성',
  REVIEW_SNAPSHOT_FROZEN: '검토 대상 저장',
  ITEM_MAPPED: '품목 매핑',
  SUPPLEMENT_REQUESTED: '보완 요청',
  CASE_REJECTED: '청구 거절',
  APPROVE: '승인',
};

export function presentAuditAction(action: string): string {
  return AUDIT_ACTION_LABELS[action] ?? action;
}

export function decisionDetail(decision: ReviewDecisionView): string {
  if (decision.decision === 'MAPPING') {
    const line = decision.mappingLineNumber === null ? '—' : `라인 ${decision.mappingLineNumber}`;
    const item = decision.mappingItemId ?? '—';
    const poLine = decision.mappingPoLineId ?? '—';
    return `${line} · ${item} / ${poLine}`;
  }
  return decision.reason ?? '사유 없음';
}

export function freshnessVerdict(freshness: ReviewFreshness): string {
  return freshness.current ? '현재 자료와 일치' : '현재 자료와 불일치';
}

// A match result is computed against one frozen evidence bundle. When the case
// has since been resubmitted, the latest match no longer reflects the current
// claim; the screen must say so instead of showing it as the current verdict.
// Audit change bodies are arbitrary JSON. A number outside the safe integer
// range cannot be represented exactly, so it is replaced by a marker instead of
// being printed as if it were the exact value. The replacer walks nested
// objects and arrays, so a deep unsafe value is redacted too.
export const UNSAFE_NUMBER_MARKER = '[지원 범위 초과 값 생략]';

export function safeJsonStringify(value: unknown): string {
  const text = JSON.stringify(value, (_key, item) => {
    if (typeof item === 'number' && (!Number.isFinite(item) || !Number.isSafeInteger(item))) {
      return UNSAFE_NUMBER_MARKER;
    }
    return item;
  });
  return text ?? '';
}

// A decision may only be sent for the exact frozen subject whose actual source
// facts are what the screen is showing. A B snapshot with a current freshness
// flag is not enough: the displayed comparison must be the snapshot's own match
// result against the snapshot's own evidence/purchasing facts. If any required
// read is missing or disagrees, the subject is not bound and decisions are
// blocked (the server would reject them anyway; the UI must not offer them).
export type ReviewBinding = {
  bound: boolean;
  reasons: string[];
};

// Minimal structural read of the frozen snapshot payload (ReviewSnapshotView.payload).
// The backend writes that canonical JSON; the UI only reads the identity/hash
// fields it needs to prove the displayed comparison is the frozen one. It never
// recomputes the payload hash.
type FrozenSnapshotPayload = {
  evidenceBundle?: { id?: unknown; version?: unknown; payloadHash?: unknown };
  matchResult?: { id?: unknown; resultNumber?: unknown; resultHash?: unknown; mappingWatermark?: unknown };
  purchasingSnapshot?: { snapshotVersion?: unknown; payloadHash?: unknown };
};

function frozenPayloadOf(payload: unknown): FrozenSnapshotPayload | null {
  if (typeof payload !== 'object' || payload === null) return null;
  return payload as FrozenSnapshotPayload;
}

export function reviewSubjectBinding(input: {
  snapshot: ReviewSnapshotView | null;
  freshnessCurrent: boolean | null;
  match: MatchResultView | null;
  currentBundleVersion: number | null;
  currentBundleHash: string | null;
}): ReviewBinding {
  const reasons: string[] = [];
  const { snapshot, freshnessCurrent, match, currentBundleVersion, currentBundleHash } = input;
  if (!snapshot) return { bound: false, reasons: ['동결된 검토 대상이 없습니다.'] };
  if (freshnessCurrent !== true) reasons.push('검토 대상의 최신성이 확인되지 않았습니다.');
  if (!match) {
    reasons.push('비교 결과를 확인할 수 없습니다.');
    return { bound: false, reasons };
  }
  // A missing/unknown current bundle hash cannot be proven; fail closed rather
  // than approving against an unverified bundle.
  if (currentBundleVersion === null || currentBundleHash === null) {
    reasons.push('현재 증빙의 버전·지문을 확인할 수 없습니다.');
  } else {
    if (snapshot.evidenceBundleVersion !== currentBundleVersion) reasons.push('표시된 증빙 버전이 검토 대상의 근거와 다릅니다.');
    if (match.payload.evidenceBundle.payloadHash !== currentBundleHash) reasons.push('표시된 증빙 지문이 현재 증빙과 다릅니다.');
  }
  if (snapshot.matchResultId !== match.id) reasons.push('표시된 비교 결과가 검토 대상의 비교 결과와 다릅니다.');
  if (snapshot.mappingWatermark !== match.mappingWatermark) reasons.push('표시된 매핑 watermark가 검토 대상과 다릅니다.');
  if (snapshot.evidenceBundleId !== match.evidenceBundleId) reasons.push('비교 결과의 증빙이 검토 대상의 근거와 다릅니다.');
  if (snapshot.purchasingSnapshotVersion !== match.payload.purchasingSnapshot.snapshotVersion) reasons.push('비교 결과의 구매 스냅샷 버전이 검토 대상과 다릅니다.');
  if (snapshot.purchasingSnapshotHash !== match.payload.purchasingSnapshot.payloadHash) reasons.push('비교 결과의 구매 스냅샷 지문이 검토 대상과 다릅니다.');

  // The frozen canonical payload must carry the same source match/bundle/
  // purchasing facts the screen is showing; a snapshot whose own payload does
  // not is not a trustworthy subject.
  const frozen = frozenPayloadOf(snapshot.payload);
  const frozenMatch = frozen?.matchResult;
  if (!frozenMatch) {
    reasons.push('검토 대상의 동결 근거를 확인할 수 없습니다.');
  } else {
    if (frozenMatch.id !== match.id) reasons.push('검토 대상 근거의 비교 결과 ID가 표시된 비교와 다릅니다.');
    if (frozenMatch.resultNumber !== match.resultNumber) reasons.push('검토 대상 근거의 비교 결과 번호가 표시된 비교와 다릅니다.');
    if (frozenMatch.resultHash !== match.resultHash) reasons.push('검토 대상 근거의 비교 해시가 표시된 비교와 다릅니다.');
    if (frozenMatch.mappingWatermark !== snapshot.mappingWatermark) reasons.push('검토 대상 근거의 매핑 watermark가 동결 값과 다릅니다.');
  }
  const frozenBundle = frozen?.evidenceBundle;
  if (!frozenBundle || frozenBundle.id !== match.evidenceBundleId || frozenBundle.version !== snapshot.evidenceBundleVersion || frozenBundle.payloadHash !== match.payload.evidenceBundle.payloadHash) {
    reasons.push('검토 대상 근거의 증빙 정보가 표시된 비교와 다릅니다.');
  }
  const frozenPurchasing = frozen?.purchasingSnapshot;
  if (!frozenPurchasing || frozenPurchasing.snapshotVersion !== match.payload.purchasingSnapshot.snapshotVersion || frozenPurchasing.payloadHash !== match.payload.purchasingSnapshot.payloadHash) {
    reasons.push('검토 대상 근거의 구매 스냅샷 정보가 표시된 비교와 다릅니다.');
  }
  return { bound: reasons.length === 0, reasons };
}

// The exact approval/decision body: always the frozen snapshot the reviewer is
// looking at, never a match result or a different snapshot.
export function decisionSubjectPayload(
  expectedCaseVersion: number,
  snapshot: ReviewSnapshotView,
): { expectedCaseVersion: number; reviewSnapshotId: string; reviewPayloadHash: string } {
  return {
    expectedCaseVersion,
    reviewSnapshotId: snapshot.id,
    reviewPayloadHash: snapshot.payloadHash,
  };
}

export function isStaleMatch(
  match: MatchResultView,
  currentBundleVersion: number | null,
  currentBundleHash: string | null,
): boolean {
  if (currentBundleVersion === null) return false;
  if (match.payload.evidenceBundle.version !== currentBundleVersion) return true;
  return currentBundleHash !== null && match.payload.evidenceBundle.payloadHash !== currentBundleHash;
}
