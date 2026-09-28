import {
  ApprovalConflictErrorResponse,
  ApprovalResult,
  ApproveInvoiceCaseRequest,
  AuditHistoryPage,
  CaseHandoffStatus,
  CaseVersionConflictErrorResponse,
  CreateInvoiceCaseRequest,
  CurrentUserView,
  FreezeReviewSnapshotRequest,
  InvoiceCaseDetail,
  InvoiceCasePage,
  InvoiceCaseStatus,
  MappingDecisionResult,
  MatchResultView,
  OpenSupplementRevisionRequest,
  RecordMappingDecisionRequest,
  RejectReviewRequest,
  ReplaceDraftLinesRequest,
  ReviewConflictErrorResponse,
  ReviewFreshness,
  ReviewSnapshotView,
  RunMatchRequest,
  SubmissionResult,
  SubmitInvoiceCaseRequest,
  SupplementRequestRequest,
} from "@/types/api";
import { generateTraceId } from "./id-generator";

export interface AuthCredentials {
  username: string;
  password: string;
}

export interface ApiResponse<T> {
  ok: boolean;
  status: number;
  data: T | null;
  error: CaseVersionConflictErrorResponse | ReviewConflictErrorResponse | ApprovalConflictErrorResponse | { code: string; message: string } | null;
  traceId: string | null;
}

const DEFAULT_TIMEOUT_MS = 15000;

export async function requestApi<T>(
  path: string,
  options: {
    method?: "GET" | "POST" | "PUT" | "DELETE";
    auth?: AuthCredentials | null;
    body?: unknown;
    traceId?: string;
    signal?: AbortSignal;
    timeoutMs?: number;
  } = {}
): Promise<ApiResponse<T>> {
  const method = options.method || "GET";
  const clientTrace = options.traceId || generateTraceId();

  const headers: Record<string, string> = {
    Accept: "application/json",
    "X-Trace-Id": clientTrace,
  };

  if (options.auth?.username && options.auth?.password) {
    const encoded = typeof window !== "undefined"
      ? window.btoa(`${options.auth.username}:${options.auth.password}`)
      : Buffer.from(`${options.auth.username}:${options.auth.password}`).toString("base64");
    headers["Authorization"] = `Basic ${encoded}`;
  }

  let requestBody: string | undefined = undefined;
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
    requestBody = JSON.stringify(options.body);
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => {
    controller.abort(new Error("Request timed out"));
  }, options.timeoutMs || DEFAULT_TIMEOUT_MS);

  if (options.signal) {
    options.signal.addEventListener("abort", () => controller.abort());
  }

  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  // Requests go through the Next.js proxy route to preserve headers and avoid browser CORS
  const url = `/api/proxy${normalizedPath}`;

  try {
    const res = await fetch(url, {
      method,
      headers,
      body: requestBody,
      signal: controller.signal,
      cache: "no-store",
    });

    clearTimeout(timeoutId);

    const resTrace = res.headers.get("x-trace-id") || clientTrace;
    const contentType = res.headers.get("content-type") || "";

    let parsedBody: unknown = null;
    if (contentType.includes("application/json")) {
      try {
        parsedBody = await res.json();
      } catch {
        parsedBody = null;
      }
    } else {
      const text = await res.text();
      if (text) {
        parsedBody = { message: text };
      }
    }

    if (res.ok) {
      return {
        ok: true,
        status: res.status,
        data: parsedBody as T,
        error: null,
        traceId: resTrace,
      };
    } else {
      const errorObj = (parsedBody && typeof parsedBody === "object")
        ? (parsedBody as Record<string, unknown>)
        : { code: `HTTP_${res.status}`, message: res.statusText || "Request failed" };

      return {
        ok: false,
        status: res.status,
        data: null,
        error: {
          code: (errorObj.code as string) || `HTTP_${res.status}`,
          message: (errorObj.message as string) || "Unknown error",
          ...(errorObj as object),
        } as ApiResponse<T>["error"],
        traceId: resTrace,
      };
    }
  } catch (err: unknown) {
    clearTimeout(timeoutId);
    const message = err instanceof Error ? err.message : "Network error";
    return {
      ok: false,
      status: 0,
      data: null,
      error: { code: "NETWORK_ERROR", message },
      traceId: clientTrace,
    };
  }
}

// Typed API operations
export const api = {
  getMe: (auth: AuthCredentials) =>
    requestApi<CurrentUserView>("/me", { auth }),

  listCases: (
    auth: AuthCredentials,
    filters: {
      status?: InvoiceCaseStatus | "";
      supplierId?: string;
      purchaseOrderId?: string;
      invoiceNumber?: string;
      submittedBy?: string;
      page?: number;
      size?: number;
    } = {}
  ) => {
    const params = new URLSearchParams();
    if (filters.status) params.set("status", filters.status);
    if (filters.supplierId) params.set("supplierId", filters.supplierId.trim());
    if (filters.purchaseOrderId) params.set("purchaseOrderId", filters.purchaseOrderId.trim());
    if (filters.invoiceNumber) params.set("invoiceNumber", filters.invoiceNumber.trim());
    if (filters.submittedBy) params.set("submittedBy", filters.submittedBy.trim());
    if (filters.page !== undefined) params.set("page", String(filters.page));
    if (filters.size !== undefined) params.set("size", String(filters.size));

    const qs = params.toString();
    return requestApi<InvoiceCasePage>(`/invoice-cases${qs ? `?${qs}` : ""}`, { auth });
  },

  getCase: (auth: AuthCredentials, id: string) =>
    requestApi<InvoiceCaseDetail>(`/invoice-cases/${id}`, { auth }),

  createCase: (auth: AuthCredentials, data: CreateInvoiceCaseRequest) =>
    requestApi<InvoiceCaseDetail>("/invoice-cases", {
      method: "POST",
      auth,
      body: data,
    }),

  replaceDraftLines: (auth: AuthCredentials, id: string, data: ReplaceDraftLinesRequest) =>
    requestApi<InvoiceCaseDetail>(`/invoice-cases/${id}/draft`, {
      method: "PUT",
      auth,
      body: data,
    }),

  submitCase: (auth: AuthCredentials, id: string, data: SubmitInvoiceCaseRequest) =>
    requestApi<SubmissionResult>(`/invoice-cases/${id}/submit`, {
      method: "POST",
      auth,
      body: data,
    }),

  openSupplementRevision: (auth: AuthCredentials, id: string, data: OpenSupplementRevisionRequest) =>
    requestApi<InvoiceCaseDetail>(`/invoice-cases/${id}/revisions`, {
      method: "POST",
      auth,
      body: data,
    }),

  runMatch: (auth: AuthCredentials, id: string, data: RunMatchRequest) =>
    requestApi<MatchResultView>(`/invoice-cases/${id}/match`, {
      method: "POST",
      auth,
      body: data,
    }),

  getLatestMatch: (auth: AuthCredentials, id: string) =>
    requestApi<MatchResultView>(`/invoice-cases/${id}/match`, { auth }),

  freezeReviewSnapshot: (auth: AuthCredentials, id: string, data: FreezeReviewSnapshotRequest) =>
    requestApi<ReviewSnapshotView>(`/invoice-cases/${id}/review-snapshots`, {
      method: "POST",
      auth,
      body: data,
    }),

  getLatestReviewSnapshot: (auth: AuthCredentials, id: string) =>
    requestApi<ReviewSnapshotView>(`/invoice-cases/${id}/review-snapshots/latest`, { auth }),

  getReviewFreshness: (auth: AuthCredentials, id: string, snapshotNumber: number) =>
    requestApi<ReviewFreshness>(`/invoice-cases/${id}/review-snapshots/${snapshotNumber}/freshness`, { auth }),

  recordMapping: (auth: AuthCredentials, id: string, data: RecordMappingDecisionRequest) =>
    requestApi<MappingDecisionResult>(`/invoice-cases/${id}/mapping-decisions`, {
      method: "POST",
      auth,
      body: data,
    }),

  requestSupplement: (auth: AuthCredentials, id: string, data: SupplementRequestRequest) =>
    requestApi<ReviewSnapshotView>(`/invoice-cases/${id}/supplement-requests`, {
      method: "POST",
      auth,
      body: data,
    }),

  rejectReview: (auth: AuthCredentials, id: string, data: RejectReviewRequest) =>
    requestApi<ReviewSnapshotView>(`/invoice-cases/${id}/reject`, {
      method: "POST",
      auth,
      body: data,
    }),

  approveCase: (auth: AuthCredentials, id: string, data: ApproveInvoiceCaseRequest) =>
    requestApi<ApprovalResult>(`/invoice-cases/${id}/approve`, {
      method: "POST",
      auth,
      body: data,
    }),

  getHandoffStatus: (auth: AuthCredentials, id: string) =>
    requestApi<CaseHandoffStatus>(`/invoice-cases/${id}/handoff`, { auth }),

  getAuditHistory: (auth: AuthCredentials, id: string, cursor?: string | null, limit = 20) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor) params.set("cursor", cursor);
    return requestApi<AuditHistoryPage>(`/invoice-cases/${id}/audit-entries?${params.toString()}`, { auth });
  },
};
