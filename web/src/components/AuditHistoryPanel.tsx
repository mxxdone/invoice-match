"use client";

import React, { useState, useEffect, useCallback } from "react";
import { AuditEntryView } from "@/types/api";
import { useAuth } from "@/lib/auth-context";
import { api } from "@/lib/api-client";

interface AuditHistoryPanelProps {
  caseId: string;
}

import { translateAuditAction } from "@/lib/translators";
export { translateAuditAction };

export function AuditHistoryPanel({ caseId }: AuditHistoryPanelProps) {
  const { credentials, hasRole } = useAuth();
  const canViewAudit = hasRole("APPROVER") || hasRole("OPERATOR");

  const [entries, setEntries] = useState<AuditEntryView[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const loadAudit = useCallback(async (cursor?: string | null, append = false) => {
    if (!credentials || !canViewAudit) return;
    setIsLoading(true);

    const res = await api.getAuditHistory(credentials, caseId, cursor, 20);
    setIsLoading(false);

    if (res.ok && res.data) {
      if (append) {
        setEntries((prev) => [...prev, ...res.data!.entries]);
      } else {
        setEntries(res.data.entries);
      }
      setNextCursor(res.data.nextCursor);
    }
  }, [credentials, canViewAudit, caseId]);

  useEffect(() => {
    let active = true;
    if (!credentials || !canViewAudit) return;
    api.getAuditHistory(credentials, caseId, undefined, 20).then((res) => {
      if (!active) return;
      if (res.ok && res.data) {
        setEntries(res.data.entries);
        setNextCursor(res.data.nextCursor);
      }
    });
    return () => {
      active = false;
    };
  }, [credentials, canViewAudit, caseId]);

  if (!canViewAudit) {
    return (
      <section className="card">
        <h2 className="card-title">감사 이력 (Audit History)</h2>
        <p style={{ color: "var(--color-text-muted)", fontSize: "0.85rem", margin: 0 }}>
          감사 이력은 승인자(APPROVER) 및 운영자(OPERATOR) 권한으로만 조회 가능합니다.
        </p>
      </section>
    );
  }

  return (
    <section className="card" aria-labelledby="audit-history-title">
      <div className="card-header">
        <h2 id="audit-history-title" className="card-title">
          감사 이력 (Audit History - 변경 추적 및 감사 증적)
          <span style={{ fontSize: "0.8rem", fontWeight: 400, color: "var(--color-text-muted)" }}>
            (기록 {entries.length}건)
          </span>
        </h2>
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={() => loadAudit(null, false)}
          disabled={isLoading}
          aria-label="감사 이력 새로고침"
          id="btn-refresh-audit"
        >
          {isLoading ? "불러오는 중..." : "🔄 새로고침"}
        </button>
      </div>

      {entries.length === 0 ? (
        <div style={{ textAlign: "center", padding: "2rem", color: "var(--color-text-muted)" }}>
          {isLoading ? "감사 이력을 불러오는 중..." : "등록된 감사 이력이 없습니다."}
        </div>
      ) : (
        <div className="table-container">
          <table className="data-table" aria-label="사건 감사 이력 목록">
            <thead>
              <tr>
                <th scope="col" style={{ width: "160px" }}>발생 일시</th>
                <th scope="col" style={{ width: "140px" }}>수행자 / 역할</th>
                <th scope="col">수행 작업 (Action)</th>
                <th scope="col" style={{ width: "80px" }} className="text-center">버전</th>
                <th scope="col" style={{ width: "180px" }}>요청 ID / 추적 ID</th>
                <th scope="col" style={{ width: "100px" }} className="text-center">상세 변경</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((item) => {
                const isExpanded = expandedId === item.id;
                return (
                  <React.Fragment key={item.id}>
                    <tr>
                      <td style={{ fontSize: "0.8rem", color: "var(--color-text-muted)" }}>
                        {new Date(item.occurredAt).toLocaleString("ko-KR")}
                      </td>
                      <td>
                        <strong>{item.actor}</strong>
                        <div style={{ display: "flex", gap: "0.2rem", marginTop: "0.2rem", flexWrap: "wrap" }}>
                          {item.actorRoles?.map((r) => (
                            <span key={r} className="badge badge-role" style={{ fontSize: "0.65rem", padding: "0.1rem 0.35rem" }}>
                              {r}
                            </span>
                          ))}
                        </div>
                      </td>
                      <td>
                        <strong style={{ color: "#1e3a8a" }}>{translateAuditAction(item.action)}</strong>
                        <div style={{ fontSize: "0.75rem", color: "var(--color-text-muted)" }}>
                          대상: {item.targetType} (<code>{item.targetId.slice(0, 12)}...</code>)
                        </div>
                      </td>
                      <td className="text-center font-mono">v{item.businessVersion}</td>
                      <td>
                        <div style={{ fontSize: "0.75rem" }}>
                          요청: <code className="font-mono">{item.requestId || "-"}</code>
                        </div>
                        <div style={{ fontSize: "0.75rem", marginTop: "0.2rem" }}>
                          추적: <span className="badge badge-trace" style={{ fontSize: "0.7rem" }}>{item.traceId || "-"}</span>
                        </div>
                      </td>
                      <td className="text-center">
                        <button
                          type="button"
                          className="btn btn-secondary btn-sm"
                          onClick={() => setExpandedId(isExpanded ? null : item.id)}
                          aria-expanded={isExpanded}
                        >
                          {isExpanded ? "닫기 ▲" : "보기 ▼"}
                        </button>
                      </td>
                    </tr>
                    {isExpanded && (
                      <tr style={{ backgroundColor: "#f8fafc" }}>
                        <td colSpan={6} style={{ padding: "0.75rem 1rem" }}>
                          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "1rem", fontSize: "0.8rem" }}>
                            <div>
                              <strong>변경 전 (Before):</strong>
                              <pre
                                className="font-mono"
                                style={{
                                  backgroundColor: "#ffffff",
                                  border: "1px solid var(--color-border-light)",
                                  padding: "0.5rem",
                                  borderRadius: "var(--radius-sm)",
                                  maxHeight: "160px",
                                  overflowY: "auto",
                                  margin: "0.25rem 0 0 0",
                                }}
                              >
                                {item.before ? JSON.stringify(item.before, null, 2) : "None"}
                              </pre>
                            </div>
                            <div>
                              <strong>변경 후 (After):</strong>
                              <pre
                                className="font-mono"
                                style={{
                                  backgroundColor: "#ffffff",
                                  border: "1px solid var(--color-border-light)",
                                  padding: "0.5rem",
                                  borderRadius: "var(--radius-sm)",
                                  maxHeight: "160px",
                                  overflowY: "auto",
                                  margin: "0.25rem 0 0 0",
                                }}
                              >
                                {item.after ? JSON.stringify(item.after, null, 2) : "None"}
                              </pre>
                            </div>
                          </div>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {nextCursor && (
        <div style={{ marginTop: "1rem", textAlign: "center" }}>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => loadAudit(nextCursor, true)}
            disabled={isLoading}
            id="btn-audit-load-more"
          >
            {isLoading ? "불러오는 중..." : "⬇ 감사 이력 더보기 (Next Page)"}
          </button>
        </div>
      )}
    </section>
  );
}
