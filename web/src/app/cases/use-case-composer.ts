'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  createInvoiceCase,
  openSupplementRevision,
  replaceDraft,
  submitInvoiceCase,
  type DraftLineInput,
} from '../api/client.ts';
import type { Credentials } from '../api/transport.ts';
import {
  classifyMutationFailure,
  isDefiniteFailure,
  newRequestId,
  resolveIntent,
  type MutationFailure,
  type MutationOperation,
  type PendingIntent,
} from './composer-model.ts';

export type ComposerStatus =
  | 'idle'
  | 'creating'
  | 'created'
  | 'saving'
  | 'draft-saved'
  | 'submitting'
  | 'submitted'
  | 'opening'
  | 'failed';

export type DraftHeader = {
  supplierId: string;
  purchaseOrderId: string;
  invoiceNumber: string;
};

export type ComposerOptions = {
  credentials: Credentials | null;
  sessionId: number;
  onUnauthorized: () => void;
};

export type ExecuteResult<T> = { ok: true; data: T } | { ok: false; failure: MutationFailure };

export function normalizeLines(lines: DraftLineInput[]): DraftLineInput[] {
  return lines.map((line, index) => ({
    lineNumber: index + 1,
    rawItemName: line.rawItemName.trim(),
    quantity: line.quantity,
    unitPrice: line.unitPrice,
    confirmedItemId: line.confirmedItemId && line.confirmedItemId.trim() ? line.confirmedItemId.trim() : null,
  }));
}

// The composer owns the case identity/version across the separate create →
// draft → submit operations. A pending intent survives a failure so a manual
// retry of the identical intent reuses the same request id and payload; an
// uncertain failure is never auto-resent and the caller is told the result is
// unknown. An edit changes the payload signature and therefore starts a new
// intent. No credential or payload is persisted outside this component.
export function useCaseComposer({ credentials, sessionId, onUnauthorized }: ComposerOptions) {
  const [caseId, setCaseId] = useState<string | null>(null);
  const [caseVersion, setCaseVersion] = useState<number | null>(null);
  const [status, setStatus] = useState<ComposerStatus>('idle');
  const [failure, setFailure] = useState<MutationFailure | null>(null);
  const [hasPending, setHasPending] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [submittedBundleVersion, setSubmittedBundleVersion] = useState<number | null>(null);

  const caseRef = useRef<{ id: string | null; version: number | null }>({ id: null, version: null });
  const pending = useRef<PendingIntent | null>(null);
  const activeController = useRef<AbortController | null>(null);
  const sessionRef = useRef(sessionId);
  const credentialsRef = useRef(credentials);
  const mounted = useRef(true);

  // A new session (login/logout) resets the visible composer during render,
  // using the same "adjust state when a prop changes" pattern as the list. The
  // intent/ref state is cleared in the effect below so no mutation from the
  // previous session can apply here.
  const [sessionMarker, setSessionMarker] = useState(sessionId);
  if (sessionMarker !== sessionId) {
    setSessionMarker(sessionId);
    setCaseId(null);
    setCaseVersion(null);
    setStatus('idle');
    setFailure(null);
    setHasPending(false);
    setNotice(null);
    setSubmittedBundleVersion(null);
  }

  useEffect(() => {
    credentialsRef.current = credentials;
  }, [credentials]);

  useEffect(() => () => {
    mounted.current = false;
    activeController.current?.abort();
  }, []);

  useEffect(() => {
    sessionRef.current = sessionId;
    pending.current = null;
    activeController.current?.abort();
    caseRef.current = { id: null, version: null };
  }, [sessionId]);

  const isCurrent = useCallback(
    () => mounted.current && sessionRef.current === sessionId,
    [sessionId],
  );

  const execute = useCallback(
    async <T,>(
      operation: MutationOperation,
      businessPayload: unknown,
      call: (requestId: string, signal: AbortSignal) => Promise<T>,
    ): Promise<ExecuteResult<T>> => {
      const current = credentialsRef.current;
      if (!current) {
        const unauthorized: MutationFailure = {
          kind: 'unauthorized',
          status: 0,
          code: 'UNAUTHENTICATED',
          message: '로그인이 필요합니다.',
        };
        setFailure(unauthorized);
        return { ok: false, failure: unauthorized };
      }
      const intent = resolveIntent(pending.current, operation, businessPayload, () => newRequestId('web'));
      pending.current = intent;
      if (isCurrent()) {
        setHasPending(true);
        setFailure(null);
        setNotice(null);
      }
      const controller = new AbortController();
      activeController.current = controller;
      try {
        const data = await call(intent.requestId, controller.signal);
        if (!isCurrent()) return { ok: true, data };
        pending.current = null;
        setHasPending(false);
        return { ok: true, data };
      } catch (caught) {
        if (caught instanceof Error && caught.name === 'AbortError') {
          return {
            ok: false,
            failure: { kind: 'error', status: 0, code: 'ABORTED', message: '요청이 취소되었습니다.' },
          };
        }
        const classified = classifyMutationFailure(caught);
        if (isDefiniteFailure(classified.kind)) {
          // The next attempt must start a new intent (a changed input or a
          // refreshed case version), so the old id is not replayed.
          pending.current = null;
        }
        if (isCurrent()) {
          setFailure(classified);
          setHasPending(pending.current !== null);
          setStatus('failed');
        }
        if (classified.kind === 'unauthorized') {
          onUnauthorized();
        }
        return { ok: false, failure: classified };
      }
    },
    [isCurrent, onUnauthorized],
  );

  // Creates the case once and reuses it afterwards, including after a create
  // that succeeded but whose later draft operation failed.
  const createOrReuse = useCallback(
    async (header: DraftHeader): Promise<string | null> => {
      if (caseRef.current.id) {
        return caseRef.current.id;
      }
      const payload = {
        supplierId: header.supplierId.trim(),
        purchaseOrderId: header.purchaseOrderId.trim(),
        invoiceNumber: header.invoiceNumber.trim(),
      };
      setStatus('creating');
      const result = await execute('create', payload, (requestId, signal) =>
        createInvoiceCase(credentialsRef.current as Credentials, { requestId, ...payload }, signal),
      );
      if (!result.ok) return null;
      caseRef.current = { id: result.data.id, version: result.data.version };
      setCaseId(result.data.id);
      setCaseVersion(result.data.version);
      setStatus('created');
      return result.data.id;
    },
    [execute],
  );

  const saveDraft = useCallback(
    async (header: DraftHeader, lines: DraftLineInput[]): Promise<boolean> => {
      const id = await createOrReuse(header);
      if (!id || !isCurrent()) return false;
      const version = caseRef.current.version;
      if (version === null) return false;
      const normalized = normalizeLines(lines);
      const payload = { caseId: id, expectedCaseVersion: version, lines: normalized };
      setStatus('saving');
      const result = await execute('draft', payload, (requestId, signal) =>
        replaceDraft(
          credentialsRef.current as Credentials,
          id,
          { requestId, expectedCaseVersion: version, lines: normalized },
          signal,
        ),
      );
      if (!result.ok) return false;
      caseRef.current.version = result.data.version;
      setCaseVersion(result.data.version);
      setStatus('draft-saved');
      return true;
    },
    [createOrReuse, execute, isCurrent],
  );

  const submit = useCallback(async (): Promise<boolean> => {
    const id = caseRef.current.id;
    const version = caseRef.current.version;
    if (!id || version === null || !isCurrent()) return false;
    const payload = { caseId: id, expectedCaseVersion: version };
    setStatus('submitting');
    const result = await execute('submit', payload, (requestId, signal) =>
      submitInvoiceCase(
        credentialsRef.current as Credentials,
        id,
        { requestId, expectedCaseVersion: version },
        signal,
      ),
    );
    if (!result.ok) return false;
    caseRef.current.version = result.data.version;
    setCaseVersion(result.data.version);
    setSubmittedBundleVersion(result.data.evidenceBundle.version);
    setStatus('submitted');
    return true;
  }, [execute, isCurrent]);

  // Opens a supplement revision for an existing case and adopts its new version.
  const openRevision = useCallback(
    async (existingCaseId: string, expectedCaseVersion: number): Promise<boolean> => {
      const payload = { caseId: existingCaseId, expectedCaseVersion };
      setStatus('opening');
      const result = await execute('revision', payload, (requestId, signal) =>
        openSupplementRevision(
          credentialsRef.current as Credentials,
          existingCaseId,
          { requestId, expectedCaseVersion },
          signal,
        ),
      );
      if (!result.ok) return false;
      caseRef.current = { id: existingCaseId, version: result.data.version };
      setCaseId(existingCaseId);
      setCaseVersion(result.data.version);
      setStatus('draft-saved');
      return true;
    },
    [execute],
  );

  // Syncs the composer with a freshly re-read server version after a 409. The
  // UI must re-confirm before submitting again; this never auto-retries.
  const adoptLatest = useCallback((id: string, version: number, statusNote?: string) => {
    caseRef.current = { id, version };
    setCaseId(id);
    setCaseVersion(version);
    setFailure(null);
    setNotice(statusNote ?? '서버의 최신 청구서 버전을 반영했습니다. 내용을 다시 확인하고 저장·제출하세요.');
  }, []);

  const reset = useCallback(() => {
    pending.current = null;
    activeController.current?.abort();
    caseRef.current = { id: null, version: null };
    setCaseId(null);
    setCaseVersion(null);
    setStatus('idle');
    setFailure(null);
    setHasPending(false);
    setNotice(null);
    setSubmittedBundleVersion(null);
  }, []);

  const clearFailure = useCallback(() => setFailure(null), []);

  return {
    caseId,
    caseVersion,
    status,
    failure,
    hasPending,
    notice,
    submittedBundleVersion,
    createOrReuse,
    saveDraft,
    submit,
    openRevision,
    adoptLatest,
    reset,
    clearFailure,
  };
}
