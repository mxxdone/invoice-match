'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  approveInvoiceCase,
  confirmGraphReview,
  freezeReviewSnapshot,
  recordMappingDecision,
  requestSupplement,
  rejectInvoiceCase,
  reserveGraph,
  reserveGraphSuccessor,
  runMatch,
  reserveProposal,
  type GraphReviewInput,
  type MappingDecisionInput,
} from '../../api/client.ts';
import type { SelectedProposal } from '../../api/contract.ts';
import type { Credentials } from '../../api/transport.ts';
import { Generation } from '../../api/generation.ts';
import {
  classifyMutationFailure,
  intentSignature,
  isDefiniteFailure,
  isGraphOperation,
  newRequestId,
  type MutationFailure,
  type MutationOperation,
} from '../composer-model.ts';

// The hook owns the idempotency request id, so callers pass the intent without it.
type MappingInput = Omit<MappingDecisionInput, 'requestId'>;

export type GraphConfirmCommand = { graphId: string } & GraphReviewInput;

export type CaseActionsOptions = {
  credentials: Credentials | null;
  sessionId: number;
  caseId: string;
  // A change to the exact waiting graph/interrupt/review identity invalidates
  // only graph intents; page intents stay bound to the session/case.
  graphIdentity?: string;
  onUnauthorized: () => void;
  // Called after a successful write so the page re-reads the authoritative
  // state instead of applying the response locally.
  onCompleted: (operation: MutationOperation) => void;
};

export type UnresolvedAction = { operation: MutationOperation; requestId: string };

type FrozenAction = {
  operation: MutationOperation;
  requestId: string;
  signature: string;
  identity: string;
  payload: unknown;
  invoke: (requestId: string, signal: AbortSignal) => Promise<unknown>;
};

export function useCaseActions({
  credentials,
  sessionId,
  caseId,
  graphIdentity = '',
  onUnauthorized,
  onCompleted,
}: CaseActionsOptions) {
  const [pendingAction, setPendingAction] = useState<MutationOperation | null>(null);
  const [failure, setFailure] = useState<MutationFailure | null>(null);
  const [lastSuccess, setLastSuccess] = useState<MutationOperation | null>(null);
  const [unresolved, setUnresolved] = useState<UnresolvedAction | null>(null);

  const frozen = useRef<FrozenAction | null>(null);
  const activeController = useRef<AbortController | null>(null);
  const sessionRef = useRef(sessionId);
  const caseIdRef = useRef(caseId);
  const graphRef = useRef(graphIdentity);
  const credentialsRef = useRef(credentials);
  const alive = useRef(false);
  const generations = useRef(new Generation());

  // A session or case change resets the visible action state during render; the
  // effect below only clears refs so no late response can mark another case done.
  const [identityMarker, setIdentityMarker] = useState(`${sessionId}#${caseId}`);
  if (identityMarker !== `${sessionId}#${caseId}`) {
    setIdentityMarker(`${sessionId}#${caseId}`);
    setUnresolved(null);
    setPendingAction(null);
    setFailure(null);
    setLastSuccess(null);
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
    sessionRef.current = sessionId;
    caseIdRef.current = caseId;
    generations.current.next();
    frozen.current = null;
    activeController.current?.abort();
  }, [sessionId, caseId]);

  // A graph/interrupt/review change drops only graph intents; a page action that
  // is in flight or unresolved keeps its identity and is not invalidated.
  useEffect(() => {
    graphRef.current = graphIdentity;
    const existing = frozen.current;
    if (existing && isGraphOperation(existing.operation)) {
      frozen.current = null;
      setUnresolved(null);
      setPendingAction(null);
      return;
    }
    setUnresolved((current) => (current && isGraphOperation(current.operation) ? null : current));
    setPendingAction((current) => (current && isGraphOperation(current) ? null : current));
  }, [graphIdentity]);

  const run = useCallback(
    async <T,>(
      operation: MutationOperation,
      businessPayload: unknown,
      invoke: (requestId: string, signal: AbortSignal) => Promise<T>,
    ): Promise<boolean> => {
      const current = credentialsRef.current;
      const runCaseId = caseId;
      if (!current) {
        const unauthorized: MutationFailure = { kind: 'unauthorized', status: 0, code: 'UNAUTHENTICATED', message: '로그인이 필요합니다.', details: null };
        setFailure(unauthorized);
        return false;
      }
      const graphAtStart = graphRef.current;
      const token = generations.current.next();
      const signature = intentSignature(operation, businessPayload);
      const existing = frozen.current;
      if (existing && existing.signature !== signature) {
        const blocked: MutationFailure = {
          kind: 'error',
          status: 0,
          code: 'UNRESOLVED_INTENT',
          message: '이전 요청의 결과가 확정되지 않았습니다. 같은 요청을 다시 시도하거나 최신 자료를 다시 조회해 먼저 해소하세요.',
          details: null,
        };
        if (alive.current && generations.current.isCurrent(token)) setFailure(blocked);
        return false;
      }
      const intent: FrozenAction = existing && existing.signature === signature
        ? existing
        : {
            operation,
            requestId: newRequestId('web'),
            signature,
            identity: isGraphOperation(operation) ? `${sessionId}#${runCaseId}#${graphAtStart}` : `${sessionId}#${runCaseId}`,
            payload: businessPayload,
            invoke,
          };

      setFailure(null);
      if (alive.current && generations.current.isCurrent(token)) setPendingAction(operation);
      const controller = new AbortController();
      activeController.current = controller;
      const stillCurrent = () => alive.current
        && generations.current.isCurrent(token)
        && sessionRef.current === sessionId
        && caseIdRef.current === runCaseId
        && (!isGraphOperation(operation) || graphRef.current === graphAtStart);
      try {
        await intent.invoke(intent.requestId, controller.signal);
        if (!stillCurrent()) return false;
        frozen.current = null;
        setUnresolved(null);
        setPendingAction(null);
        setLastSuccess(operation);
        // A completed write reloads the case; server values replace the view.
        onCompleted(operation);
        return true;
      } catch (caught) {
        if (caught instanceof Error && caught.name === 'AbortError') {
          return false;
        }
        if (!stillCurrent()) return false;
        const classified = classifyMutationFailure(caught);
        if (isDefiniteFailure(classified.kind)) {
          frozen.current = null;
          setUnresolved(null);
        } else {
          frozen.current = intent;
          setUnresolved({ operation, requestId: intent.requestId });
        }
        setPendingAction(null);
        setFailure(classified);
        if (classified.kind === 'unauthorized') {
          onUnauthorized();
        }
        return false;
      }
    },
    [caseId, onCompleted, onUnauthorized, sessionId],
  );

  const retry = useCallback(async (): Promise<boolean> => {
    const intent = frozen.current;
    if (!intent) return false;
    const runCaseId = caseIdRef.current;
    const expectedIdentity = isGraphOperation(intent.operation)
      ? `${sessionRef.current}#${runCaseId}#${graphRef.current}`
      : `${sessionRef.current}#${runCaseId}`;
    if (intent.identity !== expectedIdentity) {
      frozen.current = null;
      setUnresolved(null);
      return false;
    }
    const token = generations.current.next();
    setFailure(null);
    setPendingAction(intent.operation);
    const controller = new AbortController();
    activeController.current = controller;
    try {
      await intent.invoke(intent.requestId, controller.signal);
      if (!alive.current || !generations.current.isCurrent(token) || caseIdRef.current !== runCaseId || intent.identity !== expectedIdentity) return false;
      frozen.current = null;
      setUnresolved(null);
      setPendingAction(null);
      setLastSuccess(intent.operation);
      onCompleted(intent.operation);
      return true;
    } catch (caught) {
      if (!alive.current || !generations.current.isCurrent(token) || caseIdRef.current !== runCaseId) return false;
      const classified = classifyMutationFailure(caught);
      if (classified.kind === 'unauthorized') onUnauthorized();
      setPendingAction(null);
      setFailure(classified);
      return false;
    }
  }, [onCompleted, onUnauthorized]);

  return {
    pendingAction,
    failure,
    lastSuccess,
    unresolved,
    clearFailure: useCallback(() => setFailure(null), []),
    retry,
    runMatch: useCallback(
      () => run('match', { caseId }, (requestId, signal) => runMatch(credentialsRef.current as Credentials, caseId, { requestId }, signal)),
      [run, caseId],
    ),
    freezeSnapshot: useCallback(
      (expectedCaseVersion: number, proposal?: SelectedProposal) => run('freeze', { caseId, expectedCaseVersion, ...proposal }, (requestId, signal) =>
        freezeReviewSnapshot(credentialsRef.current as Credentials, caseId, { requestId, expectedCaseVersion, ...proposal }, signal)),
      [run, caseId],
    ),
    reserveProposal: useCallback(
      (expectedCaseVersion: number) => run('proposal', { caseId, expectedCaseVersion }, (requestId, signal) =>
        reserveProposal(credentialsRef.current as Credentials, caseId, { requestId, expectedCaseVersion }, signal)),
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
    graphReserve: useCallback(
      (expectedCaseVersion: number) => run('graphReserve', { caseId, expectedCaseVersion }, (requestId, signal) =>
        reserveGraph(credentialsRef.current as Credentials, caseId, { requestId, expectedCaseVersion }, signal)),
      [run, caseId],
    ),
    graphSuccessor: useCallback(
      (predecessorId: string, expectedCaseVersion: number) => run('graphSuccessor', { caseId, predecessorId, expectedCaseVersion }, (requestId, signal) =>
        reserveGraphSuccessor(credentialsRef.current as Credentials, caseId, predecessorId, { requestId, expectedCaseVersion }, signal)),
      [run, caseId],
    ),
    graphConfirm: useCallback(
      (command: GraphConfirmCommand) => run('graphConfirm', { caseId, ...command }, (requestId, signal) => {
        const { graphId, ...review } = command;
        return confirmGraphReview(credentialsRef.current as Credentials, caseId, graphId, { ...review, requestId }, signal);
      }),
      [run, caseId],
    ),
  };
}
