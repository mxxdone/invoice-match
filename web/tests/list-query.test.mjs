import test from 'node:test';
import assert from 'node:assert/strict';
import { filtersFromSearchParams, searchFromFilters, DEFAULT_PAGE_SIZE } from '../src/app/cases/list-query.ts';

test('an empty URL yields the default list query', () => {
  const filters = filtersFromSearchParams(new URLSearchParams(''));
  assert.deepEqual(filters, {
    status: 'all',
    supplierId: null,
    submittedBy: null,
    submittedFrom: null,
    submittedTo: null,
    searchField: 'invoiceNumber',
    searchValue: null,
    page: 0,
    size: DEFAULT_PAGE_SIZE,
  });
});

test('the full list state round-trips through the URL unchanged', () => {
  const url = new URLSearchParams({
    status: 'SUPPLEMENT_REQUIRED',
    supplierId: 'SUP-1001',
    submittedBy: 'submitter-02',
    submittedFrom: '2026-09-30T00:00:00.000000+09:00',
    submittedTo: '2026-09-30T23:59:59.999999+09:00',
    invoiceNumber: 'inv 0142',
    page: '3',
    size: '50',
  });
  const filters = filtersFromSearchParams(url);
  assert.equal(filters.status, 'SUPPLEMENT_REQUIRED');
  assert.equal(filters.supplierId, 'SUP-1001');
  assert.equal(filters.submittedBy, 'submitter-02');
  assert.equal(filters.searchField, 'invoiceNumber');
  assert.equal(filters.searchValue, 'inv 0142');
  assert.equal(filters.page, 3);
  assert.equal(filters.size, 50);
  const roundTripped = filtersFromSearchParams(new URLSearchParams(searchFromFilters(filters)));
  assert.deepEqual(roundTripped, filters);
});

test('a purchase-order search selects the PO field and an unknown status falls back to all', () => {
  const filters = filtersFromSearchParams(new URLSearchParams('purchaseOrderId=po-1001&status=BOGUS'));
  assert.equal(filters.searchField, 'purchaseOrderId');
  assert.equal(filters.searchValue, 'po-1001');
  assert.equal(filters.status, 'all');
});

test('a negative page, a zero size and an unbounded status are clamped', () => {
  assert.equal(filtersFromSearchParams(new URLSearchParams('page=-4')).page, 0);
  assert.equal(filtersFromSearchParams(new URLSearchParams('size=0')).size, DEFAULT_PAGE_SIZE);
  assert.equal(filtersFromSearchParams(new URLSearchParams('size=9999')).size, 100);
});

test('a page or size that is not a plain integer is rejected instead of truncated', () => {
  assert.equal(filtersFromSearchParams(new URLSearchParams('page=2oops')).page, 0);
  assert.equal(filtersFromSearchParams(new URLSearchParams('page=1.5')).page, 0);
  assert.equal(filtersFromSearchParams(new URLSearchParams('size=2oops')).size, DEFAULT_PAGE_SIZE);
  assert.equal(filtersFromSearchParams(new URLSearchParams('size=50abc')).size, DEFAULT_PAGE_SIZE);
  assert.equal(filtersFromSearchParams(new URLSearchParams('page=99999999999999999999')).page, 0);
});

test('a KST range is accepted only in the canonical form, and an invalid or reversed one resets', () => {
  const canonical = new URLSearchParams({
    submittedFrom: '2026-09-30T00:00:00.000000+09:00',
    submittedTo: '2026-09-30T23:59:59.999999+09:00',
  });
  const parsed = filtersFromSearchParams(canonical);
  assert.equal(parsed.submittedFrom, canonical.get('submittedFrom'));
  assert.equal(parsed.submittedTo, canonical.get('submittedTo'));

  const reversed = filtersFromSearchParams(new URLSearchParams({
    submittedFrom: '2026-10-01T00:00:00.000000+09:00',
    submittedTo: '2026-09-30T23:59:59.999999+09:00',
  }));
  assert.equal(reversed.submittedFrom, null);
  assert.equal(reversed.submittedTo, null);

  const bogus = filtersFromSearchParams(new URLSearchParams({ submittedFrom: 'not-a-date' }));
  assert.equal(bogus.submittedFrom, null);

  const nonCanonical = filtersFromSearchParams(new URLSearchParams({ submittedFrom: '2026-09-30T00:00:00Z' }));
  assert.equal(nonCanonical.submittedFrom, null);
});
