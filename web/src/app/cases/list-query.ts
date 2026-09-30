// URL <-> filter mapping for the live work list.
//
// The list filters live in the URL so navigating into a case detail and back
// (browser back/forward or the detail's "목록으로" link) restores the same
// server query. The serialized form is exactly `buildInvoiceCaseQuery`, so the
// URL the browser keeps and the query the server receives can never drift.
//
// Parsing is strict: a page/size must be a plain non-negative integer (so
// `2oops` or `1.5` is rejected, not truncated) and a date range must round-trip
// to the canonical KST boundaries this app writes. An invalid, reversed or
// non-canonical range is reset to no filter rather than forwarded as-is.

import { buildInvoiceCaseQuery, type InvoiceCaseFilters, type SearchField } from '../api/query';
import { resolveSubmittedRange } from '../api/daterange';

export const DEFAULT_PAGE_SIZE = 20;

const STATUSES = new Set([
  'DRAFT',
  'SUBMITTED',
  'REVIEW_PENDING',
  'SUPPLEMENT_REQUIRED',
  'REJECTED',
  'EXPORT_PENDING',
  'EXPORTED',
]);

const PLAIN_INT = /^\d+$/;
const DAY_ONLY = /^\d{4}-\d{2}-\d{2}$/;

function strictInt(value: string | null): number | null {
  if (value === null || !PLAIN_INT.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

function firstNonBlank(value: string | null): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

function clampSize(size: number | null): number {
  if (size === null || size < 1) return DEFAULT_PAGE_SIZE;
  return Math.min(size, 100);
}

function resolveRange(params: URLSearchParams): { from: string | null; to: string | null } {
  const rawFrom = params.get('submittedFrom');
  const rawTo = params.get('submittedTo');
  const fromDate = rawFrom ? rawFrom.slice(0, 10) : '';
  const toDate = rawTo ? rawTo.slice(0, 10) : '';
  if ((rawFrom !== null && !DAY_ONLY.test(fromDate)) || (rawTo !== null && !DAY_ONLY.test(toDate))) {
    return { from: null, to: null };
  }
  const resolved = resolveSubmittedRange(fromDate, toDate);
  if (resolved.error) {
    return { from: null, to: null };
  }
  // Only accept the canonical KST instant form this app produces. If either
  // side is non-canonical the whole range is reset, so a valid end can never be
  // combined with a start the app did not write.
  const fromIsCanonical = resolved.from === rawFrom;
  const toIsCanonical = resolved.to === rawTo;
  if (!fromIsCanonical || !toIsCanonical) {
    return { from: null, to: null };
  }
  return { from: resolved.from, to: resolved.to };
}

export function filtersFromSearchParams(params: URLSearchParams): InvoiceCaseFilters {
  const status = params.get('status');
  const purchaseOrderId = firstNonBlank(params.get('purchaseOrderId'));
  const invoiceNumber = firstNonBlank(params.get('invoiceNumber'));
  const searchField: SearchField = purchaseOrderId && !invoiceNumber
    ? 'purchaseOrderId'
    : 'invoiceNumber';
  const searchValue = searchField === 'purchaseOrderId' ? purchaseOrderId : invoiceNumber;
  const range = resolveRange(params);
  return {
    status: status && STATUSES.has(status) ? status : 'all',
    supplierId: firstNonBlank(params.get('supplierId')),
    submittedBy: firstNonBlank(params.get('submittedBy')),
    submittedFrom: range.from,
    submittedTo: range.to,
    searchField,
    searchValue,
    page: Math.max(0, strictInt(params.get('page')) ?? 0),
    size: clampSize(strictInt(params.get('size'))),
  };
}

export function searchParamsFromFilters(filters: InvoiceCaseFilters): URLSearchParams {
  return new URLSearchParams(buildInvoiceCaseQuery(filters));
}

export function searchFromFilters(filters: InvoiceCaseFilters): string {
  return searchParamsFromFilters(filters).toString();
}
