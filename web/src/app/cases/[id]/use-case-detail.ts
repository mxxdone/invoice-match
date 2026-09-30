'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  fetchAuditEntries,
  fetchCaseHandoff,
  fetchEvidenceBundle,
  fetchEvidenceBundles,
  fetchInvoiceCase,
  fetchLatestMatch,
  fetchLatestReviewSnapshot,
  fetchReviewDecisions,
  fetchReviewFreshness,
} from '../../api/client.ts';
import { ApiRequestError, type Credentials } from '../../api/transport.ts';
import { Generation } from '../../api/generation.ts';
import type {
  AuditEntryView,
  AuditHistoryPage,
  CaseHandoffStatus,
  EvidenceBundleDetail,
  EvidenceBundleSummary,
  InvoiceCaseDetail,
  MatchResultView,
  ReviewDecisionView,
  ReviewFreshness,
  ReviewSnapshotView,
} from '../../api/contract.ts';
import { latestBundle } from './detail-model.ts';

export const AUDIT_PAGE_SIZE = 20;

// A section that is absent, forbidden or failed is kept distinct from a page
// that failed to load at all, so a missing match result never looks like a
// missing case.
export type SectionState<T> =
  | { status: 'ready'; data: T }
  | { status: 'empty' }
  | { status: 'forbidden' }
  | { status: 'error'; message: string };

export type CaseDetailData = {
  detail: InvoiceCaseDetail;
  bundles: SectionState<EvidenceBundleSummary[]>;
  sealed: SectionState<EvidenceBundleDetail>;
  handoff: SectionState<CaseHandoffStatus>;
  match: SectionState<MatchResultView>;
  snapshot: SectionState<ReviewSnapshotView>;
  freshness: SectionState<ReviewFreshness>;
  decisions: SectionState<ReviewDecisionView[]>;
  audit: SectionState<AuditHistoryPage>;
  canReadReview: boolean;
};

export type CaseDetailLoad =
  | { status: 'loading' }
  | { status: 'notFound' }
  | { status: 'forbidden' }
  | { status: 'error'; message: string }
  | { status: 'ready'; data: CaseDetailData };

export type UseCaseDetailOptions = {
  credentials: Credentials | null;
  sessionId: number;
  caseId: string;
  canReadReview: boolean;
  reloadToken: number;
  onUnauthorized: () => void;
};

function isAbort(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError';
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '자료를 불러오지 못했습니다.';
}

function forbiddenSection<T>(): SectionState<T> {
  return { status: 'forbidden' };
}

// Runs one section read and classifies the failure. A 401 (or a cancellation)
// is never swallowed into a section state: it must abort the whole load so the
// session is re-checked once instead of rendering a misleading section.
async function loadSection<T>(
  read: () => Promise<T>,
  classify: (data: T) => SectionState<T>,
): Promise<SectionState<T>> {
  try {
    return classify(await read());
  } catch (caught) {
    if (isAbort(caught)) throw caught;
    if (caught instanceof ApiRequestError && caught.status === 401) throw caught;
    if (caught instanceof ApiRequestError && caught.status === 403) return { status: 'forbidden' };
    if (caught instanceof ApiRequestError && caught.status === 404) return { status: 'empty' };
    return { status: 'error', message: errorMessage(caught) };
  }
}

function listSection<T>(items: T[]): SectionState<T[]> {
  return items.length > 0 ? { status: 'ready', data: items } : { status: 'empty' };
}

function pageSection(page: AuditHistoryPage): SectionState<AuditHistoryPage> {
  return page.entries.length > 0 ? { status: 'ready', data: page } : { status: 'empty' };
}

export async function loadCaseDetail({
  credentials,
  caseId,
  canReadReview,
  signal,
  isCurrent,
}: {
  credentials: Credentials;
  caseId: string;
  canReadReview: boolean;
  signal: AbortSignal;
  isCurrent: () => boolean;
}): Promise<CaseDetailLoad> {
  let detail: InvoiceCaseDetail;
  try {
    detail = await fetchInvoiceCase(credentials, caseId, signal);
  } catch (caught) {
    if (isAbort(caught)) throw caught;
    if (caught instanceof ApiRequestError && caught.status === 401) throw caught;
    if (caught instanceof ApiRequestError && caught.status === 404) return { status: 'notFound' };
    if (caught instanceof ApiRequestError && caught.status === 403) return { status: 'forbidden' };
    return { status: 'error', message: errorMessage(caught) };
  }
  if (!isCurrent()) return { status: 'loading' };

  const [bundles, handoff, match, snapshot, decisions, audit] = await Promise.all([
    loadSection(() => fetchEvidenceBundles(credentials, caseId, signal), (data) => listSection(data)),
    loadSection(() => fetchCaseHandoff(credentials, caseId, signal), (data) => ({ status: 'ready', data })),
    canReadReview
      ? loadSection(() => fetchLatestMatch(credentials, caseId, signal), (data) => ({ status: 'ready', data }))
      : Promise.resolve(forbiddenSection<MatchResultView>()),
    canReadReview
      ? loadSection(() => fetchLatestReviewSnapshot(credentials, caseId, signal), (data) => ({ status: 'ready', data }))
      : Promise.resolve(forbiddenSection<ReviewSnapshotView>()),
    canReadReview
      ? loadSection(() => fetchReviewDecisions(credentials, caseId, signal), (data) => listSection(data))
      : Promise.resolve(forbiddenSection<ReviewDecisionView[]>()),
    canReadReview
      ? loadSection(
          () => fetchAuditEntries(credentials, caseId, null, AUDIT_PAGE_SIZE, signal),
          pageSection,
        )
      : Promise.resolve(forbiddenSection<AuditHistoryPage>()),
  ]);

  if (!isCurrent()) return { status: 'loading' };

  const bundleList = bundles.status === 'ready' ? bundles.data : [];
  const newestBundle = latestBundle(bundleList);
  // A submitted case keeps no OPEN draft, so its claim lines are read back from
  // the latest sealed bundle. The extra read only happens when it is needed.
  const sealed: SectionState<EvidenceBundleDetail> =
    detail.lines.length === 0 && newestBundle !== null
      ? await loadSection(
          () => fetchEvidenceBundle(credentials, caseId, newestBundle.version, signal),
          (data) => ({ status: 'ready', data }),
        )
      : { status: 'empty' };

  if (!isCurrent()) return { status: 'loading' };

  let freshness: SectionState<ReviewFreshness>;
  if (!canReadReview) {
    freshness = { status: 'forbidden' };
  } else if (snapshot.status === 'ready') {
    freshness = await loadSection(
      () => fetchReviewFreshness(credentials, caseId, snapshot.data.snapshotNumber, signal),
      (data) => ({ status: 'ready', data }),
    );
  } else {
    freshness = { status: 'empty' };
  }

  return {
    status: 'ready',
    data: {
      detail,
      bundles,
      sealed,
      handoff,
      match,
      snapshot,
      freshness,
      decisions,
      audit,
      canReadReview,
    },
  };
}

type Loaded = { key: string; load: CaseDetailLoad };

type AuditExtra = {
  key: string;
  entries: AuditEntryView[];
  nextCursor: string | null;
};

export function useCaseDetail({
  credentials,
  sessionId,
  caseId,
  canReadReview,
  reloadToken,
  onUnauthorized,
}: UseCaseDetailOptions) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [auditExtra, setAuditExtra] = useState<AuditExtra | null>(null);
  // The loading flag is scoped to the request key, so a pagination that was in
  // flight when the case/session changed can never leave the new view loading.
  const [auditLoading, setAuditLoading] = useState<string | null>(null);
  const requests = useRef(new Generation());
  const pagination = useRef(new Generation());
  const paginationController = useRef<AbortController | null>(null);
  const requestKey = `${sessionId}#${caseId}#${canReadReview ? 'review' : 'basic'}#${reloadToken}`;

  useEffect(() => {
    const guard = requests.current;
    const token = guard.next();
    if (!credentials) {
      return;
    }
    const controller = new AbortController();
    paginationController.current = controller;
    loadCaseDetail({
      credentials,
      caseId,
      canReadReview,
      signal: controller.signal,
      isCurrent: () => guard.isCurrent(token),
    })
      .then((load) => {
        if (!guard.isCurrent(token)) return;
        setLoaded({ key: requestKey, load });
      })
      .catch((caught: unknown) => {
        if (!guard.isCurrent(token)) return;
        if (isAbort(caught)) return;
        if (caught instanceof ApiRequestError && caught.status === 401) {
          onUnauthorized();
          return;
        }
        setLoaded({ key: requestKey, load: { status: 'error', message: errorMessage(caught) } });
      });
    return () => {
      controller.abort();
      guard.next();
    };
  }, [credentials, sessionId, caseId, canReadReview, reloadToken, requestKey, onUnauthorized]);

  const isCurrent = loaded !== null && loaded.key === requestKey;
  const load: CaseDetailLoad = isCurrent ? loaded.load : { status: 'loading' };

  const base = load.status === 'ready' ? load.data : null;
  const extra = auditExtra && auditExtra.key === requestKey ? auditExtra : null;
  const baseAudit = base && base.audit.status === 'ready' ? base.audit.data : null;
  const auditEntries: AuditEntryView[] = [
    ...(baseAudit?.entries ?? []),
    ...(extra?.entries ?? []),
  ];
  const auditNextCursor = extra ? extra.nextCursor : (baseAudit?.nextCursor ?? null);
  const auditLoadingMore = auditLoading === requestKey;

  const loadMoreAudit = useCallback(() => {
    if (!credentials || !baseAudit || auditLoadingMore) return;
    const cursor = extra ? extra.nextCursor : baseAudit.nextCursor;
    if (!cursor) return;
    const guard = pagination.current;
    const token = guard.next();
    setAuditLoading(requestKey);
    fetchAuditEntries(credentials, caseId, cursor, AUDIT_PAGE_SIZE, paginationController.current?.signal)
      .then((page) => {
        if (!guard.isCurrent(token)) return;
        setAuditExtra((previous) => ({
          key: requestKey,
          entries: [...(previous && previous.key === requestKey ? previous.entries : []), ...page.entries],
          nextCursor: page.nextCursor,
        }));
        setAuditLoading(null);
      })
      .catch((caught: unknown) => {
        if (!guard.isCurrent(token)) return;
        if (isAbort(caught)) return;
        setAuditLoading(null);
        if (caught instanceof ApiRequestError && caught.status === 401) onUnauthorized();
      });
  }, [credentials, caseId, requestKey, baseAudit, extra, auditLoadingMore, onUnauthorized]);

  return { load, isLoading: !isCurrent, auditEntries, auditNextCursor, auditLoadingMore, loadMoreAudit };
}
