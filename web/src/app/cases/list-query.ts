// URL <-> filter mapping for the live work list.
//
// The list filters live in the URL so navigating into a case detail and back
// (browser back/forward or the detail's "목록으로" link) restores the same
// server query. The serialized form is exactly `buildInvoiceCaseQuery`, so the
// URL the browser keeps and the query the server receives can never drift.

import { buildInvoiceCaseQuery, type InvoiceCaseFilters, type SearchField } from '../api/query';

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

function positiveInt(value: string | null, fallback: number): number {
  if (value === null) return fallback;
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed < 0) return fallback;
  return parsed;
}

function firstNonBlank(value: string | null): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

function clampSize(size: number): number {
  if (!Number.isFinite(size) || size < 1) return DEFAULT_PAGE_SIZE;
  return Math.min(size, 100);
}

export function filtersFromSearchParams(params: URLSearchParams): InvoiceCaseFilters {
  const status = params.get('status');
  const purchaseOrderId = firstNonBlank(params.get('purchaseOrderId'));
  const invoiceNumber = firstNonBlank(params.get('invoiceNumber'));
  const searchField: SearchField = purchaseOrderId && !invoiceNumber
    ? 'purchaseOrderId'
    : 'invoiceNumber';
  const searchValue = searchField === 'purchaseOrderId' ? purchaseOrderId : invoiceNumber;
  return {
    status: status && STATUSES.has(status) ? status : 'all',
    supplierId: firstNonBlank(params.get('supplierId')),
    submittedBy: firstNonBlank(params.get('submittedBy')),
    submittedFrom: firstNonBlank(params.get('submittedFrom')),
    submittedTo: firstNonBlank(params.get('submittedTo')),
    searchField,
    searchValue,
    page: Math.max(0, positiveInt(params.get('page'), 0)),
    size: clampSize(positiveInt(params.get('size'), DEFAULT_PAGE_SIZE)),
  };
}

export function searchParamsFromFilters(filters: InvoiceCaseFilters): URLSearchParams {
  return new URLSearchParams(buildInvoiceCaseQuery(filters));
}

export function searchFromFilters(filters: InvoiceCaseFilters): string {
  return searchParamsFromFilters(filters).toString();
}
