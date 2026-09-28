"use client";

import React from "react";
import { CaseHandoffStatus, OutboxStatus, PaymentStatus } from "@/types/api";

interface HandoffStatusCardProps {
  handoff: CaseHandoffStatus | null;
  isLoading: boolean;
  onRefresh: () => void;
}

export function HandoffStatusCard({ handoff, isLoading, onRefresh }: HandoffStatusCardProps) {
  const getPaymentStatusBadge = (status: PaymentStatus) => {
    switch (status) {
      case "ACKNOWLEDGED":
        return <span className="badge badge-exported">수신확인 (ACKNOWLEDGED)</span>;
      case "NOT_SENT":
        return <span className="badge badge-draft">미전송 (NOT_SENT)</span>;
      case "SENDING":
        return <span className="badge badge-review-pending">전송 중 (SENDING)</span>;
      case "RETRY_SCHEDULED":
        return <span className="badge badge-supplement">재시도 예정 (RETRY_SCHEDULED)</span>;
      case "FAILED":
        return <span className="badge badge-rejected" id="badge-payment-failed">전송 실패 (FAILED)</span>;
      case "RESULT_UNKNOWN":
        return (
          <span
            className="badge"
            style={{ backgroundColor: "var(--color-unknown-bg)", color: "var(--color-unknown-text)", border: "1px solid var(--color-unknown-border)" }}
            id="badge-payment-unknown"
          >
            결과 불명 (RESULT_UNKNOWN)
          </span>
        );
      default:
        return <span className="badge">{status}</span>;
    }
  };

  const getOutboxStatusBadge = (status: OutboxStatus | null) => {
    if (!status) return <span className="badge badge-draft">없음</span>;
    switch (status) {
      case "DELIVERED":
        return <span className="badge badge-exported">전달 완료 (DELIVERED)</span>;
      case "READY":
        return <span className="badge badge-draft">대기 (READY)</span>;
      case "CLAIMED":
      case "SENDING":
        return <span className="badge badge-review-pending">중계 중 ({status})</span>;
      case "FAILED":
        return <span className="badge badge-rejected" id="badge-outbox-failed">아웃박스 실패 (FAILED)</span>;
      case "RESULT_UNKNOWN":
        return (
          <span
            className="badge"
            style={{ backgroundColor: "var(--color-unknown-bg)", color: "var(--color-unknown-text)", border: "1px solid var(--color-unknown-border)" }}
            id="badge-outbox-unknown"
          >
            결과 불명 (RESULT_UNKNOWN)
          </span>
        );
      default:
        return <span className="badge">{status}</span>;
    }
  };

  const payment = handoff?.payment;

  const isFailed = payment?.paymentStatus === "FAILED" || payment?.outboxStatus === "FAILED";
  const isUnknown = payment?.paymentStatus === "RESULT_UNKNOWN" || payment?.outboxStatus === "RESULT_UNKNOWN";

  return (
    <section className="card" aria-labelledby="handoff-status-title">
      <div className="card-header">
        <h2 id="handoff-status-title" className="card-title">
          ERP 연동 및 지급 인계 상태 (Payment & Outbox)
        </h2>
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={onRefresh}
          disabled={isLoading}
          aria-label="ERP 인계 상태 새로고침"
          id="btn-refresh-handoff"
        >
          {isLoading ? "확인 중..." : "🔄 새로고침"}
        </button>
      </div>

      {!payment ? (
        <div style={{ padding: "1.5rem", textAlign: "center", color: "var(--color-text-muted)", backgroundColor: "#f8fafc", borderRadius: "var(--radius-sm)" }}>
          <p style={{ margin: 0 }}>
            아직 청구가 승인되지 않아 ERP 지급 인계 레코드(PaymentRequest)가 생성되지 않았습니다.
          </p>
          <div style={{ fontSize: "0.8rem", marginTop: "0.4rem" }}>
            사건 상태: <strong>{handoff?.caseStatus || "-"}</strong> | 사건 버전: <strong>v{handoff?.caseVersion ?? "-"}</strong>
          </div>
        </div>
      ) : (
        <div>
          {/* Visual distinction for FAILED vs RESULT_UNKNOWN */}
          {isFailed && (
            <div className="alert alert-danger" role="alert" id="callout-handoff-failed">
              <span className="alert-title">❌ ERP 전송 실패 (FAILED)</span>
              <div>
                지급 요청이 ERP 시스템에 전달되지 못하고 최종 실패했습니다. (오류 코드: <code>{payment.lastErrorCode || "N/A"}</code>, 재시도: {payment.attemptCount}회)
              </div>
            </div>
          )}

          {isUnknown && (
            <div className="alert alert-unknown" role="alert" id="callout-handoff-unknown">
              <span className="alert-title">⚠️ ERP 연동 결과 불명 (RESULT_UNKNOWN)</span>
              <div>
                ERP 중계 중 네트워크 단절 또는 타임아웃으로 실제 처리 여부를 확인할 수 없습니다. 이중 지급을 방지하기 위해 자동 재전송이 중단되었으며, 운영자가 ERP 원장을 수동 대조해야 합니다.
              </div>
            </div>
          )}

          <div
            style={{
              display: "grid",
              gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))",
              gap: "1rem",
              backgroundColor: "#f8fafc",
              border: "1px solid var(--color-border-light)",
              borderRadius: "var(--radius-sm)",
              padding: "1rem",
              marginBottom: "1rem",
            }}
          >
            <div>
              <div style={{ fontSize: "0.75rem", color: "var(--color-text-muted)" }}>지급 상태 (Payment Status)</div>
              <div style={{ marginTop: "0.3rem" }}>{getPaymentStatusBadge(payment.paymentStatus)}</div>
            </div>

            <div>
              <div style={{ fontSize: "0.75rem", color: "var(--color-text-muted)" }}>아웃박스 상태 (Outbox Status)</div>
              <div style={{ marginTop: "0.3rem" }}>{getOutboxStatusBadge(payment.outboxStatus)}</div>
            </div>

            <div>
              <div style={{ fontSize: "0.75rem", color: "var(--color-text-muted)" }}>지급 승인 금액</div>
              <div style={{ marginTop: "0.3rem", fontWeight: 700, fontSize: "1.1rem", color: "#1e3a8a" }} className="font-mono">
                {payment.amount.toLocaleString("ko-KR")} {payment.currency}
              </div>
            </div>

            <div>
              <div style={{ fontSize: "0.75rem", color: "var(--color-text-muted)" }}>재시도 횟수</div>
              <div style={{ marginTop: "0.3rem", fontWeight: 600 }}>
                {payment.attemptCount}회 시도 {payment.lastErrorCode && `(최종 에러: ${payment.lastErrorCode})`}
              </div>
            </div>
          </div>

          {/* Technical Details */}
          <div style={{ fontSize: "0.8rem", color: "var(--color-text-muted)", display: "flex", flexDirection: "column", gap: "0.3rem" }}>
            <div>지급 요청 ID: <code className="font-mono">{payment.paymentRequestId}</code></div>
            <div>외부 요청 키: <code className="font-mono">{payment.externalRequestKey}</code></div>
            <div>인계 버전: <strong>v{payment.exportVersion}</strong> | 등록 일시: {new Date(payment.createdAt).toLocaleString("ko-KR")}</div>
            {payment.deliveredAt && <div>전달 완료 일시: {new Date(payment.deliveredAt).toLocaleString("ko-KR")}</div>}
            {payment.nextAttemptAt && <div>다음 재시도 예정: {new Date(payment.nextAttemptAt).toLocaleString("ko-KR")}</div>}
          </div>
        </div>
      )}
    </section>
  );
}
