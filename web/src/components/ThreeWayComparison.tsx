"use client";

import React, { useState } from "react";
import {
  InvoiceCaseDetail,
  MatchLineOutcome,
  MatchResultView,
  ReviewSnapshotView,
} from "@/types/api";
import { useAuth } from "@/lib/auth-context";
import { api } from "@/lib/api-client";
import { generateRequestId } from "@/lib/id-generator";
import { AppError } from "./ErrorBanner";

interface ThreeWayComparisonProps {
  caseDetail: InvoiceCaseDetail;
  matchResult: MatchResultView | null;
  activeSnapshot: ReviewSnapshotView | null;
  onRefresh: () => void;
  onError: (err: AppError) => void;
}

import { translateExceptionType } from "@/lib/translators";
export { translateExceptionType };

export function ThreeWayComparison({
  caseDetail,
  matchResult,
  activeSnapshot,
  onRefresh,
  onError,
}: ThreeWayComparisonProps) {
  const { credentials, hasRole } = useAuth();
  const [isRunningMatch, setIsRunningMatch] = useState(false);

  // Mapping state
  const [mappingLineNum, setMappingLineNum] = useState<number>(1);
  const [mappingItemId, setMappingItemId] = useState("ITEM-A4-80");
  const [isRecordingMapping, setIsRecordingMapping] = useState(false);
  const [mappingSuccess, setMappingSuccess] = useState<string | null>(null);

  // 1. Run deterministic match (OPERATOR)
  const handleRunMatch = async () => {
    if (!credentials) return;
    setIsRunningMatch(true);

    const requestId = generateRequestId("match");
    const res = await api.runMatch(credentials, caseDetail.id, { requestId });
    setIsRunningMatch(false);

    if (res.ok) {
      onRefresh();
    } else {
      onError({
        code: res.error?.code || `HTTP_${res.status}`,
        message: res.error?.message || "대사 실행 중 오류가 발생했습니다.",
        traceId: res.traceId,
        ...(res.error as object),
      });
    }
  };

  // 2. Record mapping decision (APPROVER)
  const handleRecordMapping = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!credentials) return;

    if (!activeSnapshot) {
      alert("품목 매핑 결정을 내리려면 먼저 검토 스냅샷(Review Snapshot)이 동결되어 있어야 합니다.");
      return;
    }

    setIsRecordingMapping(true);
    setMappingSuccess(null);

    const requestId = generateRequestId("map");
    const res = await api.recordMapping(credentials, caseDetail.id, {
      requestId,
      expectedCaseVersion: caseDetail.version,
      reviewSnapshotId: activeSnapshot.id,
      reviewPayloadHash: activeSnapshot.payloadHash,
      lineNumber: mappingLineNum,
      itemId: mappingItemId.trim(),
    });

    setIsRecordingMapping(false);

    if (res.ok) {
      setMappingSuccess(`${mappingLineNum}번 라인 품목 매핑이 확정되었으며, 결정론적 재대사가 적용되었습니다.`);
      onRefresh();
    } else {
      onError({
        code: res.error?.code || `HTTP_${res.status}`,
        message: res.error?.message || "품목 매핑 결정 저장 중 오류가 발생했습니다.",
        traceId: res.traceId,
        ...(res.error as object),
      });
    }
  };

  const payload = activeSnapshot?.payload || matchResult?.payload;

  return (
    <section className="card" aria-labelledby="three-way-title">
      <div className="card-header">
        <h2 id="three-way-title" className="card-title">
          3-Way 대사 (청구서 - 발주서 - 검수)
          {payload && (
            <span className={payload.normal ? "badge badge-exported" : "badge badge-rejected"}>
              {payload.normal ? "✓ 3-Way 일치 (Normal)" : "⚠️ 불일치 / 예외 발생"}
            </span>
          )}
        </h2>

        {hasRole("OPERATOR") && (
          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={handleRunMatch}
            disabled={isRunningMatch || caseDetail.status === "DRAFT"}
            id="btn-run-match"
          >
            {isRunningMatch ? (
              <>
                <span className="spinner" aria-hidden="true" />
                <span>대사 실행 중...</span>
              </>
            ) : (
              "⚙️ 3-Way 대사 실행 (OPERATOR)"
            )}
          </button>
        )}
      </div>

      {/* When match has not run yet */}
      {!payload ? (
        <div style={{ textAlign: "center", padding: "2rem", color: "var(--color-text-muted)" }}>
          <p>아직 실행된 3-way 대사 결과가 없습니다.</p>
          {caseDetail.status === "DRAFT" ? (
            <p style={{ fontSize: "0.85rem" }}>사건이 제출(SUBMITTED)된 후 운영자(OPERATOR)가 대사를 실행할 수 있습니다.</p>
          ) : hasRole("OPERATOR") ? (
            <p style={{ fontSize: "0.85rem" }}>상단의 <strong>[3-Way 대사 실행]</strong> 버튼을 클릭하여 대사를 수행하세요.</p>
          ) : (
            <p style={{ fontSize: "0.85rem" }}>운영자(OPERATOR) 권한으로 로그인하여 대사를 실행할 수 있습니다.</p>
          )}
        </div>
      ) : (
        <div>
          {/* Match & Snapshot Metadata Bar */}
          <div
            style={{
              backgroundColor: "#f8fafc",
              border: "1px solid var(--color-border-light)",
              borderRadius: "var(--radius-sm)",
              padding: "0.75rem 1rem",
              marginBottom: "1rem",
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              flexWrap: "wrap",
              gap: "0.5rem",
              fontSize: "0.8rem",
            }}
          >
            <div>
              대사 스키마: <strong>{payload.schemaVersion}</strong> | 구매 스냅샷 버전:{" "}
              <strong>v{payload.purchasingSnapshot.snapshotVersion}</strong> (발주 v{payload.purchasingSnapshot.purchaseOrderVersion})
            </div>
            <div>
              {matchResult && (
                <span>대사 번호: #{matchResult.resultNumber} | 해시: <code className="font-mono">{matchResult.resultHash.slice(0, 12)}...</code></span>
              )}
            </div>
          </div>

          {/* Exceptions Alert */}
          {payload.exceptions && payload.exceptions.length > 0 && (
            <div className="alert alert-danger" role="alert" style={{ marginBottom: "1.25rem" }}>
              <span className="alert-title">대사 예외 내역 (총 {payload.exceptions.length}건)</span>
              <ul style={{ margin: "0.25rem 0 0 1.25rem", padding: 0 }}>
                {payload.exceptions.map((ex, i) => (
                  <li key={i} style={{ marginBottom: "0.3rem" }}>
                    <strong>[{ex.type}]</strong> {ex.lineNumber ? `${ex.lineNumber}번 라인: ` : "사건 전체: "}
                    {translateExceptionType(ex.type)}
                    {ex.details && Object.keys(ex.details).length > 0 && (
                      <span style={{ fontSize: "0.75rem", color: "#7f1d1d", marginLeft: "0.5rem" }}>
                        (세부: {JSON.stringify(ex.details)})
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {/* 3-Column Comparison Table */}
          <div className="table-container" style={{ marginBottom: "1.25rem" }}>
            <table className="data-table" aria-label="청구-발주-검수 3열 비교 표">
              <thead>
                <tr>
                  <th scope="col" style={{ width: "50px" }} className="text-center">#</th>
                  <th scope="col" style={{ backgroundColor: "#eff6ff" }}>1. 인보이스(청구서) 라인</th>
                  <th scope="col" style={{ backgroundColor: "#f0fdf4" }}>2. 발주서(PO) 라인</th>
                  <th scope="col" style={{ backgroundColor: "#fefce8" }}>3. 검수(Receipt) 및 예상 배분</th>
                  <th scope="col" style={{ width: "100px" }} className="text-center">판정</th>
                </tr>
              </thead>
              <tbody>
                {payload.lineOutcomes.map((outcome: MatchLineOutcome) => {
                  const po = outcome.purchaseOrderLine;
                  const plans = outcome.expectedAllocationPlan || [];

                  return (
                    <tr key={outcome.lineNumber}>
                      <td className="text-center font-mono">{outcome.lineNumber}</td>

                      {/* Col 1: Invoice Line */}
                      <td style={{ backgroundColor: "#f8fbff" }}>
                        <div><strong>{outcome.rawItemName}</strong></div>
                        <div style={{ fontSize: "0.75rem", color: "var(--color-text-muted)" }}>
                          품목코드: <code className="font-mono">{outcome.confirmedItemId || "미지정"}</code>
                        </div>
                        <div style={{ fontSize: "0.8rem", marginTop: "0.25rem" }}>
                          수량: <strong>{outcome.invoiceQuantity.toLocaleString("ko-KR")}</strong> | 단가: <strong>{outcome.invoiceUnitPrice.toLocaleString("ko-KR")}</strong>원
                        </div>
                        <div style={{ fontSize: "0.8rem", color: "#1e3a8a", fontWeight: 600 }}>
                          청구액: {(outcome.invoiceQuantity * outcome.invoiceUnitPrice).toLocaleString("ko-KR")}원
                        </div>
                      </td>

                      {/* Col 2: PO Line */}
                      <td style={{ backgroundColor: "#f9fefb" }}>
                        {po ? (
                          <div>
                            <div>발주라인: <code className="font-mono">{po.purchaseOrderLineId}</code></div>
                            <div style={{ fontSize: "0.75rem", color: "var(--color-text-muted)" }}>
                              품목: <code className="font-mono">{po.itemId}</code>
                            </div>
                            <div style={{ fontSize: "0.8rem", marginTop: "0.25rem" }}>
                              발주수량: <strong>{po.orderedQuantity.toLocaleString("ko-KR")}</strong> | 발주단가: <strong>{po.unitPrice.toLocaleString("ko-KR")}</strong>원
                            </div>
                            <div style={{ fontSize: "0.8rem", color: "#166534", fontWeight: 600 }}>
                              발주액: {(po.orderedQuantity * po.unitPrice).toLocaleString("ko-KR")}원
                            </div>
                          </div>
                        ) : (
                          <div style={{ color: "var(--color-text-dim)", fontSize: "0.85rem" }}>
                            일치하는 발주 라인 없음
                          </div>
                        )}
                      </td>

                      {/* Col 3: Receipt Allocation Plan */}
                      <td style={{ backgroundColor: "#fffef0" }}>
                        {plans.length > 0 ? (
                          <div style={{ display: "flex", flexDirection: "column", gap: "0.3rem" }}>
                            {plans.map((p, pIdx) => (
                              <div
                                key={pIdx}
                                style={{
                                  fontSize: "0.75rem",
                                  padding: "0.25rem 0.4rem",
                                  backgroundColor: "#ffffff",
                                  border: "1px solid #fef08a",
                                  borderRadius: "var(--radius-sm)",
                                }}
                              >
                                <div>검수증: <code>{p.receiptId}</code> (라인: {p.receiptLineId})</div>
                                <div>검수일자: {p.receiptDate}</div>
                                <div>
                                  확정수량: {p.confirmedQuantity} | <strong>계획 배분: {p.plannedQuantity}</strong>
                                </div>
                              </div>
                            ))}
                            <div style={{ fontSize: "0.8rem", marginTop: "0.2rem" }}>
                              확정 잔여: {outcome.availableConfirmedQuantity} / 총 배분: <strong>{outcome.plannedQuantity}</strong>
                            </div>
                          </div>
                        ) : (
                          <div style={{ color: "var(--color-text-dim)", fontSize: "0.85rem" }}>
                            배분 계획 없음 (잔여 검수 수량: {outcome.availableConfirmedQuantity})
                          </div>
                        )}
                      </td>

                      {/* Status / Outcome */}
                      <td className="text-center">
                        {outcome.status === "MATCHED" && outcome.exceptions.length === 0 ? (
                          <span className="badge badge-exported">일치</span>
                        ) : (
                          <span className="badge badge-rejected">{outcome.status}</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {/* Allocation Plan Rules Summary */}
          {payload.allocationPlan && (
            <div
              style={{
                fontSize: "0.8rem",
                color: "var(--color-text-muted)",
                backgroundColor: "#f8fafc",
                padding: "0.5rem 0.75rem",
                borderRadius: "var(--radius-sm)",
                marginBottom: "1rem",
              }}
            >
              배분 계획 모드: <code>{payload.allocationPlan.mode}</code> | 소비형 배분 여부:{" "}
              <strong>{payload.allocationPlan.consuming ? "소비형" : "비소비형 예상 배분 (Non-Consuming)"}</strong> | 정렬 기준:{" "}
              <code>{payload.allocationPlan.fifoOrdering}</code>
            </div>
          )}

          {/* Mapping Decision Form for APPROVER */}
          {hasRole("APPROVER") && activeSnapshot && (
            <div
              style={{
                border: "1px solid var(--color-border)",
                borderRadius: "var(--radius-sm)",
                padding: "1rem",
                backgroundColor: "#f8fafc",
                marginTop: "1rem",
              }}
            >
              <h3 style={{ fontSize: "0.95rem", marginBottom: "0.5rem" }}>
                🎯 품목 매핑 결정 (Item Mapping Decision - APPROVER 전용)
              </h3>
              <p style={{ fontSize: "0.8rem", color: "var(--color-text-muted)", marginBottom: "0.75rem" }}>
                청구 라인의 품목코드 불일치 또는 미확정 시, 발주서의 정확한 품목코드를 수동 확정 매핑합니다. 매핑 저장 즉시 결정론적 재대사가 실행되어 새 스냅샷이 생성됩니다.
              </p>

              {mappingSuccess && (
                <div className="alert alert-success" role="status" style={{ padding: "0.5rem" }}>
                  <span>{mappingSuccess}</span>
                </div>
              )}

              <form onSubmit={handleRecordMapping} style={{ display: "flex", gap: "0.75rem", alignItems: "flex-end", flexWrap: "wrap" }}>
                <div className="form-group" style={{ marginBottom: 0, minWidth: "100px" }}>
                  <label htmlFor="select-map-line" className="form-label" style={{ fontSize: "0.75rem" }}>
                    청구 라인 #
                  </label>
                  <select
                    id="select-map-line"
                    className="form-select"
                    value={mappingLineNum}
                    onChange={(e) => setMappingLineNum(Number(e.target.value))}
                  >
                    {caseDetail.lines?.map((l) => (
                      <option key={l.lineNumber} value={l.lineNumber}>
                        {l.lineNumber}번 ({l.rawItemName})
                      </option>
                    ))}
                  </select>
                </div>

                <div className="form-group" style={{ marginBottom: 0, flex: 1, minWidth: "180px" }}>
                  <label htmlFor="input-map-item" className="form-label" style={{ fontSize: "0.75rem" }}>
                    확정할 발주 품목코드 (Item ID)
                  </label>
                  <input
                    id="input-map-item"
                    type="text"
                    className="form-input font-mono"
                    value={mappingItemId}
                    onChange={(e) => setMappingItemId(e.target.value)}
                    placeholder="예: ITEM-A4-80"
                    required
                  />
                </div>

                <button
                  type="submit"
                  className="btn btn-secondary"
                  disabled={isRecordingMapping}
                  id="btn-record-mapping"
                >
                  {isRecordingMapping ? "매핑 저장 중..." : "품목 매핑 확정 (Record Mapping)"}
                </button>
              </form>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
