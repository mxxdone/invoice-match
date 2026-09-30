// Wire contract for the Core API read endpoints used by the first live slice.
// These shapes mirror the Java records exactly; a local design fixture is not a
// substitute for them.

export type InvoiceCaseStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'REVIEW_PENDING'
  | 'SUPPLEMENT_REQUIRED'
  | 'REJECTED'
  | 'EXPORT_PENDING'
  | 'EXPORTED';

// GET /api/invoice-cases -> InvoiceCaseSummary (one row of the server page).
export type InvoiceCaseSummary = {
  id: string;
  supplierId: string;
  purchaseOrderId: string;
  invoiceNumber: string;
  submittedBy: string;
  status: InvoiceCaseStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
  submittedAt: string | null;
};

// GET /api/invoice-cases -> InvoiceCasePage. The server names the total
// `totalItems`, not `totalElements`, and pages are 0-based.
export type InvoiceCasePage = {
  items: InvoiceCaseSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
};

// GET /api/me -> CurrentUserView.
export type CurrentUser = {
  username: string;
  roles: string[];
};

// --- Live detail read contracts (P1-10 detail slice) -----------------------
// These mirror the Java records exactly. A missing field is a missing server
// field: nothing below is filled in with a design fixture.

export type InvoiceLineDetail = {
  lineNumber: number;
  rawItemName: string;
  quantity: number;
  unitPrice: number;
  confirmedItemId: string | null;
};

export type DraftRevisionDetail = {
  id: string;
  revisionNumber: number;
  status: string;
};

// GET /api/invoice-cases/{id} -> InvoiceCaseDetail.
export type InvoiceCaseDetail = {
  id: string;
  supplierId: string;
  purchaseOrderId: string;
  invoiceNumber: string;
  submittedBy: string;
  status: InvoiceCaseStatus;
  version: number;
  currentRevision: DraftRevisionDetail | null;
  lines: InvoiceLineDetail[];
};

// GET /api/invoice-cases/{id}/evidence-bundles -> EvidenceBundleSummary[].
export type EvidenceBundleSummary = {
  version: number;
  payloadHash: string;
  submittedAt: string;
};

// GET /api/invoice-cases/{id}/evidence-bundles/{version} -> EvidenceBundleDetail.
// `payload` is the canonical JSON stored as a string, so it must be parsed
// before its lines are read.
export type EvidenceBundleDetail = {
  version: number;
  payloadHash: string;
  payload: string;
  submittedAt: string;
};

// Parsed shape of EvidenceBundleDetail.payload.
export type EvidenceBundlePayload = {
  caseId: string;
  supplierId: string;
  purchaseOrderId: string;
  invoiceNumber: string;
  revisionNumber: number;
  lines: InvoiceLineDetail[];
};

export type MatchExceptionDetail = {
  type: string;
  lineNumber: number | null;
  details: Record<string, unknown>;
};

export type MatchPoLine = {
  purchaseOrderLineId: string;
  itemId: string;
  orderedQuantity: number;
  unitPrice: number;
};

export type PlannedAllocation = {
  receiptId: string;
  receiptLineId: string;
  receiptDate: string;
  receiptLineVersion: number;
  confirmedQuantity: number;
  plannedQuantity: number;
};

export type MatchLineOutcome = {
  lineNumber: number;
  rawItemName: string;
  confirmedItemId: string | null;
  status: string;
  candidatePoLineIds: string[];
  purchaseOrderLine: MatchPoLine | null;
  invoiceQuantity: number;
  invoiceUnitPrice: number;
  availableConfirmedQuantity: number;
  plannedQuantity: number;
  expectedAllocationPlan: PlannedAllocation[];
  exceptions: MatchExceptionDetail[];
};

export type MatchReceiptLine = {
  receiptLineId: string;
  version: number;
  purchaseOrderLineId: string;
  confirmedQuantity: number;
};

export type MatchReceipt = {
  receiptId: string;
  status: string;
  receiptDate: string;
  version: number;
  lines: MatchReceiptLine[];
};

export type MatchPayload = {
  schemaVersion: string;
  caseId: string;
  supplierId: string;
  purchaseOrderId: string;
  invoiceNumber: string;
  normalizedInvoiceNumber: string;
  caseVersion: number;
  evidenceBundle: { id: string; version: number; payloadHash: string };
  appliedMappings: Array<{ lineNumber: number; itemId: string; purchaseOrderLineId: string }>;
  purchasingSnapshot: {
    snapshotVersion: number;
    purchaseOrderVersion: number;
    payloadHash: string;
    receipts: MatchReceipt[];
  };
  allocationPlan: { consuming: boolean; mode: string; fifoOrdering: string };
  lineOutcomes: MatchLineOutcome[];
  exceptions: MatchExceptionDetail[];
  normal: boolean;
};

// GET /api/invoice-cases/{id}/match -> MatchResultView.
export type MatchResultView = {
  id: string;
  invoiceCaseId: string;
  evidenceBundleId: string;
  resultNumber: number;
  resultHash: string;
  purchasingSnapshotVersion: number;
  purchasingSnapshotHash: string;
  mappingWatermark: number;
  payload: MatchPayload;
  createdAt: string;
};

// GET /api/invoice-cases/{id}/review-snapshots/latest -> ReviewSnapshotView.
export type ReviewSnapshotView = {
  id: string;
  invoiceCaseId: string;
  snapshotNumber: number;
  evidenceBundleId: string;
  evidenceBundleVersion: number;
  matchResultId: string;
  matchResultNumber: number | null;
  targetCaseVersion: number;
  purchasingSnapshotVersion: number;
  purchasingSnapshotHash: string;
  mappingWatermark: number;
  payloadHash: string;
  payload: unknown;
  createdAt: string;
};

// GET .../review-snapshots/{number}/freshness -> ReviewFreshness.
export type ReviewFreshness = {
  invoiceCaseId: string;
  reviewSnapshotId: string;
  snapshotNumber: number;
  current: boolean;
  reasons: string[];
  snapshotCaseVersion: number;
  currentCaseVersion: number;
  currentCaseStatus: string;
  snapshotEvidenceBundleId: string;
  latestEvidenceBundleId: string | null;
  latestEvidenceBundleVersion: number | null;
  snapshotMatchResultId: string;
  latestMatchResultId: string | null;
  latestMatchResultNumber: number | null;
  snapshotMappingWatermark: number;
  currentMappingWatermark: number;
  snapshotPurchasingSnapshotVersion: number;
  currentPurchasingSnapshotVersion: number | null;
  snapshotPurchasingSnapshotHash: string;
  currentPurchasingSnapshotHash: string | null;
};

// GET /api/invoice-cases/{id}/review-decisions -> ReviewDecisionView[].
export type ReviewDecisionView = {
  id: string;
  invoiceCaseId: string;
  reviewSnapshotId: string;
  decisionNumber: number;
  decision: string;
  decidedBy: string;
  reason: string | null;
  payloadHash: string | null;
  mappingBundleId: string | null;
  mappingLineNumber: number | null;
  mappingItemId: string | null;
  mappingPoLineId: string | null;
  decidedAt: string;
};

// GET /api/invoice-cases/{id}/handoff -> CaseHandoffStatus.
export type PaymentHandoffView = {
  invoiceCaseId: string;
  paymentRequestId: string;
  externalRequestKey: string;
  amount: number;
  currency: string;
  exportVersion: number;
  paymentStatus: string;
  outboxStatus: string | null;
  attemptCount: number;
  lastErrorCode: string | null;
  nextAttemptAt: string | null;
  deliveredAt: string | null;
  createdAt: string;
};

export type CaseHandoffStatus = {
  invoiceCaseId: string;
  caseStatus: InvoiceCaseStatus;
  caseVersion: number;
  payment: PaymentHandoffView | null;
};

// GET /api/invoice-cases/{id}/audit-entries -> AuditHistoryPage.
export type AuditEntryView = {
  id: string;
  invoiceCaseId: string;
  occurredAt: string;
  actor: string;
  actorRoles: string[];
  action: string;
  targetType: string;
  targetId: string;
  businessVersion: number;
  before: unknown;
  after: unknown;
  requestId: string | null;
  traceId: string | null;
};

export type AuditHistoryPage = {
  entries: AuditEntryView[];
  nextCursor: string | null;
};

// POST /api/invoice-cases/{id}/submit -> SubmissionResult.
export type SubmissionResult = {
  caseId: string;
  status: InvoiceCaseStatus;
  version: number;
  evidenceBundle: EvidenceBundleSummary;
};

// POST /api/invoice-cases/{id}/mapping-decisions -> MappingDecisionResult.
export type MappingDecisionResult = {
  decision: ReviewDecisionView;
  successorSnapshot: ReviewSnapshotView;
};

export type ApprovedAllocation = {
  invoiceLineNumber: number;
  receiptId: string;
  receiptLineId: string;
  allocatedQuantity: number;
};

// POST /api/invoice-cases/{id}/approve -> ApprovalResult.
export type ApprovalResult = {
  invoiceCaseId: string;
  status: string;
  caseVersion: number;
  reviewDecisionId: string;
  decisionNumber: number;
  reviewSnapshotId: string;
  reviewPayloadHash: string;
  paymentRequestId: string;
  externalRequestKey: string;
  amount: number;
  currency: string;
  allocations: ApprovedAllocation[];
  approvedAt: string;
};

// Shared error body for 400/401/403/404/409/503 responses.
export type ApiErrorBody = {
  code: string;
  message: string;
};

export type StatusPresentation = { label: string; tone: string };

// Server statuses keep their approved semantic colours. The supplier name is
// not part of any read contract, so nothing here invents one.
export const statusPresentation: Record<InvoiceCaseStatus, StatusPresentation> = {
  DRAFT: { label: '작성 중', tone: 'neutral' },
  SUBMITTED: { label: '제출됨', tone: 'pending' },
  REVIEW_PENDING: { label: '검토 대기', tone: 'pending' },
  SUPPLEMENT_REQUIRED: { label: '보완 대기', tone: 'attention' },
  REJECTED: { label: '청구 거절', tone: 'rejected' },
  EXPORT_PENDING: { label: '인계 대기', tone: 'pending' },
  EXPORTED: { label: '인계 완료', tone: 'complete' },
};

export function presentStatus(status: string): StatusPresentation {
  return statusPresentation[status as InvoiceCaseStatus] ?? { label: status, tone: 'neutral' };
}

// Server instants are UTC ISO-8601. Display them in the same Asia/Seoul (KST)
// zone the submitted-date filter uses, so what is shown matches what is
// filtered. Asia/Seoul has no DST and is a fixed +09:00 offset.
export function formatInstant(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'short',
    timeZone: 'Asia/Seoul',
  }).format(date);
}
