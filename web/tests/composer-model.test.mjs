import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiRequestError } from '../src/app/api/transport.ts';
import {
  classifyMutationFailure,
  failureDetailLines,
  intentSignature,
  isDefiniteFailure,
  needsRefresh,
  resolveIntent,
} from '../src/app/cases/composer-model.ts';

test('failures are classified by status and keep the server code and message', () => {
  assert.deepEqual(classifyMutationFailure(new ApiRequestError(401, 'UNAUTHENTICATED', 'no')), {
    kind: 'unauthorized', status: 401, code: 'UNAUTHENTICATED', message: 'no', details: null,
  });
  assert.equal(classifyMutationFailure(new ApiRequestError(403, 'FORBIDDEN', 'no')).kind, 'forbidden');
  assert.equal(classifyMutationFailure(new ApiRequestError(409, 'CASE_VERSION_CONFLICT', 'stale')).kind, 'conflict');
  assert.equal(classifyMutationFailure(new ApiRequestError(400, 'VALIDATION_ERROR', 'bad')).kind, 'validation');
  assert.equal(classifyMutationFailure(new ApiRequestError(413, 'PAYLOAD_TOO_LARGE', 'big')).kind, 'validation');
  assert.equal(classifyMutationFailure(new ApiRequestError(422, 'X', 'bad')).kind, 'validation');
});

test('network and 5xx failures are uncertain, never a definite failure', () => {
  assert.equal(classifyMutationFailure(new ApiRequestError(0, 'NETWORK_ERROR', 'down')).kind, 'uncertain');
  assert.equal(classifyMutationFailure(new ApiRequestError(502, 'CORE_API_REDIRECT', 'x')).kind, 'uncertain');
  assert.equal(classifyMutationFailure(new ApiRequestError(503, 'CORE_API_UNAVAILABLE', 'x')).kind, 'uncertain');
  assert.equal(classifyMutationFailure(new ApiRequestError(504, 'CORE_API_TIMEOUT', 'x')).kind, 'uncertain');
  assert.equal(isDefiniteFailure('uncertain'), false);
});

test('a validation/conflict failure is definite and a conflict also needs a refresh', () => {
  assert.equal(isDefiniteFailure('validation'), true);
  assert.equal(isDefiniteFailure('conflict'), true);
  assert.equal(needsRefresh('conflict'), true);
  assert.equal(needsRefresh('validation'), false);
});

test('the identical intent reuses its request id and any change starts a new one', () => {
  const factory = (() => { let n = 0; return () => `req-${++n}`; })();
  const payload = { supplierId: 'SUP-1', purchaseOrderId: 'PO-1', invoiceNumber: 'INV-1' };
  const first = resolveIntent(null, 'create', payload, factory);
  assert.equal(first.requestId, 'req-1');
  const retry = resolveIntent(first, 'create', { ...payload }, factory);
  assert.equal(retry.requestId, 'req-1');
  const changed = resolveIntent(first, 'create', { ...payload, invoiceNumber: 'INV-2' }, factory);
  assert.equal(changed.requestId, 'req-2');
  const otherOperation = resolveIntent(first, 'draft', { caseId: 'c', expectedCaseVersion: 1, lines: [] }, factory);
  assert.equal(otherOperation.requestId, 'req-3');
});

test('the signature is stable for equal payloads and distinct for a changed version', () => {
  assert.equal(intentSignature('submit', { caseId: 'c', expectedCaseVersion: 1 }), intentSignature('submit', { caseId: 'c', expectedCaseVersion: 1 }));
  assert.notEqual(intentSignature('submit', { caseId: 'c', expectedCaseVersion: 1 }), intentSignature('submit', { caseId: 'c', expectedCaseVersion: 2 }));
});

test('a conflict failure keeps and renders its structured cause', () => {
  const conflict = classifyMutationFailure(new ApiRequestError(409, 'STALE_REVIEW_TARGET', 'stale', {
    reasons: ['CASE_VERSION', 'EVIDENCE_BUNDLE'],
    currentCaseVersion: 7,
    shortfalls: [{ receiptLineId: 'RCL-1', remaining: 5 }],
  }));
  assert.equal(conflict.kind, 'conflict');
  assert.equal(conflict.details.reasons.length, 2);
  const lines = failureDetailLines(conflict);
  assert.ok(lines.includes('청구 버전이 변경됨'));
  assert.ok(lines.includes('증빙 버전이 변경됨'));
  assert.ok(lines.some((line) => line.includes('서버 최신 청구서 버전: v7')));
  assert.ok(lines.some((line) => line.includes('RCL-1')));
});

test('details are null for a body without them and the presenter returns nothing', () => {
  const plain = classifyMutationFailure(new ApiRequestError(409, 'CASE_STATE_CONFLICT', 'no'));
  assert.equal(plain.details, null);
  assert.deepEqual(failureDetailLines(plain), []);
});
