// Same-origin fetch adapter for the live read endpoints. Every call goes to the
// fixed local /backend proxy, which forwards only the Basic authorization
// header to the server-configured Core API. Nothing here stores or logs the
// credentials.

import type {
  AuditHistoryPage,
  CaseHandoffStatus,
  CurrentUser,
  EvidenceBundleDetail,
  EvidenceBundleSummary,
  InvoiceCaseDetail,
  InvoiceCasePage,
  MatchResultView,
  ReviewDecisionView,
  ReviewFreshness,
  ReviewSnapshotView,
} from './contract.ts';
import { buildInvoiceCaseQuery, type InvoiceCaseFilters } from './query.ts';
import {
  ApiRequestError,
  buildBasicAuthHeader,
  toApiRequestError,
  type Credentials,
} from './transport.ts';

const PROXY_PREFIX = '/backend';

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError';
}

async function requestJson<T>(
  path: string,
  credentials: Credentials,
  signal?: AbortSignal,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${PROXY_PREFIX}${path}`, {
      method: 'GET',
      headers: {
        authorization: buildBasicAuthHeader(credentials),
        accept: 'application/json',
      },
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
    let body = '';
    try {
      body = await response.text();
    } catch (caught) {
      // Preserve cancellation: reading the error body can itself be aborted, and
      // swallowing it into a 401/403 would let a stale request sign the user out.
      if (isAbortError(caught)) {
        throw caught;
      }
    }
    throw toApiRequestError(response.status, body);
  }
  return (await response.json()) as T;
}

export function fetchCurrentUser(
  credentials: Credentials,
  signal?: AbortSignal,
): Promise<CurrentUser> {
  return requestJson<CurrentUser>('/api/me', credentials, signal);
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
    signal,
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
    signal,
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
    signal,
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
    signal,
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
    signal,
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
    signal,
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
    signal,
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
    signal,
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
    signal,
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
    signal,
  );
}
