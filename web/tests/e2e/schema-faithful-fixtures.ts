// Schema-faithful fixtures representing exact JSON payloads from core-api

import {
  ApprovalResult,
  AuditEntryView,
  CaseHandoffStatus,
  CurrentUserView,
  InvoiceCaseDetail,
  InvoiceCasePage,
  MatchResultView,
  ReviewFreshness,
  ReviewSnapshotView,
  Role,
} from "../../src/types/api";

export function fixtureUser(username: string, roles: Role[]): CurrentUserView {
  return { username, roles };
}

export function fixtureCaseDetail(
  id = "11111111-1111-1111-1111-111111111111",
  status: InvoiceCaseDetail["status"] = "DRAFT",
  version = 1,
  submittedBy = "submitter"
): InvoiceCaseDetail {
  return {
    id,
    supplierId: "SUP-1",
    purchaseOrderId: "PO-1001",
    invoiceNumber: "INV-2026-001",
    submittedBy,
    status,
    version,
    currentRevision: status === "DRAFT" || status === "SUPPLEMENT_REQUIRED" ? {
      id: "22222222-2222-2222-2222-222222222222",
      revisionNumber: 1,
      status: "OPEN",
    } : null,
    lines: [
      {
        lineNumber: 1,
        rawItemName: "A4 복사용지 80g",
        quantity: 60,
        unitPrice: 2500,
        confirmedItemId: "ITEM-A4-80",
      },
    ],
  };
}

export function fixtureCasePage(items = [fixtureCaseDetail()]): InvoiceCasePage {
  return {
    items: items.map((c) => ({
      id: c.id,
      supplierId: c.supplierId,
      purchaseOrderId: c.purchaseOrderId,
      invoiceNumber: c.invoiceNumber,
      submittedBy: c.submittedBy,
      status: c.status,
      version: c.version,
      createdAt: "2026-09-28T10:00:00Z",
      updatedAt: "2026-09-28T10:05:00Z",
      submittedAt: c.status !== "DRAFT" ? "2026-09-28T10:05:00Z" : null,
    })),
    page: 0,
    size: 20,
    totalItems: items.length,
    totalPages: 1,
    hasNext: false,
  };
}

export function fixtureMatchResult(caseId: string, normal = true): MatchResultView {
  return {
    id: "33333333-3333-3333-3333-333333333333",
    invoiceCaseId: caseId,
    evidenceBundleId: "44444444-4444-4444-4444-444444444444",
    resultNumber: 1,
    resultHash: "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
    purchasingSnapshotVersion: 1,
    purchasingSnapshotHash: "purchasinghash1234567890",
    mappingWatermark: 0,
    payload: {
      schemaVersion: "match-result-v3",
      caseId,
      supplierId: "SUP-1",
      purchaseOrderId: "PO-1001",
      invoiceNumber: "INV-2026-001",
      normalizedInvoiceNumber: "inv-2026-001",
      caseVersion: 2,
      evidenceBundle: {
        id: "44444444-4444-4444-4444-444444444444",
        version: 1,
        payloadHash: "bundlehash123",
      },
      appliedMappings: [],
      purchasingSnapshot: {
        snapshotVersion: 1,
        purchaseOrderVersion: 1,
        payloadHash: "purchasinghash1234567890",
        receipts: [
          {
            receiptId: "REC-101",
            status: "CONFIRMED",
            receiptDate: "2026-09-20",
            version: 1,
            lines: [
              {
                receiptLineId: "REC-L-1",
                version: 1,
                purchaseOrderLineId: "PO-L-1",
                confirmedQuantity: 100,
              },
            ],
          },
        ],
      },
      allocationPlan: {
        consuming: false,
        mode: "NON_CONSUMING_EXPECTED_PLAN_V1",
        fifoOrdering: "receiptDate,receiptLineId,receiptId",
      },
      lineOutcomes: [
        {
          lineNumber: 1,
          rawItemName: "A4 복사용지 80g",
          confirmedItemId: "ITEM-A4-80",
          status: normal ? "MATCHED" : "ITEM_UNCONFIRMED",
          candidatePoLineIds: ["PO-L-1"],
          purchaseOrderLine: {
            purchaseOrderLineId: "PO-L-1",
            itemId: "ITEM-A4-80",
            orderedQuantity: 100,
            unitPrice: 2500,
          },
          invoiceQuantity: 60,
          invoiceUnitPrice: 2500,
          availableConfirmedQuantity: 100,
          plannedQuantity: normal ? 60 : 0,
          expectedAllocationPlan: normal
            ? [
                {
                  receiptId: "REC-101",
                  receiptLineId: "REC-L-1",
                  receiptDate: "2026-09-20",
                  receiptLineVersion: 1,
                  confirmedQuantity: 100,
                  plannedQuantity: 60,
                },
              ]
            : [],
          exceptions: normal
            ? []
            : [
                {
                  type: "ITEM_UNCONFIRMED",
                  lineNumber: 1,
                  details: { rawItemName: "A4 복사용지 80g" },
                },
              ],
        },
      ],
      exceptions: normal
        ? []
        : [
            {
              type: "ITEM_UNCONFIRMED",
              lineNumber: 1,
              details: { rawItemName: "A4 복사용지 80g" },
            },
          ],
      normal,
    },
    createdAt: "2026-09-28T10:10:00Z",
  };
}

export function fixtureReviewSnapshot(caseId: string, matchResult = fixtureMatchResult(caseId)): ReviewSnapshotView {
  return {
    id: "55555555-5555-5555-5555-555555555555",
    invoiceCaseId: caseId,
    snapshotNumber: 1,
    evidenceBundleId: "44444444-4444-4444-4444-444444444444",
    evidenceBundleVersion: 1,
    matchResultId: matchResult.id,
    matchResultNumber: matchResult.resultNumber,
    targetCaseVersion: 2,
    purchasingSnapshotVersion: 1,
    purchasingSnapshotHash: "purchasinghash1234567890",
    mappingWatermark: 0,
    payloadHash: "snaphash1234567890abcdef1234567890abcdef",
    payload: matchResult.payload,
    createdAt: "2026-09-28T10:12:00Z",
  };
}

export function fixtureFreshness(caseId: string, snapshotId: string, current = true): ReviewFreshness {
  return {
    invoiceCaseId: caseId,
    reviewSnapshotId: snapshotId,
    snapshotNumber: 1,
    current,
    reasons: current ? [] : ["MATCH_RESULT"],
    snapshotCaseVersion: 2,
    currentCaseVersion: 2,
    currentCaseStatus: "REVIEW_PENDING",
    snapshotEvidenceBundleId: "44444444-4444-4444-4444-444444444444",
    latestEvidenceBundleId: "44444444-4444-4444-4444-444444444444",
    latestEvidenceBundleVersion: 1,
    snapshotMatchResultId: "33333333-3333-3333-3333-333333333333",
    latestMatchResultId: "33333333-3333-3333-3333-333333333333",
    latestMatchResultNumber: 1,
    snapshotMappingWatermark: 0,
    currentMappingWatermark: 0,
    snapshotPurchasingSnapshotVersion: 1,
    currentPurchasingSnapshotVersion: 1,
    snapshotPurchasingSnapshotHash: "purchasinghash1234567890",
    currentPurchasingSnapshotHash: "purchasinghash1234567890",
  };
}

export function fixtureApprovalResult(caseId: string, snapshotId: string): ApprovalResult {
  return {
    invoiceCaseId: caseId,
    status: "EXPORT_PENDING",
    caseVersion: 3,
    reviewDecisionId: "66666666-6666-6666-6666-666666666666",
    decisionNumber: 1,
    reviewSnapshotId: snapshotId,
    reviewPayloadHash: "snaphash1234567890abcdef1234567890abcdef",
    paymentRequestId: "77777777-7777-7777-7777-777777777777",
    externalRequestKey: `PAYMENT:${caseId}:${snapshotId}`,
    amount: 150000,
    currency: "KRW",
    allocations: [
      {
        receiptId: "REC-101",
        receiptLineId: "REC-L-1",
        receiptDate: "2026-09-20",
        allocatedQuantity: 60,
        unitPrice: 2500,
        allocationAmount: 150000,
      },
    ],
    approvedAt: "2026-09-28T10:15:00Z",
  };
}

export function fixtureHandoff(
  caseId: string,
  paymentStatus: CaseHandoffStatus["payment"] extends null ? never : NonNullable<CaseHandoffStatus["payment"]>["paymentStatus"] = "ACKNOWLEDGED",
  outboxStatus: CaseHandoffStatus["payment"] extends null ? never : NonNullable<CaseHandoffStatus["payment"]>["outboxStatus"] = "DELIVERED"
): CaseHandoffStatus {
  return {
    invoiceCaseId: caseId,
    caseStatus: "EXPORT_PENDING",
    caseVersion: 3,
    payment: {
      invoiceCaseId: caseId,
      paymentRequestId: "77777777-7777-7777-7777-777777777777",
      externalRequestKey: `PAYMENT:${caseId}:snap-1`,
      amount: 150000,
      currency: "KRW",
      exportVersion: 1,
      paymentStatus,
      outboxStatus,
      attemptCount: paymentStatus === "FAILED" ? 5 : 1,
      lastErrorCode: paymentStatus === "FAILED" ? "ERP_TIMEOUT_EXHAUSTED" : null,
      nextAttemptAt: null,
      deliveredAt: outboxStatus === "DELIVERED" ? "2026-09-28T10:16:00Z" : null,
      createdAt: "2026-09-28T10:15:00Z",
    },
  };
}

export function fixtureAuditEntries(caseId: string): AuditEntryView[] {
  return [
    {
      id: "88888888-8888-8888-8888-888888888888",
      invoiceCaseId: caseId,
      occurredAt: "2026-09-28T10:00:00Z",
      actor: "submitter",
      actorRoles: ["SUBMITTER"],
      action: "CREATE",
      targetType: "INVOICE_CASE",
      targetId: caseId,
      businessVersion: 1,
      before: null,
      after: { status: "DRAFT", supplierId: "SUP-1", invoiceNumber: "INV-2026-001" },
      requestId: "req-create-1",
      traceId: "trc-create-1",
    },
    {
      id: "99999999-9999-9999-9999-999999999999",
      invoiceCaseId: caseId,
      occurredAt: "2026-09-28T10:05:00Z",
      actor: "submitter",
      actorRoles: ["SUBMITTER"],
      action: "SUBMIT",
      targetType: "INVOICE_CASE",
      targetId: caseId,
      businessVersion: 2,
      before: { status: "DRAFT" },
      after: { status: "SUBMITTED" },
      requestId: "req-submit-1",
      traceId: "trc-submit-1",
    },
  ];
}
