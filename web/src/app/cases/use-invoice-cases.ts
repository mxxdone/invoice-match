'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { fetchInvoiceCases } from '../api/client.ts';
import { ApiRequestError, type Credentials } from '../api/transport.ts';
import type { InvoiceCaseFilters } from '../api/query.ts';
import type { InvoiceCasePage } from '../api/contract.ts';
import { Generation } from '../api/generation.ts';

export type CaseListLoadState = 'ready' | 'empty' | 'error' | 'forbidden';
export type CaseListResult = {
  key: string;
  session: number;
  state: CaseListLoadState;
  page: InvoiceCasePage | null;
  error: string;
};

export type UseInvoiceCasesOptions = {
  credentials: Credentials | null;
  sessionId: number;
  filters: InvoiceCaseFilters;
  reloadToken: number;
  onUnauthorized: () => void;
};

/**
 * Server-paged case list for one session.
 *
 * Every effect run allocates a generation token and invalidates it on cleanup,
 * so a response that resolves after a filter change, unmount, logout or a new
 * login can never apply. Rows are additionally scoped to the session that
 * loaded them, so an old session's rows are never rendered for a new one.
 */
export function useInvoiceCases({
  credentials,
  sessionId,
  filters,
  reloadToken,
  onUnauthorized,
}: UseInvoiceCasesOptions) {
  const [loaded, setLoaded] = useState<CaseListResult | null>(null);
  const requests = useRef(new Generation());
  const requestKey = useMemo(
    () => `${sessionId}#${JSON.stringify(filters)}#${reloadToken}`,
    [sessionId, filters, reloadToken],
  );

  useEffect(() => {
    // Allocate first so the early credential-less path still invalidates any
    // previous in-flight request, then invalidate again on cleanup. `guard`
    // pins the stable Generation instance for the cleanup closure.
    const guard = requests.current;
    const token = guard.next();
    if (!credentials) {
      return;
    }
    const controller = new AbortController();
    fetchInvoiceCases(credentials, filters, controller.signal)
      .then(page => {
        if (!guard.isCurrent(token)) return;
        setLoaded({
          key: requestKey,
          session: sessionId,
          state: page.items.length ? 'ready' : 'empty',
          page,
          error: '',
        });
      })
      .catch((caught: unknown) => {
        if (!guard.isCurrent(token)) return;
        if (caught instanceof Error && caught.name === 'AbortError') return;
        if (caught instanceof ApiRequestError && caught.status === 401) {
          onUnauthorized();
          return;
        }
        if (caught instanceof ApiRequestError && caught.status === 403) {
          setLoaded({ key: requestKey, session: sessionId, state: 'forbidden', page: null, error: '' });
          return;
        }
        setLoaded({
          key: requestKey,
          session: sessionId,
          state: 'error',
          page: null,
          error: caught instanceof Error ? caught.message : '목록을 불러오지 못했습니다.',
        });
      });
    return () => {
      controller.abort();
      guard.next();
    };
  }, [credentials, sessionId, filters, requestKey, onUnauthorized]);

  const isCurrent = loaded !== null && loaded.key === requestKey;
  const sameSession = loaded !== null && loaded.session === sessionId;
  return {
    state: isCurrent ? loaded.state : 'loading',
    page: sameSession ? loaded.page : null,
    error: isCurrent ? loaded.error : '',
    isLoading: !isCurrent,
  };
}
