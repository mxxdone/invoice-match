"use client";

import React, { useState, useEffect, useCallback } from "react";
import { useAuth } from "@/lib/auth-context";
import { api } from "@/lib/api-client";
import { InvoiceCasePage, InvoiceCaseStatus, InvoiceCaseSummary } from "@/types/api";

interface CaseListViewProps {
  onSelectCase: (caseId: string) => void;
  onOpenNewCaseModal: () => void;
}

export function CaseListView({ onSelectCase, onOpenNewCaseModal }: CaseListViewProps) {
  const { credentials, user, hasRole } = useAuth();

  const [statusFilter, setStatusFilter] = useState<InvoiceCaseStatus | "">("");
  const [supplierIdFilter, setSupplierIdFilter] = useState("");
  const [poIdFilter, setPoIdFilter] = useState("");
  const [invoiceNumberFilter, setInvoiceNumberFilter] = useState("");
  const [page, setPage] = useState(0);
  const pageSize = 20;

  const [pageData, setPageData] = useState<InvoiceCasePage | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadCases = useCallback(async (targetPage = page) => {
    if (!credentials) return;
    setIsLoading(true);
    setError(null);

    const res = await api.listCases(credentials, {
      status: statusFilter,
      supplierId: supplierIdFilter,
      purchaseOrderId: poIdFilter,
      invoiceNumber: invoiceNumberFilter,
      page: targetPage,
      size: pageSize,
    });

    if (res.ok && res.data) {
      setPageData(res.data);
      setPage(res.data.page);
    } else {
      setError(res.error?.message || "사건 목록을 불러오는 중 오류가 발생했습니다.");
    }
    setIsLoading(false);
  }, [credentials, statusFilter, supplierIdFilter, poIdFilter, invoiceNumberFilter, page]);

  useEffect(() => {
    let active = true;
    if (!credentials) return;
    api
      .listCases(credentials, {
        status: statusFilter,
        supplierId: supplierIdFilter,
        purchaseOrderId: poIdFilter,
        invoiceNumber: invoiceNumberFilter,
        page: 0,
        size: pageSize,
      })
      .then((res) => {
        if (!active) return;
        if (res.ok && res.data) {
          setPageData(res.data);
          setPage(res.data.page);
        } else {
          setError(res.error?.message || "사건 목록을 불러오는 중 오류가 발생했습니다.");
        }
        setIsLoading(false);
      });
    return () => {
      active = false;
    };
  }, [credentials, statusFilter, supplierIdFilter, poIdFilter, invoiceNumberFilter]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    loadCases(0);
  };

  const handleReset = () => {
    setStatusFilter("");
    setSupplierIdFilter("");
    setPoIdFilter("");
    setInvoiceNumberFilter("");
    setPage(0);
    if (!credentials) return;
    api.listCases(credentials, { page: 0, size: pageSize }).then((res) => {
      if (res.ok && res.data) {
        setPageData(res.data);
        setPage(0);
      }
    });
  };

  const getStatusBadge = (status: InvoiceCaseStatus) => {
    switch (status) {
      case "DRAFT":
        return <span className="badge badge-draft">초안 (DRAFT)</span>;
      case "SUBMITTED":
        return <span className="badge badge-submitted">제출됨 (SUBMITTED)</span>;
      case "REVIEW_PENDING":
        return <span className="badge badge-review-pending">검토대기 (REVIEW_PENDING)</span>;
      case "SUPPLEMENT_REQUIRED":
        return <span className="badge badge-supplement">보완요청 (SUPPLEMENT_REQUIRED)</span>;
      case "REJECTED":
        return <span className="badge badge-rejected">거절됨 (REJECTED)</span>;
      case "EXPORT_PENDING":
        return <span className="badge badge-export-pending">ERP인계대기 (EXPORT_PENDING)</span>;
      case "EXPORTED":
        return <span className="badge badge-exported">ERP인계완료 (EXPORTED)</span>;
      default:
        return <span className="badge">{status}</span>;
    }
  };

  const isSubmitter = hasRole("SUBMITTER") && !hasRole("APPROVER") && !hasRole("OPERATOR");

  return (
    <main className="content-area">
      {/* Role Scoping Notice */}
      <aside aria-label="권한별 조회 범위 안내" style={{ marginBottom: "1rem" }}>
        {isSubmitter ? (
          <div className="alert alert-info" role="status">
            <span>📌 <strong>제출자(SUBMITTER) 권한 적용:</strong> 본인(<code>{user?.username}</code>)이 직접 제출한 청구 사건만 조회됩니다.</span>
          </div>
        ) : (
          <div className="alert alert-info" role="status">
            <span>📌 <strong>검토/운영 권한 적용:</strong> 전체 등록된 청구 사건을 조회하고 검토 및 대사를 진행할 수 있습니다.</span>
          </div>
        )}
      </aside>

      {/* Filter and Action Bar */}
      <div className="card">
        <form onSubmit={handleSearch} className="form-row" style={{ alignItems: "flex-end" }}>
          <div className="form-group" style={{ minWidth: "150px", flex: 1, marginBottom: 0 }}>
            <label htmlFor="filter-status" className="form-label">
              진행 상태
            </label>
            <select
              id="filter-status"
              className="form-select"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as InvoiceCaseStatus | "")}
            >
              <option value="">전체 상태</option>
              <option value="DRAFT">초안 (DRAFT)</option>
              <option value="SUBMITTED">제출됨 (SUBMITTED)</option>
              <option value="REVIEW_PENDING">검토대기 (REVIEW_PENDING)</option>
              <option value="SUPPLEMENT_REQUIRED">보완요청 (SUPPLEMENT_REQUIRED)</option>
              <option value="REJECTED">거절됨 (REJECTED)</option>
              <option value="EXPORT_PENDING">ERP인계대기 (EXPORT_PENDING)</option>
              <option value="EXPORTED">ERP인계완료 (EXPORTED)</option>
            </select>
          </div>

          <div className="form-group" style={{ minWidth: "140px", flex: 1, marginBottom: 0 }}>
            <label htmlFor="filter-supplier" className="form-label">
              공급사 ID
            </label>
            <input
              id="filter-supplier"
              type="text"
              className="form-input"
              value={supplierIdFilter}
              onChange={(e) => setSupplierIdFilter(e.target.value)}
              placeholder="예: SUP-1"
            />
          </div>

          <div className="form-group" style={{ minWidth: "140px", flex: 1, marginBottom: 0 }}>
            <label htmlFor="filter-po" className="form-label">
              발주서 번호
            </label>
            <input
              id="filter-po"
              type="text"
              className="form-input"
              value={poIdFilter}
              onChange={(e) => setPoIdFilter(e.target.value)}
              placeholder="예: PO-1001"
            />
          </div>

          <div className="form-group" style={{ minWidth: "140px", flex: 1, marginBottom: 0 }}>
            <label htmlFor="filter-invoice" className="form-label">
              인보이스(청구서) 번호
            </label>
            <input
              id="filter-invoice"
              type="text"
              className="form-input"
              value={invoiceNumberFilter}
              onChange={(e) => setInvoiceNumberFilter(e.target.value)}
              placeholder="예: INV-1"
            />
          </div>

          <div style={{ display: "flex", gap: "0.5rem" }}>
            <button type="submit" className="btn btn-primary" id="btn-filter-search">
              검색
            </button>
            <button type="button" className="btn btn-secondary" onClick={handleReset} id="btn-filter-reset">
              초기화
            </button>
          </div>
        </form>
      </div>

      {error && (
        <div className="alert alert-danger" role="alert">
          <span>{error}</span>
        </div>
      )}

      {/* Case List Table */}
      <div className="card" style={{ padding: 0 }}>
        <div className="card-header" style={{ padding: "1rem 1.25rem", margin: 0 }}>
          <h2 className="card-title">
            청구 사건 목록
            {pageData && (
              <span style={{ fontSize: "0.85rem", fontWeight: 400, color: "var(--color-text-muted)" }}>
                (총 {pageData.totalItems}건)
              </span>
            )}
          </h2>

          {hasRole("SUBMITTER") && (
            <button
              type="button"
              className="btn btn-primary btn-sm"
              onClick={onOpenNewCaseModal}
              id="btn-list-new-case"
            >
              + 새 청구서 작성
            </button>
          )}
        </div>

        <div className="table-container" style={{ border: "none" }}>
          <table className="data-table" aria-label="청구 사건 목록">
            <caption className="sr-only" style={{ display: "none" }}>등록된 청구 사건 목록 표</caption>
            <thead>
              <tr>
                <th scope="col">인보이스(청구서) 번호</th>
                <th scope="col">발주서 번호</th>
                <th scope="col">공급사 ID</th>
                <th scope="col">제출자</th>
                <th scope="col">상태</th>
                <th scope="col" className="text-center">버전</th>
                <th scope="col">등록일시</th>
                <th scope="col">제출일시</th>
                <th scope="col" className="text-center">작업</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr>
                  <td colSpan={9} style={{ textAlign: "center", padding: "3rem" }}>
                    <span className="spinner" aria-hidden="true" />
                    <span style={{ marginLeft: "0.5rem" }}>사건 목록을 불러오는 중...</span>
                  </td>
                </tr>
              ) : !pageData || pageData.items.length === 0 ? (
                <tr>
                  <td colSpan={9} style={{ textAlign: "center", padding: "3rem", color: "var(--color-text-muted)" }}>
                    조회된 청구 사건이 없습니다.
                  </td>
                </tr>
              ) : (
                pageData.items.map((item: InvoiceCaseSummary) => (
                  <tr
                    key={item.id}
                    className="clickable"
                    onClick={() => onSelectCase(item.id)}
                    tabIndex={0}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        onSelectCase(item.id);
                      }
                    }}
                    id={`case-row-${item.id}`}
                  >
                    <td>
                      <strong className="font-mono">{item.invoiceNumber}</strong>
                    </td>
                    <td>
                      <span className="font-mono">{item.purchaseOrderId}</span>
                    </td>
                    <td>
                      <span className="font-mono">{item.supplierId}</span>
                    </td>
                    <td>{item.submittedBy}</td>
                    <td>{getStatusBadge(item.status)}</td>
                    <td className="text-center">
                      <span className="badge font-mono" style={{ backgroundColor: "#f1f5f9" }}>
                        v{item.version}
                      </span>
                    </td>
                    <td style={{ fontSize: "0.8rem", color: "var(--color-text-muted)" }}>
                      {new Date(item.createdAt).toLocaleString("ko-KR")}
                    </td>
                    <td style={{ fontSize: "0.8rem", color: "var(--color-text-muted)" }}>
                      {item.submittedAt ? new Date(item.submittedAt).toLocaleString("ko-KR") : "-"}
                    </td>
                    <td className="text-center">
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        onClick={(e) => {
                          e.stopPropagation();
                          onSelectCase(item.id);
                        }}
                        id={`btn-open-${item.id}`}
                      >
                        상세 열기 →
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Server Pagination */}
        {pageData && pageData.totalPages > 1 && (
          <nav
            aria-label="페이지 이동 네비게이션"
            style={{
              padding: "0.75rem 1.25rem",
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              borderTop: "1px solid var(--color-border-light)",
            }}
          >
            <div style={{ fontSize: "0.85rem", color: "var(--color-text-muted)" }}>
              페이지 {pageData.page + 1} / {pageData.totalPages} (총 {pageData.totalItems}건)
            </div>

            <div style={{ display: "flex", gap: "0.5rem" }}>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => loadCases(pageData.page - 1)}
                disabled={pageData.page === 0 || isLoading}
                id="btn-page-prev"
              >
                이전
              </button>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => loadCases(pageData.page + 1)}
                disabled={!pageData.hasNext || isLoading}
                id="btn-page-next"
              >
                다음
              </button>
            </div>
          </nav>
        )}
      </div>
    </main>
  );
}
