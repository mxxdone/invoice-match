// Wire contract for the Core API read endpoints used by the first live slice.
// These shapes mirror the Java records exactly; a local design fixture is not a
// substitute for them.

export type InvoiceCaseStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'REVIEW_PENDING'
  | 'SUPPLEMENT_REQUIRED'
  | 'REJECTED'
  | 'EXPORT_PENDING'
  | 'EXPORTED';

// GET /api/invoice-cases -> InvoiceCaseSummary (one row of the server page).
export type InvoiceCaseSummary = {
  id: string;
  supplierId: string;
  purchaseOrderId: string;
  invoiceNumber: string;
  submittedBy: string;
  status: InvoiceCaseStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
  submittedAt: string | null;
};

// GET /api/invoice-cases -> InvoiceCasePage. The server names the total
// `totalItems`, not `totalElements`, and pages are 0-based.
export type InvoiceCasePage = {
  items: InvoiceCaseSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
};

// GET /api/me -> CurrentUserView.
export type CurrentUser = {
  username: string;
  roles: string[];
};

// Shared error body for 400/401/403/404/409/503 responses.
export type ApiErrorBody = {
  code: string;
  message: string;
};

export type StatusPresentation = { label: string; tone: string };

// Server statuses keep their approved semantic colours. The supplier name is
// not part of any read contract, so nothing here invents one.
export const statusPresentation: Record<InvoiceCaseStatus, StatusPresentation> = {
  DRAFT: { label: '작성 중', tone: 'neutral' },
  SUBMITTED: { label: '제출됨', tone: 'pending' },
  REVIEW_PENDING: { label: '검토 대기', tone: 'pending' },
  SUPPLEMENT_REQUIRED: { label: '보완 대기', tone: 'attention' },
  REJECTED: { label: '청구 거절', tone: 'rejected' },
  EXPORT_PENDING: { label: '인계 대기', tone: 'pending' },
  EXPORTED: { label: '인계 완료', tone: 'complete' },
};

export function presentStatus(status: string): StatusPresentation {
  return statusPresentation[status as InvoiceCaseStatus] ?? { label: status, tone: 'neutral' };
}

// Server instants are UTC ISO-8601; render them deterministically in UTC so the
// list and the server agree regardless of the viewer's timezone.
export function formatInstant(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'short',
    timeZone: 'UTC',
  }).format(date);
}
