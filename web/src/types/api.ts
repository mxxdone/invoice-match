// Strongly typed contracts conforming directly to the Core API backend JSON specifications.

export type Role = "SUBMITTER" | "APPROVER" | "OPERATOR";

export type InvoiceCaseStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "REVIEW_PENDING"
  | "SUPPLEMENT_REQUIRED"
  | "REJECTED"
  | "EXPORT_PENDING"
  | "EXPORTED";

export interface CurrentUserView {
  username: string;
  roles: Role[];
}

export interface InvoiceCaseSummary {
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
}

export interface InvoiceCasePage {
  items: InvoiceCaseSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
}

export interface InvoiceLineDetail {
  lineNumber: number;
  rawItemName: string;
  quantity: number;
  unitPrice: number;
  confirmedItemId: string | null;
}

export interface DraftRevisionDetail {
  id: string;
  revisionNumber: number;
  status: string;
}

export interface InvoiceCaseDetail {
  id: string;
  supplierId: string;
  purchaseOrderId: string;
  invoiceNumber: string;
  submittedBy: string;
  status: InvoiceCaseStatus;
  version: number;
  currentRevision: DraftRevisionDetail | null;
  lines: InvoiceLineDetail[];
}

export interface CreateInvoiceCaseRequest {
  requestId: string;
  supplierId: string;
  purchaseOrderId: string;
  invoiceNumber: string;
}

export interface LineRequest {
  lineNumber: number;
  rawItemName: string;
  quantity: number;
  unitPrice: number;
  confirmedItemId?: string | null;
}

export interface ReplaceDraftLinesRequest {
  requestId: string;
  expectedCaseVersion: number;
  lines: LineRequest[];
}

export interface SubmitInvoiceCaseRequest {
  requestId: string;
  expectedCaseVersion: number;
}

export interface OpenSupplementRevisionRequest {
  requestId: string;
  expectedCaseVersion: number;
}

export interface SubmissionResult {
  invoiceCaseId: string;
  status: InvoiceCaseStatus;
  version: number;
  evidenceBundleId: string;
  evidenceBundleVersion: number;
  submittedAt: string;
}

export interface MatchPoLine {
  purchaseOrderLineId: string;
  itemId: string;
  orderedQuantity: number;
  unitPrice: number;
}

export interface ExpectedAllocation {
  receiptId: string;
  receiptLineId: string;
  receiptDate: string;
  receiptLineVersion: number;
  confirmedQuantity: number;
  plannedQuantity: number;
}

export interface MatchExceptionDetail {
  type: string;
  lineNumber: number | null;
  details: Record<string, unknown>;
}

export interface MatchLineOutcome {
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
  expectedAllocationPlan: ExpectedAllocation[];
  exceptions: MatchExceptionDetail[];
}

export interface MatchResultPayload {
  schemaVersion: string;
  caseId: string;
  supplierId: string;
  purchaseOrderId: string;
  invoiceNumber: string;
  normalizedInvoiceNumber: string;
  caseVersion: number;
  evidenceBundle: {
    id: string;
    version: number;
    payloadHash: string;
  };
  appliedMappings: Array<{
    lineNumber: number;
    itemId: string;
    purchaseOrderLineId: string;
  }>;
  purchasingSnapshot: {
    snapshotVersion: number;
    purchaseOrderVersion: number;
    payloadHash: string;
    receipts: Array<{
      receiptId: string;
      status: string;
      receiptDate: string;
      version: number;
      lines: Array<{
        receiptLineId: string;
        version: number;
        purchaseOrderLineId: string;
        confirmedQuantity: number;
      }>;
    }>;
  };
  allocationPlan: {
    consuming: boolean;
    mode: string;
    fifoOrdering: string;
  };
  lineOutcomes: MatchLineOutcome[];
  exceptions: MatchExceptionDetail[];
  normal: boolean;
}

export interface MatchResultView {
  id: string;
  invoiceCaseId: string;
  evidenceBundleId: string;
  resultNumber: number;
  resultHash: string;
  purchasingSnapshotVersion: number;
  purchasingSnapshotHash: string;
  mappingWatermark: number;
  payload: MatchResultPayload;
  createdAt: string;
}

export interface RunMatchRequest {
  requestId: string;
}

export interface ReviewSnapshotView {
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
  payload: MatchResultPayload;
  createdAt: string;
}

export type StaleReason =
  | "CASE_STATE"
  | "CASE_VERSION"
  | "EVIDENCE_BUNDLE"
  | "MATCH_RESULT"
  | "MAPPING"
  | "PURCHASING_SNAPSHOT"
  | "SUPERSEDED";

export interface ReviewFreshness {
  invoiceCaseId: string;
  reviewSnapshotId: string;
  snapshotNumber: number;
  current: boolean;
  reasons: StaleReason[];
  snapshotCaseVersion: number;
  currentCaseVersion: number;
  currentCaseStatus: string;
  snapshotEvidenceBundleId: string;
  latestEvidenceBundleId: string;
  latestEvidenceBundleVersion: number | null;
  snapshotMatchResultId: string;
  latestMatchResultId: string;
  latestMatchResultNumber: number | null;
  snapshotMappingWatermark: number;
  currentMappingWatermark: number;
  snapshotPurchasingSnapshotVersion: number;
  currentPurchasingSnapshotVersion: number | null;
  snapshotPurchasingSnapshotHash: string;
  currentPurchasingSnapshotHash: string | null;
}

export type ReviewDecisionType =
  | "ITEM_MAPPING"
  | "SUPPLEMENT_REQUESTED"
  | "REJECTED"
  | "APPROVED";

export interface ReviewDecisionView {
  id: string;
  invoiceCaseId: string;
  reviewSnapshotId: string;
  decisionNumber: number;
  decision: ReviewDecisionType;
  decidedBy: string;
  reason: string | null;
  payloadHash: string;
  mappingBundleId: string | null;
  mappingLineNumber: number | null;
  mappingItemId: string | null;
  mappingPoLineId: string | null;
  decidedAt: string;
}

export interface FreezeReviewSnapshotRequest {
  requestId: string;
  expectedCaseVersion: number;
}

export interface RecordMappingDecisionRequest {
  requestId: string;
  expectedCaseVersion: number;
  reviewSnapshotId: string;
  reviewPayloadHash: string;
  lineNumber: number;
  itemId: string;
  decidedBy?: string;
}

export interface MappingDecisionResult {
  decision: ReviewDecisionView;
  successorSnapshot: ReviewSnapshotView;
}

export interface SupplementRequestRequest {
  requestId: string;
  expectedCaseVersion: number;
  reviewSnapshotId: string;
  reviewPayloadHash: string;
  reason: string;
  decidedBy?: string;
}

export interface RejectReviewRequest {
  requestId: string;
  expectedCaseVersion: number;
  reviewSnapshotId: string;
  reviewPayloadHash: string;
  reason: string;
  decidedBy?: string;
}

export interface ApproveInvoiceCaseRequest {
  requestId: string;
  expectedCaseVersion: number;
  reviewSnapshotId: string;
  reviewPayloadHash: string;
}

export interface ApprovedAllocation {
  receiptId: string;
  receiptLineId: string;
  receiptDate: string;
  allocatedQuantity: number;
  unitPrice: number;
  allocationAmount: number;
}

export interface ApprovalResult {
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
}

export type PaymentStatus =
  | "NOT_SENT"
  | "SENDING"
  | "ACKNOWLEDGED"
  | "RETRY_SCHEDULED"
  | "FAILED"
  | "RESULT_UNKNOWN";

export type OutboxStatus =
  | "READY"
  | "CLAIMED"
  | "SENDING"
  | "DELIVERED"
  | "FAILED"
  | "RESULT_UNKNOWN";

export interface PaymentHandoffView {
  invoiceCaseId: string;
  paymentRequestId: string;
  externalRequestKey: string;
  amount: number;
  currency: string;
  exportVersion: number;
  paymentStatus: PaymentStatus;
  outboxStatus: OutboxStatus | null;
  attemptCount: number;
  lastErrorCode: string | null;
  nextAttemptAt: string | null;
  deliveredAt: string | null;
  createdAt: string;
}

export interface CaseHandoffStatus {
  invoiceCaseId: string;
  caseStatus: InvoiceCaseStatus;
  caseVersion: number;
  payment: PaymentHandoffView | null;
}

export interface AuditEntryView {
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
  requestId: string;
  traceId: string;
}

export interface AuditHistoryPage {
  entries: AuditEntryView[];
  nextCursor: string | null;
}

// Error responses
export interface ApiErrorResponse {
  code: string;
  message: string;
  traceId?: string;
}

export interface CaseVersionConflictErrorResponse extends ApiErrorResponse {
  code: "STALE_CASE_VERSION";
  caseId: string | null;
  expectedVersion: number | null;
  latestVersion: number | null;
}

export interface ReviewConflictErrorResponse extends ApiErrorResponse {
  code: "STALE_REVIEW_TARGET" | "REVIEW_STATE_CONFLICT" | "REVIEW_TARGET_INVALID" | "REVIEW_SNAPSHOT_NOT_FOUND";
  reasons: StaleReason[];
  currentCaseVersion: number | null;
  currentCaseStatus: string | null;
}

export interface ApprovalConflictErrorResponse extends ApiErrorResponse {
  code: "APPROVAL_NOT_PERMITTED" | "INSUFFICIENT_RECEIPT_BALANCE";
  reasons: string[];
  shortfalls: unknown[];
}
