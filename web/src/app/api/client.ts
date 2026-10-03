// Same-origin fetch adapter for the live read endpoints. Every call goes to the
// fixed local /backend proxy, which forwards only the Basic authorization
// header to the server-configured Core API. Nothing here stores or logs the
// credentials.

import type {
  DocumentPage,
  DocumentDownload,
  ApprovalResult,
  AuditHistoryPage,
  CaseHandoffStatus,
  CurrentUser,
  EvidenceBundleDetail,
  EvidenceBundleSummary,
  InvoiceCaseDetail,
  InvoiceCasePage,
  MappingDecisionResult,
  MatchResultView,
  ReviewDecisionView,
  ReviewFreshness,
  ReviewSnapshotView,
  SubmissionResult,
} from './contract.ts';
import { buildInvoiceCaseQuery, type InvoiceCaseFilters } from './query.ts';
import {
  ApiRequestError,
  buildBasicAuthHeader,
  toApiRequestError,
  type Credentials,
} from './transport.ts';

const PROXY_PREFIX = '/backend';

export function fetchDocuments(credentials: Credentials, caseId: string, page = 0, signal?: AbortSignal): Promise<DocumentPage> {
  return requestJson<DocumentPage>(`/api/invoice-cases/${encodeURIComponent(caseId)}/documents?page=${page}&size=20`, credentials, { signal });
}

export function fetchDocumentDownload(credentials: Credentials, caseId: string, documentId: string,
  disposition: 'attachment' | 'inline' = 'attachment', signal?: AbortSignal): Promise<DocumentDownload> {
  return requestJson<DocumentDownload>(`/api/invoice-cases/${encodeURIComponent(caseId)}/documents/${encodeURIComponent(documentId)}/download-url?disposition=${disposition}`, credentials, { signal });
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError';
}

type JsonRequestOptions = {
  method?: 'GET' | 'POST' | 'PUT';
  body?: unknown;
  signal?: AbortSignal;
};

async function requestJson<T>(
  path: string,
  credentials: Credentials,
  options: JsonRequestOptions = {},
): Promise<T> {
  const { method = 'GET', body, signal } = options;
  const headers: Record<string, string> = {
    authorization: buildBasicAuthHeader(credentials),
    accept: 'application/json',
  };
  let payload: string | undefined;
  if (body !== undefined) {
    headers['content-type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  let response: Response;
  try {
    response = await fetch(`${PROXY_PREFIX}${path}`, {
      method,
      headers,
      body: payload,
      cache: 'no-store',
      signal,
    });
  } catch (caught) {
    if (isAbortError(caught)) {
      throw caught;
    }
    throw new ApiRequestError(0, 'NETWORK_ERROR', '서버에 연결하지 못했습니다.');
  }
  if (!response.ok) {
    let text = '';
    try {
      text = await response.text();
    } catch (caught) {
      // Preserve cancellation: reading the error body can itself be aborted, and
      // swallowing it into a 401/403 would let a stale request sign the user out.
      if (isAbortError(caught)) {
        throw caught;
      }
    }
    throw toApiRequestError(response.status, text);
  }
  return (await response.json()) as T;
}

export function fetchCurrentUser(
  credentials: Credentials,
  signal?: AbortSignal,
): Promise<CurrentUser> {
  return requestJson<CurrentUser>('/api/me', credentials, { signal });
}

export function fetchInvoiceCases(
  credentials: Credentials,
  filters: InvoiceCaseFilters,
  signal?: AbortSignal,
): Promise<InvoiceCasePage> {
  const query = new URLSearchParams(buildInvoiceCaseQuery(filters)).toString();
  return requestJson<InvoiceCasePage>(
    `/api/invoice-cases${query ? `?${query}` : ''}`,
    credentials,
    { signal },
  );
}

// The case id and numeric version are path segments, so they are encoded even
// though the server already restricts them by their route type.
export function fetchInvoiceCase(
  credentials: Credentials,
  caseId: string,
  signal?: AbortSignal,
): Promise<InvoiceCaseDetail> {
  return requestJson<InvoiceCaseDetail>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}`,
    credentials,
    { signal },
  );
}

export function fetchEvidenceBundles(
  credentials: Credentials,
  caseId: string,
  signal?: AbortSignal,
): Promise<EvidenceBundleSummary[]> {
  return requestJson<EvidenceBundleSummary[]>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/evidence-bundles`,
    credentials,
    { signal },
  );
}

export function fetchEvidenceBundle(
  credentials: Credentials,
  caseId: string,
  version: number,
  signal?: AbortSignal,
): Promise<EvidenceBundleDetail> {
  return requestJson<EvidenceBundleDetail>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/evidence-bundles/${version}`,
    credentials,
    { signal },
  );
}

export function fetchLatestMatch(
  credentials: Credentials,
  caseId: string,
  signal?: AbortSignal,
): Promise<MatchResultView> {
  return requestJson<MatchResultView>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/match`,
    credentials,
    { signal },
  );
}

export function fetchLatestReviewSnapshot(
  credentials: Credentials,
  caseId: string,
  signal?: AbortSignal,
): Promise<ReviewSnapshotView> {
  return requestJson<ReviewSnapshotView>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/review-snapshots/latest`,
    credentials,
    { signal },
  );
}

export function fetchReviewFreshness(
  credentials: Credentials,
  caseId: string,
  snapshotNumber: number,
  signal?: AbortSignal,
): Promise<ReviewFreshness> {
  return requestJson<ReviewFreshness>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/review-snapshots/${snapshotNumber}/freshness`,
    credentials,
    { signal },
  );
}

export function fetchReviewDecisions(
  credentials: Credentials,
  caseId: string,
  signal?: AbortSignal,
): Promise<ReviewDecisionView[]> {
  return requestJson<ReviewDecisionView[]>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/review-decisions`,
    credentials,
    { signal },
  );
}

export function fetchCaseHandoff(
  credentials: Credentials,
  caseId: string,
  signal?: AbortSignal,
): Promise<CaseHandoffStatus> {
  return requestJson<CaseHandoffStatus>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/handoff`,
    credentials,
    { signal },
  );
}

export function fetchAuditEntries(
  credentials: Credentials,
  caseId: string,
  cursor: string | null,
  limit: number,
  signal?: AbortSignal,
): Promise<AuditHistoryPage> {
  const query = new URLSearchParams({ limit: String(limit) });
  if (cursor) query.set('cursor', cursor);
  return requestJson<AuditHistoryPage>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/audit-entries?${query.toString()}`,
    credentials,
    { signal },
  );
}

// --- Live write adapters ---------------------------------------------------
// Each adapter sends exactly one server intent. The caller owns the idempotency
// request id: the same intent retry reuses it and an unrelated action uses a
// new one. The server validates the principal, role, ownership and freshness,
// so nothing here is an authorization decision.

export type DraftLineInput = {
  lineNumber: number;
  rawItemName: string;
  quantity: number;
  unitPrice: number;
  confirmedItemId: string | null;
};

export type CreateInvoiceCaseInput = {
  requestId: string;
  supplierId: string;
  purchaseOrderId: string;
  invoiceNumber: string;
};

export type ReplaceDraftInput = {
  requestId: string;
  expectedCaseVersion: number;
  lines: DraftLineInput[];
};

export type VersionedRequest = {
  requestId: string;
  expectedCaseVersion: number;
};

export type RunMatchInput = {
  requestId: string;
};

export type SnapshotDecisionInput = {
  requestId: string;
  expectedCaseVersion: number;
  reviewSnapshotId: string;
  reviewPayloadHash: string;
};

export type MappingDecisionInput = SnapshotDecisionInput & {
  lineNumber: number;
  itemId: string;
};

export type ReasonDecisionInput = SnapshotDecisionInput & {
  reason: string;
};

export type ApproveInput = SnapshotDecisionInput;

export function createInvoiceCase(
  credentials: Credentials,
  input: CreateInvoiceCaseInput,
  signal?: AbortSignal,
): Promise<InvoiceCaseDetail> {
  return requestJson<InvoiceCaseDetail>('/api/invoice-cases', credentials, {
    method: 'POST',
    body: input,
    signal,
  });
}

export function replaceDraft(
  credentials: Credentials,
  caseId: string,
  input: ReplaceDraftInput,
  signal?: AbortSignal,
): Promise<InvoiceCaseDetail> {
  return requestJson<InvoiceCaseDetail>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/draft`,
    credentials,
    { method: 'PUT', body: input, signal },
  );
}

export function submitInvoiceCase(
  credentials: Credentials,
  caseId: string,
  input: VersionedRequest,
  signal?: AbortSignal,
): Promise<SubmissionResult> {
  return requestJson<SubmissionResult>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/submit`,
    credentials,
    { method: 'POST', body: input, signal },
  );
}

export function openSupplementRevision(
  credentials: Credentials,
  caseId: string,
  input: VersionedRequest,
  signal?: AbortSignal,
): Promise<InvoiceCaseDetail> {
  return requestJson<InvoiceCaseDetail>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/revisions`,
    credentials,
    { method: 'POST', body: input, signal },
  );
}

export function runMatch(
  credentials: Credentials,
  caseId: string,
  input: RunMatchInput,
  signal?: AbortSignal,
): Promise<MatchResultView> {
  return requestJson<MatchResultView>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/match`,
    credentials,
    { method: 'POST', body: input, signal },
  );
}

export function freezeReviewSnapshot(
  credentials: Credentials,
  caseId: string,
  input: VersionedRequest,
  signal?: AbortSignal,
): Promise<ReviewSnapshotView> {
  return requestJson<ReviewSnapshotView>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/review-snapshots`,
    credentials,
    { method: 'POST', body: input, signal },
  );
}

export function recordMappingDecision(
  credentials: Credentials,
  caseId: string,
  input: MappingDecisionInput,
  signal?: AbortSignal,
): Promise<MappingDecisionResult> {
  return requestJson<MappingDecisionResult>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/mapping-decisions`,
    credentials,
    { method: 'POST', body: input, signal },
  );
}

export function requestSupplement(
  credentials: Credentials,
  caseId: string,
  input: ReasonDecisionInput,
  signal?: AbortSignal,
): Promise<ReviewDecisionView> {
  return requestJson<ReviewDecisionView>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/supplement-requests`,
    credentials,
    { method: 'POST', body: input, signal },
  );
}

export function rejectInvoiceCase(
  credentials: Credentials,
  caseId: string,
  input: ReasonDecisionInput,
  signal?: AbortSignal,
): Promise<ReviewDecisionView> {
  return requestJson<ReviewDecisionView>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/reject`,
    credentials,
    { method: 'POST', body: input, signal },
  );
}

export function approveInvoiceCase(
  credentials: Credentials,
  caseId: string,
  input: ApproveInput,
  signal?: AbortSignal,
): Promise<ApprovalResult> {
  return requestJson<ApprovalResult>(
    `/api/invoice-cases/${encodeURIComponent(caseId)}/approve`,
    credentials,
    { method: 'POST', body: input, signal },
  );
}

export type AnalysisJob = {
  runId: string; caseId: string; invoiceNumber: string; inputVersion: number; status: string;
  executionAttempt: number; attemptLimit: number; nextRetryAt: string | null; lastErrorCode: string | null;
  publishStatus: string; createdAt: string; updatedAt: string;
};
export type AnalysisFailure = {
  executionAttempt: number; errorCode: string; disposition: string; createdAt: string;
  publishStatus: string | null; publishedAt: string | null;
};
export type AnalysisPage<T> = { items: T[]; page: number; size: number; totalElements: number };
export type AnalysisRetryIntent = { runId: string; requestId: string; expectedExecutionAttempt: number; reason: string };
export function fetchAnalysisJobs(credentials: Credentials, status: string, page: number, signal?: AbortSignal) {
  const query = new URLSearchParams({ page: String(page), size: '20' });
  if (status) query.set('status', status);
  return requestJson<AnalysisPage<AnalysisJob>>(`/api/analysis-runs?${query}`, credentials, { signal });
}
export function fetchAnalysisFailures(credentials: Credentials, runId: string, page: number, signal?: AbortSignal) {
  return requestJson<AnalysisPage<AnalysisFailure>>(`/api/analysis-runs/${encodeURIComponent(runId)}/failures?page=${page}&size=20`, credentials, { signal });
}
export function retryAnalysis(credentials: Credentials, intent: AnalysisRetryIntent, signal?: AbortSignal) {
  const { runId, ...body } = intent;
  return requestJson<{ runId: string; status: string; executionAttempt: number; attemptLimit: number }>(
    `/api/analysis-runs/${encodeURIComponent(runId)}/retries`, credentials, { method: 'POST', body, signal });
}
