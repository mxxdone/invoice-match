"use client";

import React, { useState, useEffect, useCallback } from "react";
import {
  CaseHandoffStatus,
  InvoiceCaseDetail,
  MatchResultView,
  ReviewFreshness,
  ReviewSnapshotView,
} from "@/types/api";
import { useAuth } from "@/lib/auth-context";
import { api } from "@/lib/api-client";
import { AppError, ErrorBanner } from "./ErrorBanner";
import { DraftEditor } from "./DraftEditor";
import { ThreeWayComparison } from "./ThreeWayComparison";
import { ReviewDecisionPanel } from "./ReviewDecisionPanel";
import { HandoffStatusCard } from "./HandoffStatusCard";
import { AuditHistoryPanel } from "./AuditHistoryPanel";

interface CaseDetailViewProps {
  caseId: string;
  onBack: () => void;
}

export function CaseDetailView({ caseId, onBack }: CaseDetailViewProps) {
  const { credentials, hasRole } = useAuth();

  const [caseDetail, setCaseDetail] = useState<InvoiceCaseDetail | null>(null);
  const [matchResult, setMatchResult] = useState<MatchResultView | null>(null);
  const [activeSnapshot, setActiveSnapshot] = useState<ReviewSnapshotView | null>(null);
  const [freshness, setFreshness] = useState<ReviewFreshness | null>(null);
  const [handoff, setHandoff] = useState<CaseHandoffStatus | null>(null);

  const [isLoading, setIsLoading] = useState(true);
  const [isHandoffLoading, setIsHandoffLoading] = useState(false);
  const [error, setError] = useState<AppError | null>(null);

  const loadCaseData = useCallback(async () => {
    if (!credentials) return;
    setIsLoading(true);
    setError(null);

    // 1. Load case detail
    const caseRes = await api.getCase(credentials, caseId);
    if (!caseRes.ok || !caseRes.data) {
      setError({
        code: caseRes.error?.code || `HTTP_${caseRes.status}`,
        message: caseRes.error?.message || "사건 상세를 불러올 수 없습니다.",
        traceId: caseRes.traceId,
      });
      setIsLoading(false);
      return;
    }
    setCaseDetail(caseRes.data);

    // 2. Load match & snapshot if user has APPROVER or OPERATOR role
    const canReadReview = hasRole("APPROVER") || hasRole("OPERATOR");
    if (canReadReview) {
      const matchRes = await api.getLatestMatch(credentials, caseId);
      if (matchRes.ok && matchRes.data) {
        setMatchResult(matchRes.data);
      } else {
        setMatchResult(null);
      }

      const snapRes = await api.getLatestReviewSnapshot(credentials, caseId);
      if (snapRes.ok && snapRes.data) {
        setActiveSnapshot(snapRes.data);

        // 3. Load freshness for latest snapshot
        const freshRes = await api.getReviewFreshness(credentials, caseId, snapRes.data.snapshotNumber);
        if (freshRes.ok && freshRes.data) {
          setFreshness(freshRes.data);
        } else {
          setFreshness(null);
        }
      } else {
        setActiveSnapshot(null);
        setFreshness(null);
      }
    }

    // 4. Load handoff
    const handoffRes = await api.getHandoffStatus(credentials, caseId);
    if (handoffRes.ok && handoffRes.data) {
      setHandoff(handoffRes.data);
    } else {
      setHandoff(null);
    }

    setIsLoading(false);
  }, [credentials, caseId, hasRole]);

  useEffect(() => {
    let active = true;
    if (!credentials) return;

    api.getCase(credentials, caseId).then(async (caseRes) => {
      if (!active) return;
      if (!caseRes.ok || !caseRes.data) {
        setError({
          code: caseRes.error?.code || `HTTP_${caseRes.status}`,
          message: caseRes.error?.message || "사건 상세를 불러올 수 없습니다.",
          traceId: caseRes.traceId,
        });
        setIsLoading(false);
        return;
      }
      setCaseDetail(caseRes.data);

      const canReadReview = hasRole("APPROVER") || hasRole("OPERATOR");
      if (canReadReview) {
        const [matchRes, snapRes] = await Promise.all([
          api.getLatestMatch(credentials, caseId),
          api.getLatestReviewSnapshot(credentials, caseId),
        ]);
        if (!active) return;
        if (matchRes.ok && matchRes.data) {
          setMatchResult(matchRes.data);
        }
        if (snapRes.ok && snapRes.data) {
          setActiveSnapshot(snapRes.data);
          const freshRes = await api.getReviewFreshness(credentials, caseId, snapRes.data.snapshotNumber);
          if (active && freshRes.ok && freshRes.data) {
            setFreshness(freshRes.data);
          }
        }
      }

      const handoffRes = await api.getHandoffStatus(credentials, caseId);
      if (active && handoffRes.ok && handoffRes.data) {
        setHandoff(handoffRes.data);
      }
      if (active) {
        setIsLoading(false);
      }
    });

    return () => {
      active = false;
    };
  }, [credentials, caseId, hasRole]);

  const refreshHandoffOnly = async () => {
    if (!credentials) return;
    setIsHandoffLoading(true);
    const handoffRes = await api.getHandoffStatus(credentials, caseId);
    setIsHandoffLoading(false);
    if (handoffRes.ok && handoffRes.data) {
      setHandoff(handoffRes.data);
    }
  };

  const getStatusBadge = (status?: string) => {
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
        return <span className="badge">{status || "-"}</span>;
    }
  };

  if (isLoading && !caseDetail) {
    return (
      <main className="content-area">
        <div className="card" style={{ textAlign: "center", padding: "4rem" }}>
          <span className="spinner" aria-hidden="true" />
          <span style={{ marginLeft: "0.75rem" }}>사건 상세 및 연관 상태를 불러오는 중...</span>
        </div>
      </main>
    );
  }

  if (!caseDetail) {
    return (
      <main className="content-area">
        <ErrorBanner error={error} onDismiss={() => setError(null)} onRefreshLatest={loadCaseData} />
        <button type="button" className="btn btn-secondary" onClick={onBack}>
          ← 사건 목록으로 돌아가기
        </button>
      </main>
    );
  }

  return (
    <main className="content-area">
      {/* Top Breadcrumb & Action bar */}
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "1rem" }}>
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={onBack}
          id="btn-back-to-list"
        >
          ← 사건 목록으로 돌아가기
        </button>

        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={loadCaseData}
          disabled={isLoading}
          id="btn-refresh-all"
        >
          {isLoading ? "새로고침 중..." : "🔄 전체 새로고침"}
        </button>
      </div>

      {/* Error / 409 Stale Conflict Banner */}
      <ErrorBanner error={error} onDismiss={() => setError(null)} onRefreshLatest={loadCaseData} />

      {/* Case Header Summary Card */}
      <section className="card" aria-labelledby="case-header-title">
        <div className="card-header">
          <h1 id="case-header-title" className="card-title">
            인보이스(청구서): <span className="font-mono">{caseDetail.invoiceNumber}</span>
          </h1>
          <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            {getStatusBadge(caseDetail.status)}
            <span
              className="badge font-mono"
              style={{ backgroundColor: "#1e3a8a", color: "#ffffff", padding: "0.3rem 0.6rem" }}
              id="badge-case-version"
            >
              사건 버전: v{caseDetail.version}
            </span>
          </div>
        </div>

        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))",
            gap: "1rem",
            fontSize: "0.85rem",
          }}
        >
          <div>
            <span style={{ color: "var(--color-text-muted)" }}>사건 고유 ID:</span>
            <div className="font-mono" style={{ fontSize: "0.78rem" }}>{caseDetail.id}</div>
          </div>
          <div>
            <span style={{ color: "var(--color-text-muted)" }}>공급사 ID (Supplier):</span>
            <div className="font-mono"><strong>{caseDetail.supplierId}</strong></div>
          </div>
          <div>
            <span style={{ color: "var(--color-text-muted)" }}>발주서 번호 (PO):</span>
            <div className="font-mono"><strong>{caseDetail.purchaseOrderId}</strong></div>
          </div>
          <div>
            <span style={{ color: "var(--color-text-muted)" }}>제출자 (Submitter):</span>
            <div><strong>{caseDetail.submittedBy}</strong></div>
          </div>
        </div>
      </section>

      {/* Section 1: Line Items / Draft Editing */}
      <DraftEditor
        caseDetail={caseDetail}
        onRefresh={loadCaseData}
        onError={(err) => setError(err)}
      />

      {/* Section 2: 3-Way Match & Comparison */}
      <ThreeWayComparison
        caseDetail={caseDetail}
        matchResult={matchResult}
        activeSnapshot={activeSnapshot}
        onRefresh={loadCaseData}
        onError={(err) => setError(err)}
      />

      {/* Section 3: Review Snapshot & Human Decisions */}
      <ReviewDecisionPanel
        caseDetail={caseDetail}
        activeSnapshot={activeSnapshot}
        freshness={freshness}
        onRefresh={loadCaseData}
        onError={(err) => setError(err)}
      />

      {/* Section 4: ERP Handoff Status */}
      <HandoffStatusCard
        handoff={handoff}
        isLoading={isHandoffLoading}
        onRefresh={refreshHandoffOnly}
      />

      {/* Section 5: Audit History Panel */}
      <AuditHistoryPanel caseId={caseDetail.id} />
    </main>
  );
}
