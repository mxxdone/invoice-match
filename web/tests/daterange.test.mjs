import test from 'node:test';
import assert from 'node:assert/strict';
import {
  KST_OFFSET,
  kstDayStart,
  kstDayEndInclusive,
  resolveSubmittedRange,
} from '../src/app/api/daterange.ts';

test('a KST day starts at midnight Asia/Seoul', () => {
  assert.equal(KST_OFFSET, '+09:00');
  assert.equal(kstDayStart('2026-03-01'), '2026-03-01T00:00:00.000000+09:00');
  assert.equal(new Date(kstDayStart('2026-03-01')).toISOString(), '2026-02-28T15:00:00.000Z');
});

test('the inclusive end is the last microsecond before the next KST day', () => {
  assert.equal(kstDayEndInclusive('2026-12-31'), '2026-12-31T23:59:59.999999+09:00');
  const end = new Date(kstDayEndInclusive('2026-12-31'));
  const nextStart = new Date(kstDayStart('2027-01-01'));
  assert.ok(end < nextStart, 'the next day 00:00 must be excluded');
  assert.equal(nextStart.getTime() - end.getTime(), 1);
});

test('month-end and leap-day dates resolve as single inclusive days', () => {
  const monthEnd = resolveSubmittedRange('2026-01-31', '2026-01-31');
  assert.equal(monthEnd.error, null);
  assert.equal(monthEnd.from, '2026-01-31T00:00:00.000000+09:00');
  assert.equal(monthEnd.to, '2026-01-31T23:59:59.999999+09:00');
  const leapDay = resolveSubmittedRange('2028-02-29', '2028-02-29');
  assert.equal(leapDay.error, null);
});

test('an impossible calendar date is rejected', () => {
  const result = resolveSubmittedRange('2026-02-29', '2026-03-01');
  assert.ok(result.error);
  assert.equal(result.from, null);
  assert.equal(result.to, null);
});

test('a reversed range reports an error and applies nothing', () => {
  const result = resolveSubmittedRange('2026-09-30', '2026-09-01');
  assert.match(result.error, /늦을 수 없습니다/);
  assert.equal(result.from, null);
  assert.equal(result.to, null);
});

test('an empty range means no filter and one-sided ranges are allowed', () => {
  assert.deepEqual(resolveSubmittedRange('', ''), { from: null, to: null, error: null });
  const startOnly = resolveSubmittedRange('2026-09-30', '');
  assert.equal(startOnly.from, '2026-09-30T00:00:00.000000+09:00');
  assert.equal(startOnly.to, null);
  const endOnly = resolveSubmittedRange('', '2026-09-30');
  assert.equal(endOnly.from, null);
  assert.equal(endOnly.to, '2026-09-30T23:59:59.999999+09:00');
});
