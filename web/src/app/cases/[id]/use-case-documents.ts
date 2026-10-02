'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchDocuments, fetchDocumentDownload } from '../../api/client';
import { ApiRequestError, type Credentials } from '../../api/transport';
import type { DocumentDownload, RegisteredDocument } from '../../api/contract';

type ListState = { scope: string; items: RegisteredDocument[]; page: number; hasNext: boolean; error: string | null };
type LinkState = DocumentDownload & { scope: string };
type Options = { credentials: Credentials; sessionId: number; caseId: string; onUnauthorized: () => void };

function failure(error: unknown) {
  if (error instanceof ApiRequestError) {
    if (error.status === 403) return '원본 문서를 볼 권한이 없습니다.';
    if (error.status === 404) return '문서를 찾을 수 없습니다.';
  }
  return '원본 문서를 불러오지 못했습니다. 다시 시도해 주세요.';
}

export function useCaseDocuments({ credentials, sessionId, caseId, onUnauthorized }: Options) {
  const scope = `${sessionId}:${caseId}`;
  const [list, setList] = useState<ListState | null>(null);
  const [preview, setPreview] = useState<LinkState | null>(null);
  const [download, setDownload] = useState<LinkState | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  const generation = useRef(0);
  const pageRequest = useRef<AbortController | null>(null);
  const linkRequest = useRef<AbortController | null>(null);
  const moreRequest = useRef<AbortController | null>(null);

  useEffect(() => {
    const token = ++generation.current;
    const request = new AbortController();
    pageRequest.current = request;
    void fetchDocuments(credentials, caseId, 0, request.signal).then((page) => {
      if (generation.current === token && !request.signal.aborted) {
        setList({ scope, items: page.items, page: page.page, hasNext: page.hasNext, error: null });
      }
    }).catch((caught) => {
      if (generation.current !== token || request.signal.aborted) return;
      if (caught instanceof ApiRequestError && caught.status === 401) onUnauthorized();
      else setList({ scope, items: [], page: 0, hasNext: false, error: failure(caught) });
    });
    return () => {
      generation.current = token + 1;
      request.abort();
      pageRequest.current?.abort();
      linkRequest.current?.abort();
    };
  }, [credentials, caseId, scope, reload, onUnauthorized]);

  useEffect(() => {
    if (!preview || preview.scope !== scope) return;
    const timer = setTimeout(() => setPreview(null), Math.max(0, Date.parse(preview.expiresAt) - Date.now()));
    return () => clearTimeout(timer);
  }, [preview, scope]);
  useEffect(() => {
    if (!download || download.scope !== scope) return;
    const timer = setTimeout(() => setDownload(null), Math.max(0, Date.parse(download.expiresAt) - Date.now()));
    return () => clearTimeout(timer);
  }, [download, scope]);

  const more = useCallback(async () => {
    if (!list || list.scope !== scope || !list.hasNext || loadingMore || moreRequest.current && !moreRequest.current.signal.aborted) return;
    const token = generation.current;
    const request = new AbortController();
    moreRequest.current = request;
    pageRequest.current = request;
    setLoadingMore(true);
    setError(null);
    try {
      const page = await fetchDocuments(credentials, caseId, list.page + 1, request.signal);
      if (generation.current !== token || request.signal.aborted) return;
      setList({ scope, items: [...list.items, ...page.items.filter((item) => !list.items.some((old) => old.documentId === item.documentId))], page: page.page, hasNext: page.hasNext, error: null });
    } catch (caught) {
      if (generation.current !== token || request.signal.aborted) return;
      if (caught instanceof ApiRequestError && caught.status === 401) onUnauthorized();
      else setError(failure(caught));
    } finally {
      if (moreRequest.current === request) moreRequest.current = null;
      if (generation.current === token) setLoadingMore(false);
    }
  }, [list, scope, loadingMore, credentials, caseId, onUnauthorized]);

  const open = useCallback(async (documentId: string, disposition: 'attachment' | 'inline') => {
    linkRequest.current?.abort();
    const request = new AbortController();
    linkRequest.current = request;
    const token = generation.current;
    setPending(true);
    setError(null);
    if (disposition === 'inline') setPreview(null);
    else setDownload(null);
    try {
      const result = await fetchDocumentDownload(credentials, caseId, documentId, disposition, request.signal);
      if (generation.current !== token || request.signal.aborted) return;
      if (!/^https?:\/\//.test(result.url) || !Number.isFinite(Date.parse(result.expiresAt)) || Date.parse(result.expiresAt) <= Date.now()) {
        throw new Error('Invalid original link');
      }
      const link = { ...result, scope };
      if (disposition === 'inline') setPreview(link);
      else setDownload(link);
    } catch (caught) {
      if (generation.current !== token || request.signal.aborted) return;
      if (caught instanceof ApiRequestError && caught.status === 401) onUnauthorized();
      else setError(failure(caught));
    } finally {
      if (generation.current === token && !request.signal.aborted) setPending(false);
    }
  }, [credentials, caseId, scope, onUnauthorized]);

  const refresh = useCallback(() => {
    generation.current++;
    pageRequest.current?.abort();
    moreRequest.current = null;
    linkRequest.current?.abort();
    setPending(false);
    setLoadingMore(false);
    setError(null);
    setPreview(null);
    setDownload(null);
    setList(null);
    setReload((value) => value + 1);
  }, []);

  return { list: list?.scope === scope ? list : null, preview: preview?.scope === scope ? preview : null,
    download: download?.scope === scope ? download : null, pending, loadingMore, error, more, open,
    refresh };
}
