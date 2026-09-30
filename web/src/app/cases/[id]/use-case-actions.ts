'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  approveInvoiceCase,
  freezeReviewSnapshot,
  recordMappingDecision,
  requestSupplement,
  rejectInvoiceCase,
  runMatch,
  type MappingDecisionInput,
} from '../../api/client.ts';
// The hook owns the idempotency request id, so callers pass the intent without it.
type MappingInput = Omit<MappingDecisionInput, 'requestId'>;
import type { Credentials } from '../../api/transport.ts';
import {
  classifyMutationFailure,
  isDefiniteFailure,
  newRequestId,
  resolveIntent,
  type MutationFailure,
  type MutationOperation,
  type PendingIntent,
} from '../composer-model.ts';

export type CaseActionsOptions = {
  credentials: Credentials | null;
  sessionId: number;
  caseId: string;
  onUnauthorized: () => void;
  // Called after a successful write so the page re-reads the authoritative
  // state instead of applying the response locally.
  onCompleted: (operation: MutationOperation) => void;
};

export function useCaseActions({
  credentials,
  sessionId,
  caseId,
  onUnauthorized,
  onCompleted,
}: CaseActionsOptions) {
  const [pendingAction, setPendingAction] = useState<MutationOperation | null>(null);
  const [failure, setFailure] = useState<MutationFailure | null>(null);
  const [lastSuccess, setLastSuccess] = useState<MutationOperation | null>(null);

  const pending = useRef<PendingIntent | null>(null);
  const activeController = useRef<AbortController | null>(null);
  const sessionRef = useRef(sessionId);
  const credentialsRef = useRef(credentials);
  const mounted = useRef(true);

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
  }, [sessionId]);

  const isCurrent = useCallback(
    () => mounted.current && sessionRef.current === sessionId,
    [sessionId],
  );

  const run = useCallback(
    async <T,>(
      operation: MutationOperation,
      businessPayload: unknown,
      call: (requestId: string, signal: AbortSignal) => Promise<T>,
    ): Promise<boolean> => {
      const current = credentialsRef.current;
      if (!current) {
        const unauthorized: MutationFailure = { kind: 'unauthorized', status: 0, code: 'UNAUTHENTICATED', message: '로그인이 필요합니다.' };
        setFailure(unauthorized);
        return false;
      }
      const intent = resolveIntent(pending.current, operation, businessPayload, () => newRequestId('web'));
      pending.current = intent;
      if (isCurrent()) {
        setPendingAction(operation);
        setFailure(null);
      }
      const controller = new AbortController();
      activeController.current = controller;
      try {
        await call(intent.requestId, controller.signal);
        if (!isCurrent()) return true;
        pending.current = null;
        setPendingAction(null);
        setLastSuccess(operation);
        // A completed write reloads the case; the server values replace the
        // previous view instead of being guessed from the response.
        onCompleted(operation);
        return true;
      } catch (caught) {
        if (caught instanceof Error && caught.name === 'AbortError') {
          return false;
        }
        const classified = classifyMutationFailure(caught);
        if (isDefiniteFailure(classified.kind)) {
          pending.current = null;
        }
        if (isCurrent()) {
          setPendingAction(null);
          setFailure(classified);
        }
        if (classified.kind === 'unauthorized') {
          onUnauthorized();
        }
        return false;
      }
    },
    [isCurrent, onCompleted, onUnauthorized],
  );

  return {
    pendingAction,
    failure,
    lastSuccess,
    clearFailure: useCallback(() => setFailure(null), []),
    runMatch: useCallback(
      () => run('match', { caseId }, (requestId, signal) => runMatch(credentialsRef.current as Credentials, caseId, { requestId }, signal)),
      [run, caseId],
    ),
    freezeSnapshot: useCallback(
      (expectedCaseVersion: number) => run('freeze', { caseId, expectedCaseVersion }, (requestId, signal) =>
        freezeReviewSnapshot(credentialsRef.current as Credentials, caseId, { requestId, expectedCaseVersion }, signal)),
      [run, caseId],
    ),
    recordMapping: useCallback(
      (input: MappingInput) => run('mapping', { caseId, ...input }, (requestId, signal) =>
        recordMappingDecision(credentialsRef.current as Credentials, caseId, { ...input, requestId }, signal)),
      [run, caseId],
    ),
    requestSupplement: useCallback(
      (input: { expectedCaseVersion: number; reviewSnapshotId: string; reviewPayloadHash: string; reason: string }) =>
        run('supplement', { caseId, ...input }, (requestId, signal) =>
          requestSupplement(credentialsRef.current as Credentials, caseId, { ...input, requestId }, signal)),
      [run, caseId],
    ),
    reject: useCallback(
      (input: { expectedCaseVersion: number; reviewSnapshotId: string; reviewPayloadHash: string; reason: string }) =>
        run('reject', { caseId, ...input }, (requestId, signal) =>
          rejectInvoiceCase(credentialsRef.current as Credentials, caseId, { ...input, requestId }, signal)),
      [run, caseId],
    ),
    approve: useCallback(
      (input: { expectedCaseVersion: number; reviewSnapshotId: string; reviewPayloadHash: string }) =>
        run('approve', { caseId, ...input }, (requestId, signal) =>
          approveInvoiceCase(credentialsRef.current as Credentials, caseId, { ...input, requestId }, signal)),
      [run, caseId],
    ),
  };
}
