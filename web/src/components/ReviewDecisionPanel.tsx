"use client";

import React, { useState } from "react";
import {
  InvoiceCaseDetail,
  ReviewFreshness,
  ReviewSnapshotView,
} from "@/types/api";
import { useAuth } from "@/lib/auth-context";
import { api } from "@/lib/api-client";
import { generateRequestId } from "@/lib/id-generator";
import { AppError, translateStaleReason } from "./ErrorBanner";

interface ReviewDecisionPanelProps {
  caseDetail: InvoiceCaseDetail;
  activeSnapshot: ReviewSnapshotView | null;
  freshness: ReviewFreshness | null;
  onRefresh: () => void;
  onError: (err: AppError) => void;
}

export function ReviewDecisionPanel({
  caseDetail,
  activeSnapshot,
  freshness,
  onRefresh,
  onError,
}: ReviewDecisionPanelProps) {
  const { credentials, user, hasRole } = useAuth();

  const isApprover = hasRole("APPROVER");
  const isSubmitterOfCase = user?.username === caseDetail.submittedBy;

  // Freeze state
  const [isFreezing, setIsFreezing] = useState(false);

  // Decision actions state
  const [supplementReason, setSupplementReason] = useState("");
  const [rejectReason, setRejectReason] = useState("");
  const [isRequestingSupplement, setIsRequestingSupplement] = useState(false);
  const [isRejecting, setIsRejecting] = useState(false);
  const [isApproving, setIsApproving] = useState(false);
  const [actionSuccess, setActionSuccess] = useState<string | null>(null);

  // Calculate approval amount from snapshot
  const plannedAmount = activeSnapshot?.payload?.lineOutcomes?.reduce((acc, outcome) => {
    return acc + (outcome.plannedQuantity * outcome.invoiceUnitPrice);
  }, 0) ?? 0;

  // 1. Freeze review snapshot
  const handleFreezeSnapshot = async () => {
    if (!credentials) return;
    setIsFreezing(true);
    setActionSuccess(null);

    const requestId = generateRequestId("freeze");
    const res = await api.freezeReviewSnapshot(credentials, caseDetail.id, {
      requestId,
      expectedCaseVersion: caseDetail.version,
    });

    setIsFreezing(false);

    if (res.ok) {
      setActionSuccess("새 검토 스냅샷(Review Snapshot)이 동결되었습니다.");
      onRefresh();
    } else {
      onError({
        code: res.error?.code || `HTTP_${res.status}`,
        message: res.error?.message || "스냅샷 동결 중 오류가 발생했습니다.",
        traceId: res.traceId,
        ...(res.error as object),
      });
    }
  };

  // 2. Supplement request
  const handleSupplement = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!credentials || !activeSnapshot) return;

    if (!supplementReason.trim()) {
      alert("보완 요청 사유를 입력해주세요.");
      return;
    }

    setIsRequestingSupplement(true);
    setActionSuccess(null);

    const requestId = generateRequestId("suppl");
    const res = await api.requestSupplement(credentials, caseDetail.id, {
      requestId,
      expectedCaseVersion: caseDetail.version,
      reviewSnapshotId: activeSnapshot.id,
      reviewPayloadHash: activeSnapshot.payloadHash,
      reason: supplementReason.trim(),
    });

    setIsRequestingSupplement(false);

    if (res.ok) {
      setActionSuccess("보완 요청이 성공적으로 등록되었습니다 (SUPPLEMENT_REQUIRED).");
      setSupplementReason("");
      onRefresh();
    } else {
      onError({
        code: res.error?.code || `HTTP_${res.status}`,
        message: res.error?.message || "보완 요청 중 오류가 발생했습니다.",
        traceId: res.traceId,
        ...(res.error as object),
      });
    }
  };

  // 3. Reject
  const handleReject = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!credentials || !activeSnapshot) return;

    if (!rejectReason.trim()) {
      alert("청구 거절 사유를 입력해주세요.");
      return;
    }

    setIsRejecting(true);
    setActionSuccess(null);

    const requestId = generateRequestId("reject");
    const res = await api.rejectReview(credentials, caseDetail.id, {
      requestId,
      expectedCaseVersion: caseDetail.version,
      reviewSnapshotId: activeSnapshot.id,
      reviewPayloadHash: activeSnapshot.payloadHash,
      reason: rejectReason.trim(),
    });

    setIsRejecting(false);

    if (res.ok) {
      setActionSuccess("청구서가 거절 처리되었습니다 (REJECTED).");
      setRejectReason("");
      onRefresh();
    } else {
      onError({
        code: res.error?.code || `HTTP_${res.status}`,
        message: res.error?.message || "거절 처리 중 오류가 발생했습니다.",
        traceId: res.traceId,
        ...(res.error as object),
      });
    }
  };

  // 4. Approve
  const handleApprove = async () => {
    if (!credentials || !activeSnapshot) return;

    // Client-side self-approval block notification
    if (isSubmitterOfCase) {
      onError({
        code: "FORBIDDEN",
        message: `제출자(${user?.username})는 본인이 제출한 사건을 승인할 수 없습니다 (자기 승인 방지).`,
      });
      return;
    }

    setIsApproving(true);
    setActionSuccess(null);

    const requestId = generateRequestId("approve");
    const res = await api.approveCase(credentials, caseDetail.id, {
      requestId,
      expectedCaseVersion: caseDetail.version,
      reviewSnapshotId: activeSnapshot.id,
      reviewPayloadHash: activeSnapshot.payloadHash,
    });

    setIsApproving(false);

    if (res.ok) {
      setActionSuccess("청구가 최종 승인되어 ERP 인계 대기(EXPORT_PENDING) 상태로 전이되었습니다.");
      onRefresh();
    } else {
      onError({
        code: res.error?.code || `HTTP_${res.status}`,
        message: res.error?.message || "승인 처리 중 오류가 발생했습니다.",
        traceId: res.traceId,
        ...(res.error as object),
      });
    }
  };

  return (
    <section className="card" aria-labelledby="review-decision-title">
      <div className="card-header">
        <h2 id="review-decision-title" className="card-title">
          검토 스냅샷 및 의사결정 (보완 / 거절 / 승인)
        </h2>

        {isApprover && (
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={handleFreezeSnapshot}
            disabled={isFreezing || caseDetail.status === "DRAFT"}
            id="btn-freeze-snapshot"
          >
            {isFreezing ? "동결 중..." : "❄️ 검토 스냅샷 동결 (Freeze Review Snapshot)"}
          </button>
        )}
      </div>

      {actionSuccess && (
        <div className="alert alert-success" role="status">
          <span>{actionSuccess}</span>
        </div>
      )}

      {/* Snapshot Information & Freshness */}
      {activeSnapshot ? (
        <div
          style={{
            backgroundColor: "#f8fafc",
            border: "1px solid var(--color-border)",
            borderRadius: "var(--radius-sm)",
            padding: "1rem",
            marginBottom: "1.25rem",
          }}
        >
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: "0.5rem" }}>
            <div>
              <h3 style={{ fontSize: "1rem", margin: "0 0 0.25rem 0" }}>
                스냅샷 #{activeSnapshot.snapshotNumber}
                <span className="font-mono" style={{ fontSize: "0.8rem", color: "var(--color-text-muted)", marginLeft: "0.5rem" }}>
                  ({activeSnapshot.id})
                </span>
              </h3>
              <div style={{ fontSize: "0.8rem", color: "var(--color-text-muted)" }}>
                대상 사건 버전: <strong>v{activeSnapshot.targetCaseVersion}</strong> | 동결 일시:{" "}
                {new Date(activeSnapshot.createdAt).toLocaleString("ko-KR")}
              </div>
              <div style={{ fontSize: "0.8rem", color: "var(--color-text-muted)" }}>
                스냅샷 페이로드 해시: <code className="font-mono">{activeSnapshot.payloadHash}</code>
              </div>
            </div>

            {/* Freshness Badge */}
            <div>
              {freshness ? (
                freshness.current ? (
                  <span className="badge badge-exported" id="badge-freshness-current">
                    ✓ 최신 검토 대상 (Fresh)
                  </span>
                ) : (
                  <span className="badge badge-rejected" id="badge-freshness-stale">
                    ⚠️ 스냅샷 만료 (Stale)
                  </span>
                )
              ) : (
                <span className="badge badge-draft">신선도 확인 중...</span>
              )}
            </div>
          </div>

          {freshness && !freshness.current && freshness.reasons.length > 0 && (
            <div style={{ marginTop: "0.75rem", padding: "0.5rem", backgroundColor: "#fff7ed", border: "1px solid #fdba74", borderRadius: "var(--radius-sm)" }}>
              <strong style={{ fontSize: "0.8rem", color: "#9a3412" }}>스냅샷 만료 원인:</strong>
              <ul style={{ margin: "0.25rem 0 0 1.2rem", padding: 0 }}>
                {freshness.reasons.map((r, i) => (
                  <li key={i} style={{ fontSize: "0.75rem", color: "#c2410c" }}>
                    <strong>{r}</strong>: {translateStaleReason(r)}
                  </li>
                ))}
              </ul>
              <div style={{ fontSize: "0.75rem", marginTop: "0.3rem", color: "var(--color-text-muted)" }}>
                상단의 [검토 스냅샷 동결]을 다시 실행하여 최신 상태의 스냅샷을 생성하세요.
              </div>
            </div>
          )}
        </div>
      ) : (
        <div style={{ padding: "1.5rem", textAlign: "center", color: "var(--color-text-muted)", backgroundColor: "#f8fafc", borderRadius: "var(--radius-sm)", marginBottom: "1.25rem" }}>
          동결된 검토 스냅샷이 없습니다. 승인자는 상단의 <strong>[검토 스냅샷 동결]</strong>을 클릭하여 검토를 시작하세요.
        </div>
      )}

      {/* Self-Approval Warning */}
      {isSubmitterOfCase && (
        <div className="alert alert-warning" role="alert" id="callout-self-approval-notice">
          <span className="alert-title">⚠️ 자기 승인 방지 규칙 (Segregation of Duties)</span>
          <span>
            현재 사용자는 해당 청구 사건의 제출자(<code>{caseDetail.submittedBy}</code>)입니다. 금융 통제 불변식에 따라 제출자 본인은 사건을 승인할 수 없습니다. 다른 승인자(APPROVER) 계정으로 로그인하여 승인을 진행해주세요.
          </span>
        </div>
      )}

      {/* Decision Actions (APPROVER role only) */}
      {!isApprover ? (
        <div style={{ fontSize: "0.85rem", color: "var(--color-text-muted)", fontStyle: "italic" }}>
          * 의사결정(보완요청, 거절, 최종승인)은 승인자(APPROVER) 권한이 필요합니다.
        </div>
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))", gap: "1rem" }}>
          {/* Action 1: Final Approval */}
          <div
            style={{
              border: "1px solid var(--color-border)",
              borderRadius: "var(--radius-md)",
              padding: "1rem",
              backgroundColor: "#ffffff",
              display: "flex",
              flexDirection: "column",
              justifyContent: "space-between",
            }}
          >
            <div>
              <h3 style={{ fontSize: "1rem", color: "#166534", marginBottom: "0.5rem" }}>
                1. 최종 승인 (Approve)
              </h3>
              <p style={{ fontSize: "0.8rem", color: "var(--color-text-muted)", marginBottom: "0.75rem" }}>
                현재 동결된 검토 스냅샷을 원본으로 승인하고 ERP 지급 인계(PaymentRequest)를 생성합니다.
              </p>

              {/* Explicit Verification Box before approval */}
              <div
                style={{
                  backgroundColor: "#f0fdf4",
                  border: "1px solid #bbf7d0",
                  borderRadius: "var(--radius-sm)",
                  padding: "0.75rem",
                  fontSize: "0.78rem",
                  marginBottom: "1rem",
                }}
                id="approval-precheck-box"
              >
                <div style={{ fontWeight: 700, color: "#166534", marginBottom: "0.3rem" }}>
                  승인 전 필수 검증 데이터 확인:
                </div>
                <div>스냅샷 ID: <code className="font-mono">{activeSnapshot?.id || "스냅샷 필요"}</code></div>
                <div>해시: <code className="font-mono">{activeSnapshot?.payloadHash ? `${activeSnapshot.payloadHash.slice(0, 16)}...` : "-"}</code></div>
                <div>대상 버전: <strong>v{caseDetail.version}</strong></div>
                <div style={{ marginTop: "0.3rem", fontWeight: 700, color: "#1e3a8a", fontSize: "0.85rem" }}>
                  승인 예정 금액: {plannedAmount.toLocaleString("ko-KR")} 원 (KRW)
                </div>
              </div>
            </div>

            <button
              type="button"
              className="btn btn-success btn-lg"
              onClick={handleApprove}
              disabled={
                isApproving ||
                !activeSnapshot ||
                isSubmitterOfCase ||
                (freshness ? !freshness.current : false)
              }
              id="btn-action-approve"
              title={isSubmitterOfCase ? "제출자 본인은 승인할 수 없습니다 (자기 승인 금지)" : undefined}
            >
              {isApproving ? "승인 처리 중..." : "✓ 최종 승인 (Approve)"}
            </button>
          </div>

          {/* Action 2: Supplement Request */}
          <div
            style={{
              border: "1px solid var(--color-border)",
              borderRadius: "var(--radius-md)",
              padding: "1rem",
              backgroundColor: "#ffffff",
            }}
          >
            <h3 style={{ fontSize: "1rem", color: "#b45309", marginBottom: "0.5rem" }}>
              2. 보완 요청 (Supplement)
            </h3>
            <p style={{ fontSize: "0.8rem", color: "var(--color-text-muted)", marginBottom: "0.5rem" }}>
              청구 내용에 오류나 근거 부족이 있을 때 제출자에게 수정을 요구합니다.
            </p>

            <form onSubmit={handleSupplement}>
              <div className="form-group">
                <label htmlFor="input-supplement-reason" className="form-label" style={{ fontSize: "0.8rem" }}>
                  보완 요청 사유 *
                </label>
                <textarea
                  id="input-supplement-reason"
                  className="form-textarea"
                  value={supplementReason}
                  onChange={(e) => setSupplementReason(e.target.value)}
                  placeholder="예: 발주 수량 대비 검수증 확인 요망"
                  required
                  disabled={isRequestingSupplement || !activeSnapshot}
                  rows={3}
                />
              </div>

              <button
                type="submit"
                className="btn btn-warning"
                style={{ width: "100%" }}
                disabled={isRequestingSupplement || !activeSnapshot || !supplementReason.trim()}
                id="btn-action-supplement"
              >
                {isRequestingSupplement ? "전송 중..." : "📝 보완 요청 전송"}
              </button>
            </form>
          </div>

          {/* Action 3: Reject */}
          <div
            style={{
              border: "1px solid var(--color-border)",
              borderRadius: "var(--radius-md)",
              padding: "1rem",
              backgroundColor: "#ffffff",
            }}
          >
            <h3 style={{ fontSize: "1rem", color: "#dc2626", marginBottom: "0.5rem" }}>
              3. 청구 거절 (Reject)
            </h3>
            <p style={{ fontSize: "0.8rem", color: "var(--color-text-muted)", marginBottom: "0.5rem" }}>
              계약 위반, 위조 또는 정당하지 않은 청구로 판정하여 최종 거절합니다.
            </p>

            <form onSubmit={handleReject}>
              <div className="form-group">
                <label htmlFor="input-reject-reason" className="form-label" style={{ fontSize: "0.8rem" }}>
                  거절 사유 *
                </label>
                <textarea
                  id="input-reject-reason"
                  className="form-textarea"
                  value={rejectReason}
                  onChange={(e) => setRejectReason(e.target.value)}
                  placeholder="예: 단가 협의되지 않은 부당 청구"
                  required
                  disabled={isRejecting || !activeSnapshot}
                  rows={3}
                />
              </div>

              <button
                type="submit"
                className="btn btn-danger"
                style={{ width: "100%" }}
                disabled={isRejecting || !activeSnapshot || !rejectReason.trim()}
                id="btn-action-reject"
              >
                {isRejecting ? "거절 처리 중..." : "✕ 청구 거절 처리"}
              </button>
            </form>
          </div>
        </div>
      )}
    </section>
  );
}
