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
import { Generation } from '../api/generation.ts';
import {
  classifyMutationFailure,
  intentSignature,
  isDefiniteFailure,
  newRequestId,
  type MutationFailure,
  type MutationOperation,
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

export type UnresolvedIntent = {
  operation: MutationOperation;
  requestId: string;
};

// One frozen write attempt. While it exists no other intent may be sent: the
// exact request id + serialized payload are retried, or the user re-reads the
// server state first. This is what stops a lost create/submit response from
// silently becoming a second case or a stale draft write.
type FrozenIntent = {
  operation: MutationOperation;
  requestId: string;
  signature: string;
  payload: unknown;
  completeStatus: ComposerStatus;
  invoke: (requestId: string, signal: AbortSignal) => Promise<unknown>;
  apply: (data: unknown) => void;
};

export function normalizeLines(lines: DraftLineInput[]): DraftLineInput[] {
  return lines.map((line, index) => ({
    lineNumber: index + 1,
    rawItemName: line.rawItemName.trim(),
    quantity: line.quantity,
    unitPrice: line.unitPrice,
    confirmedItemId: line.confirmedItemId && line.confirmedItemId.trim() ? line.confirmedItemId.trim() : null,
  }));
}

function supersededFailure(): MutationFailure {
  return {
    kind: 'error',
    status: 0,
    code: 'SUPERSEDED',
    message: '화면 또는 사건이 바뀌어 이전 요청 결과를 적용하지 않았습니다.',
    details: null,
  };
}

// The composer owns the case identity/version across the separate create →
// draft → submit operations. A run-identity guard (session + mount generation)
// drops any continuation whose screen has been replaced, so a late success or
// failure can never restore a previous case. A pending intent survives a
// failure so a manual retry of the identical intent reuses the same request id
// and payload; an uncertain failure is never auto-resent and blocks any other
// intent until it is retried or the case is re-read.
export function useCaseComposer({ credentials, sessionId, onUnauthorized }: ComposerOptions) {
  const [caseId, setCaseId] = useState<string | null>(null);
  const [caseVersion, setCaseVersion] = useState<number | null>(null);
  const [status, setStatus] = useState<ComposerStatus>('idle');
  const [failure, setFailure] = useState<MutationFailure | null>(null);
  const [hasPending, setHasPending] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [submittedBundleVersion, setSubmittedBundleVersion] = useState<number | null>(null);
  const [unresolved, setUnresolved] = useState<UnresolvedIntent | null>(null);
  // Owns the "a supplement revision was actually opened" fact so the page does
  // not keep a parallel ref that can drift from a retried revision.
  const [revisionOpened, setRevisionOpened] = useState(false);

  const caseRef = useRef<{ id: string | null; version: number | null }>({ id: null, version: null });
  const frozen = useRef<FrozenIntent | null>(null);
  const activeController = useRef<AbortController | null>(null);
  const sessionRef = useRef(sessionId);
  const credentialsRef = useRef(credentials);
  const alive = useRef(false);
  const generations = useRef(new Generation());

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
    setUnresolved(null);
    setRevisionOpened(false);
  }

  useEffect(() => {
    credentialsRef.current = credentials;
  }, [credentials]);

  useEffect(() => {
    alive.current = true;
    const guard = generations.current;
    return () => {
      alive.current = false;
      guard.next();
      activeController.current?.abort();
    };
  }, []);

  useEffect(() => {
    // A new session invalidates every intent and any loaded case. The visible
    // state is reset during render via sessionMarker; here only refs are cleared.
    sessionRef.current = sessionId;
    generations.current.next();
    frozen.current = null;
    activeController.current?.abort();
    caseRef.current = { id: null, version: null };
  }, [sessionId]);

  const isAlive = useCallback(
    (token: number) => alive.current && generations.current.isCurrent(token) && sessionRef.current === sessionId,
    [sessionId],
  );

  const clearIntent = useCallback(() => {
    frozen.current = null;
    setUnresolved(null);
    setHasPending(false);
  }, []);

  const execute = useCallback(
    async <T,>(
      operation: MutationOperation,
      businessPayload: unknown,
      completeStatus: ComposerStatus,
      invoke: (requestId: string, signal: AbortSignal) => Promise<T>,
      apply: (data: T) => void,
    ): Promise<ExecuteResult<T>> => {
      const current = credentialsRef.current;
      if (!current) {
        const unauthorized: MutationFailure = { kind: 'unauthorized', status: 0, code: 'UNAUTHENTICATED', message: '로그인이 필요합니다.', details: null };
        setFailure(unauthorized);
        return { ok: false, failure: unauthorized };
      }
      const token = generations.current.next();
      const signature = intentSignature(operation, businessPayload);
      const existing = frozen.current;
      if (existing && existing.signature !== signature) {
        const blocked: MutationFailure = {
          kind: 'error',
          status: 0,
          code: 'UNRESOLVED_INTENT',
          message: '이전 요청의 결과가 확정되지 않았습니다. 같은 요청을 다시 시도하거나 최신 서버 상태를 조회해 먼저 해소하세요.',
          details: null,
        };
        if (isAlive(token)) setFailure(blocked);
        return { ok: false, failure: blocked };
      }
      const intent: FrozenIntent = existing && existing.signature === signature
        ? existing
        : {
            operation,
            requestId: newRequestId('web'),
            signature,
            payload: businessPayload,
            completeStatus,
            invoke: invoke as (requestId: string, signal: AbortSignal) => Promise<unknown>,
            apply: apply as (data: unknown) => void,
          };

      setFailure(null);
      if (isAlive(token)) {
        setHasPending(true);
        setNotice(null);
      }
      const controller = new AbortController();
      activeController.current = controller;
      try {
        const data = await intent.invoke(intent.requestId, controller.signal);
        if (!isAlive(token)) {
          return { ok: false, failure: supersededFailure() };
        }
        intent.apply(data);
        // Reaching the terminal status here (not only in the wrapper) is what
        // lets an explicit retry complete the original operation and navigate.
        setStatus(intent.completeStatus);
        clearIntent();
        return { ok: true, data: data as T };
      } catch (caught) {
        if (caught instanceof Error && caught.name === 'AbortError') {
          return { ok: false, failure: { kind: 'error', status: 0, code: 'ABORTED', message: '요청이 취소되었습니다.', details: null } };
        }
        if (!isAlive(token)) {
          return { ok: false, failure: supersededFailure() };
        }
        const classified = classifyMutationFailure(caught);
        if (isDefiniteFailure(classified.kind)) {
          clearIntent();
        } else {
          frozen.current = intent;
          setUnresolved({ operation, requestId: intent.requestId });
          setHasPending(true);
        }
        setFailure(classified);
        setStatus('failed');
        if (classified.kind === 'unauthorized') {
          onUnauthorized();
        }
        return { ok: false, failure: classified };
      }
    },
    [clearIntent, isAlive, onUnauthorized],
  );

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
      const result = await execute(
        'create',
        payload,
        'created',
        (requestId, signal) => createInvoiceCase(credentialsRef.current as Credentials, { requestId, ...payload }, signal),
        (data) => {
          caseRef.current = { id: data.id, version: data.version };
          setCaseId(data.id);
          setCaseVersion(data.version);
        },
      );
      if (!result.ok) return null;
      return result.data.id;
    },
    [execute],
  );

  const saveDraft = useCallback(
    async (header: DraftHeader, lines: DraftLineInput[]): Promise<boolean> => {
      const id = await createOrReuse(header);
      if (!id) return false;
      const version = caseRef.current.version;
      if (version === null) return false;
      const normalized = normalizeLines(lines);
      const payload = { caseId: id, expectedCaseVersion: version, lines: normalized };
      setStatus('saving');
      const result = await execute(
        'draft',
        payload,
        'draft-saved',
        (requestId, signal) => replaceDraft(credentialsRef.current as Credentials, id, { requestId, expectedCaseVersion: version, lines: normalized }, signal),
        (data) => {
          caseRef.current.version = data.version;
          setCaseVersion(data.version);
        },
      );
      if (!result.ok) return false;
      return true;
    },
    [createOrReuse, execute],
  );

  const submit = useCallback(async (): Promise<string | null> => {
    const id = caseRef.current.id;
    const version = caseRef.current.version;
    if (!id || version === null) return null;
    const payload = { caseId: id, expectedCaseVersion: version };
    setStatus('submitting');
    const result = await execute(
      'submit',
      payload,
      'submitted',
      (requestId, signal) => submitInvoiceCase(credentialsRef.current as Credentials, id, { requestId, expectedCaseVersion: version }, signal),
      (data) => {
        caseRef.current.version = data.version;
        setCaseVersion(data.version);
        setSubmittedBundleVersion(data.evidenceBundle.version);
      },
    );
    if (!result.ok) return null;
    return id;
  }, [execute]);

  const openRevision = useCallback(
    async (existingCaseId: string, expectedCaseVersion: number): Promise<boolean> => {
      const payload = { caseId: existingCaseId, expectedCaseVersion };
      setStatus('opening');
      const result = await execute(
        'revision',
        payload,
        'draft-saved',
        (requestId, signal) => openSupplementRevision(credentialsRef.current as Credentials, existingCaseId, { requestId, expectedCaseVersion }, signal),
        (data) => {
          caseRef.current = { id: existingCaseId, version: data.version };
          setCaseId(existingCaseId);
          setCaseVersion(data.version);
          // The hook owns this fact so a retried revision also marks the page's
          // revision as opened; the page no longer keeps its own ref.
          setRevisionOpened(true);
        },
      );
      if (!result.ok) return false;
      return true;
    },
    [execute],
  );

  // Manual retry of the exact frozen intent (same request id + payload). It is
  // the only way an uncertain create/draft/submit is re-sent.
  const retry = useCallback(async (): Promise<boolean> => {
    const intent = frozen.current;
    if (!intent) return false;
    const result = await execute(intent.operation, intent.payload, intent.completeStatus, intent.invoke, intent.apply);
    return result.ok;
  }, [execute]);

  const adoptLatest = useCallback((id: string, version: number, statusNote?: string) => {
    // While a request is unresolved, a server read is NOT proof that the
    // operation landed (the read may see another writer's change), so it must
    // not change identity/version/inputs or drop the frozen request. The only
    // sanctioned resolution is the exact replay in retry(); 409-confirmed
    // failures have no frozen intent and still adopt the latest version here.
    if (frozen.current) {
      setNotice(statusNote ?? '서버 상태를 다시 읽었습니다. 미확정 작업은 같은 요청을 다시 시도해야 해소됩니다.');
      return;
    }
    caseRef.current = { id, version };
    setCaseId(id);
    setCaseVersion(version);
    setFailure(null);
    setNotice(statusNote ?? '서버의 최신 청구서 버전을 반영했습니다. 내용을 다시 확인하고 저장·제출하세요.');
  }, []);

  const reset = useCallback(() => {
    generations.current.next();
    frozen.current = null;
    activeController.current?.abort();
    caseRef.current = { id: null, version: null };
    setCaseId(null);
    setCaseVersion(null);
    setStatus('idle');
    setFailure(null);
    setHasPending(false);
    setNotice(null);
    setSubmittedBundleVersion(null);
    setUnresolved(null);
    setRevisionOpened(false);
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
    unresolved,
    revisionOpened,
    createOrReuse,
    saveDraft,
    submit,
    openRevision,
    retry,
    adoptLatest,
    reset,
    clearFailure,
  };
}
