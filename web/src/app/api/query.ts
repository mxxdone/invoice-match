// Pure construction of the `GET /api/invoice-cases` query. The server owns
// normalization, partial matching, the AND combination and the row scope, so
// the browser sends the raw fragments verbatim and never filters a page itself.

export type SearchField = 'invoiceNumber' | 'purchaseOrderId';

export type InvoiceCaseFilters = {
  status: string | null;
  supplierId: string | null;
  submittedBy: string | null;
  searchField: SearchField;
  searchValue: string | null;
  page: number;
  size: number;
};

export function buildInvoiceCaseQuery(filters: InvoiceCaseFilters): Record<string, string> {
  const query: Record<string, string> = {
    page: String(filters.page),
    size: String(filters.size),
  };
  if (filters.status && filters.status !== 'all') {
    query.status = filters.status;
  }
  const supplierId = filters.supplierId?.trim();
  if (supplierId) {
    query.supplierId = supplierId;
  }
  const submittedBy = filters.submittedBy?.trim();
  if (submittedBy) {
    query.submittedBy = submittedBy;
  }
  const searchValue = filters.searchValue?.trim();
  if (searchValue) {
    query[filters.searchField] = searchValue;
  }
  return query;
}
