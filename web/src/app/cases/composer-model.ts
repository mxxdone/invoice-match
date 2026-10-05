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
  // The parsed server error body (reasons, shortfalls, current/expected
  // versions, ...), kept so the UI can show the exact cause instead of only the
  // top-level message.
  details: Record<string, unknown> | null;
};

function failure(
  kind: MutationFailureKind,
  status: number,
  code: string,
  message: string,
  details: Record<string, unknown> | null,
): MutationFailure {
  return { kind, status, code, message, details };
}

export type MutationOperation =
  | 'create'
  | 'draft'
  | 'submit'
  | 'revision'
  | 'match'
  | 'proposal'
  | 'freeze'
  | 'mapping'
  | 'supplement'
  | 'reject'
  | 'approve'
  | 'graphReserve'
  | 'graphSuccessor'
  | 'graphConfirm';

// Graph intents are scoped to the exact waiting run/interrupt/version, so a
// graph or review change must never let an old response mark the new one done.
export function isGraphOperation(operation: MutationOperation): boolean {
  return operation === 'graphReserve' || operation === 'graphSuccessor' || operation === 'graphConfirm';
}

export type PendingIntent = {
  operation: MutationOperation;
  signature: string;
  requestId: string;
};

const DEFAULT_MESSAGE = '요청을 처리하지 못했습니다.';

export function classifyMutationFailure(error: unknown): MutationFailure {
  if (error instanceof ApiRequestError) {
    const { status, code, message, details } = error;
    const detail = message || DEFAULT_MESSAGE;
    if (status === 401) return failure('unauthorized', status, code, detail, details);
    if (status === 403) return failure('forbidden', status, code, detail, details);
    if (status === 409) return failure('conflict', status, code, detail, details);
    if (status === 400 || status === 413 || status === 422) {
      return failure('validation', status, code, detail, details);
    }
    // 0 is a transport failure and 5xx is a server/proxy failure. Neither is
    // proof the write did not land, so both are uncertain.
    if (status === 0 || status >= 500) {
      return failure('uncertain', status, code, detail, details);
    }
    return failure('error', status, code, detail, details);
  }
  if (error instanceof Error && error.name === 'AbortError') {
    return failure('error', 0, 'ABORTED', '요청이 취소되었습니다.', null);
  }
  return failure('error', 0, 'UNKNOWN', error instanceof Error ? error.message : DEFAULT_MESSAGE, null);
}

const FRESHNESS_REASON_LABELS: Record<string, string> = {
  CASE_STATE: '청구 상태가 변경됨',
  CASE_VERSION: '청구 버전이 변경됨',
  EVIDENCE_BUNDLE: '증빙 버전이 변경됨',
  MATCH_RESULT: '대사 결과가 변경됨',
  MAPPING: '매핑이 변경됨',
  PURCHASING_SNAPSHOT: '구매 스냅샷이 변경됨',
  SUPERSEDED: '더 새로운 검토 대상이 있음',
};

// Extracts the structured cause lines the server returned (stale reasons,
// allocation shortfalls, version mismatch). The UI shows these verbatim; it
// never re-derives or auto-resolves them.
export function failureDetailLines(failure: MutationFailure): string[] {
  const details = failure.details;
  if (!details) return [];
  const lines: string[] = [];
  const reasons = details.reasons;
  if (Array.isArray(reasons)) {
    for (const reason of reasons) {
      if (typeof reason === 'string') lines.push(FRESHNESS_REASON_LABELS[reason] ?? reason);
    }
  }
  const shortfalls = details.shortfalls;
  if (Array.isArray(shortfalls) && shortfalls.length > 0) {
    lines.push(`잔량/배분 부족: ${JSON.stringify(shortfalls)}`);
  }
  if (typeof details.currentCaseVersion === 'number') {
    lines.push(`서버 최신 청구서 버전: v${details.currentCaseVersion}`);
  }
  if (typeof details.expectedVersion === 'number' && typeof details.actualVersion === 'number') {
    lines.push(`버전 불일치: 요청 v${details.expectedVersion} · 서버 v${details.actualVersion}`);
  }
  return lines;
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
