// Pure helpers for the live write composer. They hold no React or fetch state
// so the failure classification, request-id reuse and payload signatures can be
// asserted directly.

import { ApiRequestError } from '../api/transport';

// A mutation failure keeps its server code and message. `uncertain` means the
// server may or may not have applied the write (a lost response, timeout or
// connection drop); the caller must not auto-resend and must reuse the exact
// same intent on a manual retry.
export type MutationFailureKind =
  | 'validation'
  | 'conflict'
  | 'unauthorized'
  | 'forbidden'
  | 'uncertain'
  | 'error';

export type MutationFailure = {
  kind: MutationFailureKind;
  status: number;
  code: string;
  message: string;
};

export type MutationOperation =
  | 'create'
  | 'draft'
  | 'submit'
  | 'revision'
  | 'match'
  | 'freeze'
  | 'mapping'
  | 'supplement'
  | 'reject'
  | 'approve';

export type PendingIntent = {
  operation: MutationOperation;
  signature: string;
  requestId: string;
};

const DEFAULT_MESSAGE = '요청을 처리하지 못했습니다.';

export function classifyMutationFailure(error: unknown): MutationFailure {
  if (error instanceof ApiRequestError) {
    const { status, code, message } = error;
    const detail = message || DEFAULT_MESSAGE;
    if (status === 401) return { kind: 'unauthorized', status, code, message: detail };
    if (status === 403) return { kind: 'forbidden', status, code, message: detail };
    if (status === 409) return { kind: 'conflict', status, code, message: detail };
    if (status === 400 || status === 413 || status === 422) {
      return { kind: 'validation', status, code, message: detail };
    }
    // 0 is a transport failure and 5xx is a server/proxy failure. Neither is
    // proof the write did not land, so both are uncertain.
    if (status === 0 || status >= 500) {
      return { kind: 'uncertain', status, code, message: detail };
    }
    return { kind: 'error', status, code, message: detail };
  }
  if (error instanceof Error && error.name === 'AbortError') {
    return { kind: 'error', status: 0, code: 'ABORTED', message: '요청이 취소되었습니다.' };
  }
  return {
    kind: 'error',
    status: 0,
    code: 'UNKNOWN',
    message: error instanceof Error ? error.message : DEFAULT_MESSAGE,
  };
}

// A definite failure can only be resolved by changing the input (validation), or
// by refreshing the case version first (conflict). An uncertain failure keeps
// its intent so a retry sends the identical request id and payload.
export function isDefiniteFailure(kind: MutationFailureKind): boolean {
  return kind === 'validation' || kind === 'conflict' || kind === 'forbidden' || kind === 'unauthorized';
}

export function needsRefresh(kind: MutationFailureKind): boolean {
  return kind === 'conflict';
}

export function intentSignature(operation: MutationOperation, businessPayload: unknown): string {
  return `${operation}:${JSON.stringify(businessPayload)}`;
}

// Resolves the request id for an intent: a retry of the identical intent reuses
// the id, any other intent gets a new one. The server stores the idempotency
// result, so the reuse is what makes a manual retry safe.
export function resolveIntent(
  current: PendingIntent | null,
  operation: MutationOperation,
  businessPayload: unknown,
  requestIdFactory: () => string,
): PendingIntent {
  const signature = intentSignature(operation, businessPayload);
  if (current && current.signature === signature) {
    return current;
  }
  return { operation, signature, requestId: requestIdFactory() };
}

let fallbackCounter = 0;

export function newRequestId(prefix = 'web'): string {
  const uuid = globalThis.crypto?.randomUUID?.();
  if (uuid) {
    return `${prefix}-${uuid}`;
  }
  fallbackCounter += 1;
  return `${prefix}-${Date.now().toString(36)}-${fallbackCounter.toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}
