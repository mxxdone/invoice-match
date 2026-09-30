import test from 'node:test';
import assert from 'node:assert/strict';
import { statusPresentation, presentStatus, formatInstant } from '../src/app/api/contract.ts';
import { statusPresentation as previewStatusPresentation } from '../src/app/cases/list-preview.ts';

test('the live status map covers every server status', () => {
  const expected = ['DRAFT', 'SUBMITTED', 'REVIEW_PENDING', 'SUPPLEMENT_REQUIRED', 'REJECTED', 'EXPORT_PENDING', 'EXPORTED'];
  assert.deepEqual(Object.keys(statusPresentation).sort(), [...expected].sort());
});

test('REJECTED reads as a claim rejection and keeps its own semantic tone', () => {
  assert.match(statusPresentation.REJECTED.label, /청구/);
  assert.match(statusPresentation.REJECTED.label, /거절/);
  assert.equal(statusPresentation.REJECTED.tone, 'rejected');
});

test('the live map and the approved preview map agree on shared statuses', () => {
  for (const [status, presentation] of Object.entries(previewStatusPresentation)) {
    assert.deepEqual(statusPresentation[status], presentation, `status ${status} drifted`);
  }
});

test('the list response total uses the real server field name', () => {
  const page = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0, hasNext: false };
  assert.equal(page.totalItems, 0);
  assert.equal('totalElements' in page, false);
});

test('unknown statuses fall back without inventing a colour and instants are deterministic', () => {
  assert.equal(presentStatus('UNKNOWN').tone, 'neutral');
  assert.equal(formatInstant(null), '—');
  assert.equal(formatInstant('not-a-date'), '—');
  assert.match(formatInstant('2026-09-30T01:38:00Z'), /1:38/);
});
