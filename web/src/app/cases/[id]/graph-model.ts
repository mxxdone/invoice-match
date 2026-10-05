// Pure adapters for the default-off graph workflow UI. Every value comes from a
// server DTO field; nothing here decides currentness, supported versions,
// permissions or hash eligibility. Those are read from the Core projection.

import type {
  GraphConfirmation,
  GraphItemDecision,
  GraphPending,
  GraphView,
  SelectedProposal,
} from '../../api/contract.ts';

const GRAPH_STATUS_LABELS: Record<string, string> = {
  QUEUED: '분석 예약됨',
  RUNNING: '분석 중',
  WAITING_HUMAN: '사람 확인 대기',
  COMPLETED: '분석 완료',
  FAILED: '분석 실패',
  STALE: '이전 입력의 분석',
};

export function presentGraphStatus(status: string): string {
  return GRAPH_STATUS_LABELS[status] ?? status;
}

const GRAPH_SEGMENT_LABELS: Record<string, string> = {
  START: '최초 분석',
  RESUME: '확인 후 분석',
};

export function presentGraphSegment(segment: string): string {
  return GRAPH_SEGMENT_LABELS[segment] ?? segment;
}

const GRAPH_REASON_LABELS: Record<string, string> = {
  DOCUMENT_REVIEW_REQUIRED: '문서 후보 확인 필요',
  AMBIGUOUS_ITEM: '품목 후보가 여러 개임',
  NO_ITEM_CANDIDATE: '품목 후보 없음',
};

export function presentGraphReason(code: string): string {
  return GRAPH_REASON_LABELS[code] ?? code;
}

// Core reports the resume outbox status; COMPLETED/CANCELLED are the terminal
// values. The non-terminal values share the same wait/in-progress phrasing.
const GRAPH_RESUME_LABELS: Record<string, string> = {
  QUEUED: '재개 대기',
  READY: '재개 대기',
  CLAIMED: '재개 중',
  PUBLISHED: '재개 중',
  SENDING: '재개 중',
  RUNNING: '재개 중',
  COMPLETED: '재개 완료',
  DONE: '재개 완료',
  CANCELLED: '재개 취소',
  FAILED: '재개 실패',
};

export function presentGraphResumeStatus(status: string): string {
  return GRAPH_RESUME_LABELS[status] ?? status;
}

type MappingLine = NonNullable<GraphPending['mapping']>['lines'][number];

// Only lines whose candidate set is not exactly one need an explicit human
// decision; the server defines the same set. Empty and multi-candidate lines
// both require a decision and both allow the unresolved (null, null) opinion.
export function pendingMappingLines(pending: GraphPending | null): MappingLine[] {
  return (pending?.mapping?.lines ?? []).filter((line) => line.candidates.length !== 1);
}

export function hasDocumentReview(reasonCodes: string[]): boolean {
  return reasonCodes.includes('DOCUMENT_REVIEW_REQUIRED');
}

// The server accepts CONFIRMED/NEEDS_CORRECTION only when the document stage
// flagged a review; otherwise the decision is fixed at NOT_REQUIRED.
export function documentDecisionOptions(reasonCodes: string[]): GraphConfirmation['documentDecision'][] {
  return hasDocumentReview(reasonCodes) ? ['CONFIRMED', 'NEEDS_CORRECTION'] : ['NOT_REQUIRED'];
}

export type ItemDecisionChoice = { itemId: string; purchaseOrderLineId: string } | null;

// Builds the exact GraphReviewController body. `source` is copied verbatim from
// the frozen line; an unresolved line is recorded with both ids null. It never
// invents an item or a purchase order line.
export function buildConfirmation(
  pending: GraphPending,
  documentDecision: GraphConfirmation['documentDecision'],
  choices: Map<number, ItemDecisionChoice>,
): GraphConfirmation {
  const itemDecisions: GraphItemDecision[] = pendingMappingLines(pending).map((line) => {
    const choice = choices.get(line.lineNumber) ?? null;
    return {
      lineNumber: line.lineNumber,
      source: line.source,
      itemId: choice?.itemId ?? null,
      purchaseOrderLineId: choice?.purchaseOrderLineId ?? null,
    };
  });
  return {
    documentStageRef: pending.documentStageRef,
    mappingStageRef: pending.mappingStageRef,
    documentDecision,
    itemDecisions,
  };
}

// A completed graph proposal is eligible only when the Core projection says it
// is current and supported and carries its immutable payload hash. The browser
// never recomputes the hash.
export function eligibleGraphProposal(view: GraphView | null): SelectedProposal | null {
  return view?.run.current && view.run.supported && view.run.status === 'COMPLETED' && view.run.payloadHash && view.payload
    ? { proposalId: view.run.id, proposalHash: view.run.payloadHash }
    : null;
}

