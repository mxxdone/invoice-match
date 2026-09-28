"use client";

import React from "react";
import { StaleReason } from "@/types/api";

export interface AppError {
  code: string;
  message: string;
  traceId?: string | null;
  // Specific for STALE_CASE_VERSION
  caseId?: string | null;
  expectedVersion?: number | null;
  latestVersion?: number | null;
  // Specific for STALE_REVIEW_TARGET
  reasons?: StaleReason[] | string[];
  currentCaseVersion?: number | null;
  currentCaseStatus?: string | null;
  // Specific for APPROVAL_NOT_PERMITTED / INSUFFICIENT_RECEIPT_BALANCE
  shortfalls?: unknown[];
}

interface ErrorBannerProps {
  error: AppError | null;
  onDismiss?: () => void;
  onRefreshLatest?: () => void;
}

import { translateStaleReason } from "@/lib/translators";
export { translateStaleReason };

export function ErrorBanner({ error, onDismiss, onRefreshLatest }: ErrorBannerProps) {
  if (!error) return null;

  const isStaleCase = error.code === "STALE_CASE_VERSION";
  const isStaleReview = error.code === "STALE_REVIEW_TARGET";
  const isForbidden = error.code === "FORBIDDEN";
  const isSelfApproval =
    isForbidden &&
    (error.message.includes("submitted the case and may not approve it") ||
     error.message.includes("self-approval") ||
     error.message.includes("자신이 제출한"));

  if (isStaleCase) {
    return (
      <div className="stale-box" role="alert" aria-live="assertive" id="stale-case-error-banner">
        <div className="stale-box-header">
          <span>⚠️ 409 버전 경합: 사건 최신 버전이 변경되었습니다 (STALE_CASE_VERSION)</span>
        </div>
        <p style={{ margin: "0.25rem 0 0.5rem 0" }}>
          요청한 버전(expectedVersion: <strong>{error.expectedVersion ?? "알 수 없음"}</strong>)이 서버의 최신 버전(latestVersion: <strong>{error.latestVersion ?? "확인 필요"}</strong>)과 일치하지 않습니다. 다른 사용자가 사건을 수정했거나 이미 처리되었습니다.
        </p>
        <div style={{ display: "flex", gap: "0.5rem", alignItems: "center", marginTop: "0.75rem", flexWrap: "wrap" }}>
          {onRefreshLatest && (
            <button
              type="button"
              className="btn btn-warning btn-sm"
              onClick={onRefreshLatest}
              id="btn-refresh-latest-case"
            >
              🔄 최신 상세 다시 불러오기
            </button>
          )}
          {error.traceId && (
            <span className="badge badge-trace">추적 ID: {error.traceId}</span>
          )}
          {onDismiss && (
            <button type="button" className="btn btn-secondary btn-sm" onClick={onDismiss}>
              닫기
            </button>
          )}
        </div>
      </div>
    );
  }

  if (isStaleReview) {
    return (
      <div className="stale-box" role="alert" aria-live="assertive" id="stale-review-error-banner">
        <div className="stale-box-header">
          <span>⚠️ 409 검토 대상 만료: 검토 스냅샷이 최신이 아닙니다 (STALE_REVIEW_TARGET)</span>
        </div>
        <p style={{ margin: "0.25rem 0 0.5rem 0" }}>
          현재 화면에 표시된 검토 스냅샷이 최신 상태가 아니므로 의사결정을 수행할 수 없습니다.
        </p>

        {error.currentCaseVersion !== undefined && error.currentCaseVersion !== null && (
          <div style={{ fontSize: "0.85rem", marginBottom: "0.5rem" }}>
            현재 서버 사건 버전: <strong>{error.currentCaseVersion}</strong> | 상태: <strong>{error.currentCaseStatus || "-"}</strong>
          </div>
        )}

        {error.reasons && error.reasons.length > 0 && (
          <div style={{ margin: "0.5rem 0" }}>
            <span style={{ fontWeight: 600, fontSize: "0.85rem" }}>만료 사유:</span>
            <ul style={{ margin: "0.25rem 0 0 1.25rem", padding: 0 }}>
              {error.reasons.map((r, idx) => (
                <li key={idx} style={{ fontSize: "0.85rem", color: "#9a3412" }}>
                  <strong>{r}</strong>: {translateStaleReason(String(r))}
                </li>
              ))}
            </ul>
          </div>
        )}

        <div style={{ display: "flex", gap: "0.5rem", alignItems: "center", marginTop: "0.75rem", flexWrap: "wrap" }}>
          {onRefreshLatest && (
            <button
              type="button"
              className="btn btn-warning btn-sm"
              onClick={onRefreshLatest}
              id="btn-refresh-latest-review"
            >
              🔄 최신 상세 다시 불러오기
            </button>
          )}
          {error.traceId && (
            <span className="badge badge-trace">추적 ID: {error.traceId}</span>
          )}
          {onDismiss && (
            <button type="button" className="btn btn-secondary btn-sm" onClick={onDismiss}>
              닫기
            </button>
          )}
        </div>
      </div>
    );
  }

  if (isSelfApproval) {
    return (
      <div className="alert alert-danger" role="alert" aria-live="assertive" id="self-approval-error-banner">
        <span className="alert-title">🚫 403 권한 오류: 자기 승인 금지 (Self-Approval Denied)</span>
        <div>
          청구서를 제출한 당사자는 해당 사건을 직접 승인할 수 없습니다. 직무 분리(Segregation of Duties) 원칙에 따라 다른 승인자가 검토 및 승인을 진행해야 합니다.
        </div>
        <div style={{ display: "flex", gap: "0.5rem", alignItems: "center", marginTop: "0.5rem" }}>
          {error.traceId && <span className="badge badge-trace">추적 ID: {error.traceId}</span>}
          {onDismiss && (
            <button type="button" className="btn btn-secondary btn-sm" onClick={onDismiss}>
              닫기
            </button>
          )}
        </div>
      </div>
    );
  }

  // General error banner
  return (
    <div className="alert alert-danger" role="alert" aria-live="assertive">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <span className="alert-title">오류: [{error.code}]</span>
          <div style={{ marginTop: "0.2rem" }}>{error.message}</div>
          {error.shortfalls && error.shortfalls.length > 0 && (
            <div style={{ marginTop: "0.5rem", fontSize: "0.85rem" }}>
              <strong>수량 부족 내역:</strong> {JSON.stringify(error.shortfalls)}
            </div>
          )}
        </div>
        {onDismiss && (
          <button type="button" className="btn btn-secondary btn-sm" onClick={onDismiss}>
            ✕
          </button>
        )}
      </div>

      <div style={{ display: "flex", gap: "0.5rem", alignItems: "center", marginTop: "0.5rem", flexWrap: "wrap" }}>
        {error.traceId && <span className="badge badge-trace">추적 ID: {error.traceId}</span>}
        {onRefreshLatest && (
          <button type="button" className="btn btn-secondary btn-sm" onClick={onRefreshLatest}>
            🔄 최신 상세 다시 불러오기
          </button>
        )}
      </div>
    </div>
  );
}
