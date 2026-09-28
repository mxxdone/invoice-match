"use client";

import React, { useState } from "react";
import { InvoiceCaseDetail, LineRequest } from "@/types/api";
import { useAuth } from "@/lib/auth-context";
import { api } from "@/lib/api-client";
import { generateRequestId } from "@/lib/id-generator";
import { AppError } from "./ErrorBanner";

interface DraftEditorProps {
  caseDetail: InvoiceCaseDetail;
  onRefresh: () => void;
  onError: (err: AppError) => void;
}

export function DraftEditor({ caseDetail, onRefresh, onError }: DraftEditorProps) {
  const { credentials, user, hasRole } = useAuth();

  const isOwner = user?.username === caseDetail.submittedBy;
  const isSubmitter = hasRole("SUBMITTER") && isOwner;
  const isDraft = caseDetail.status === "DRAFT";
  const isSupplementRequired = caseDetail.status === "SUPPLEMENT_REQUIRED";

  const [lines, setLines] = useState<LineRequest[]>(() => {
    if (caseDetail.lines && caseDetail.lines.length > 0) {
      return caseDetail.lines.map((l) => ({
        lineNumber: l.lineNumber,
        rawItemName: l.rawItemName,
        quantity: l.quantity,
        unitPrice: l.unitPrice,
        confirmedItemId: l.confirmedItemId || "",
      }));
    }
    return [
      {
        lineNumber: 1,
        rawItemName: "A4 복사용지 80g",
        quantity: 60,
        unitPrice: 2500,
        confirmedItemId: "ITEM-A4-80",
      },
    ];
  });

  const [isSaving, setIsSaving] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isOpeningRevision, setIsOpeningRevision] = useState(false);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const handleAddLine = () => {
    const nextLineNum = lines.length > 0 ? Math.max(...lines.map((l) => l.lineNumber)) + 1 : 1;
    setLines([
      ...lines,
      {
        lineNumber: nextLineNum,
        rawItemName: "",
        quantity: 1,
        unitPrice: 0,
        confirmedItemId: "",
      },
    ]);
  };

  const handleRemoveLine = (index: number) => {
    if (lines.length <= 1) {
      alert("최소 1개 이상의 청구 품목 라인이 필요합니다.");
      return;
    }
    const nextLines = lines.filter((_, i) => i !== index).map((l, i) => ({
      ...l,
      lineNumber: i + 1,
    }));
    setLines(nextLines);
  };

  const handleLineChange = (index: number, field: keyof LineRequest, value: unknown) => {
    const updated = [...lines];
    updated[index] = {
      ...updated[index],
      [field]: value,
    };
    setLines(updated);
  };

  const totalAmount = lines.reduce((acc, l) => acc + (Number(l.quantity) || 0) * (Number(l.unitPrice) || 0), 0);

  // 1. Save draft
  const handleSaveDraft = async () => {
    if (!credentials) return;
    setIsSaving(true);
    setSuccessMessage(null);

    const requestId = generateRequestId("draft");
    const payload = {
      requestId,
      expectedCaseVersion: caseDetail.version,
      lines: lines.map((l) => ({
        lineNumber: l.lineNumber,
        rawItemName: l.rawItemName.trim(),
        quantity: Number(l.quantity),
        unitPrice: Number(l.unitPrice),
        confirmedItemId: l.confirmedItemId?.trim() || null,
      })),
    };

    const res = await api.replaceDraftLines(credentials, caseDetail.id, payload);
    setIsSaving(false);

    if (res.ok) {
      setSuccessMessage("초안 라인이 성공적으로 저장되었습니다.");
      onRefresh();
    } else {
      onError({
        code: res.error?.code || `HTTP_${res.status}`,
        message: res.error?.message || "초안 저장 중 오류가 발생했습니다.",
        traceId: res.traceId,
        ...(res.error as object),
      });
    }
  };

  // 2. Submit case
  const handleSubmitCase = async () => {
    if (!credentials) return;
    setIsSubmitting(true);
    setSuccessMessage(null);

    const requestId = generateRequestId("submit");
    const res = await api.submitCase(credentials, caseDetail.id, {
      requestId,
      expectedCaseVersion: caseDetail.version,
    });

    setIsSubmitting(false);

    if (res.ok) {
      setSuccessMessage("청구 사건이 정상적으로 제출되었습니다 (SUBMITTED).");
      onRefresh();
    } else {
      onError({
        code: res.error?.code || `HTTP_${res.status}`,
        message: res.error?.message || "청구 사건 제출 중 오류가 발생했습니다.",
        traceId: res.traceId,
        ...(res.error as object),
      });
    }
  };

  // 3. Open revision on SUPPLEMENT_REQUIRED
  const handleOpenRevision = async () => {
    if (!credentials) return;
    setIsOpeningRevision(true);
    setSuccessMessage(null);

    const requestId = generateRequestId("revision");
    const res = await api.openSupplementRevision(credentials, caseDetail.id, {
      requestId,
      expectedCaseVersion: caseDetail.version,
    });

    setIsOpeningRevision(false);

    if (res.ok) {
      setSuccessMessage("보완 개정(Revision)이 시작되어 초안(DRAFT) 상태로 전환되었습니다.");
      onRefresh();
    } else {
      onError({
        code: res.error?.code || `HTTP_${res.status}`,
        message: res.error?.message || "보완 개정을 여는 중 오류가 발생했습니다.",
        traceId: res.traceId,
        ...(res.error as object),
      });
    }
  };

  return (
    <section className="card" aria-labelledby="draft-editor-title">
      <div className="card-header">
        <h2 id="draft-editor-title" className="card-title">
          인보이스(청구서) 라인 항목
          {isDraft && <span className="badge badge-draft">편집 가능 (DRAFT)</span>}
        </h2>

        {isDraft && isSubmitter && (
          <div style={{ display: "flex", gap: "0.5rem" }}>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={handleAddLine}
              id="btn-add-line"
            >
              + 라인 추가
            </button>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={handleSaveDraft}
              disabled={isSaving || isSubmitting}
              id="btn-save-draft"
            >
              {isSaving ? "저장 중..." : "초안 저장 (Draft Save)"}
            </button>
            <button
              type="button"
              className="btn btn-primary btn-sm"
              onClick={handleSubmitCase}
              disabled={isSaving || isSubmitting}
              id="btn-submit-case"
            >
              {isSubmitting ? "제출 중..." : "검토 제출 (Submit)"}
            </button>
          </div>
        )}
      </div>

      {successMessage && (
        <div className="alert alert-success" role="status">
          <span>{successMessage}</span>
        </div>
      )}

      {/* Supplement revision prompt */}
      {isSupplementRequired && isSubmitter && (
        <div className="alert alert-warning" role="alert" id="callout-supplement-prompt">
          <span className="alert-title">보완 요청 수신 (SUPPLEMENT_REQUIRED)</span>
          <p style={{ margin: "0.25rem 0 0.5rem 0" }}>
            승인자가 보완을 요청했습니다. 청구 내역을 수정하고 재제출하려면 아래 버튼을 눌러 새 개정판(Revision)을 여세요.
          </p>
          <div>
            <button
              type="button"
              className="btn btn-warning"
              onClick={handleOpenRevision}
              disabled={isOpeningRevision}
              id="btn-open-revision"
            >
              {isOpeningRevision ? "개정 여는 중..." : "📝 보완 개정(Revision) 시작"}
            </button>
          </div>
        </div>
      )}

      {!isOwner && isDraft && (
        <div className="alert alert-info" role="status">
          <span>제출자(<code>{caseDetail.submittedBy}</code>)만 초안을 수정 및 제출할 수 있습니다.</span>
        </div>
      )}

      {/* Lines Table */}
      <div className="table-container">
        <table className="data-table" aria-label="청구 라인 항목 목록">
          <thead>
            <tr>
              <th scope="col" style={{ width: "60px" }} className="text-center">#</th>
              <th scope="col">품목명 (Raw Item Name) *</th>
              <th scope="col" style={{ width: "160px" }}>확정 품목코드 (Item ID)</th>
              <th scope="col" style={{ width: "100px" }} className="text-right">수량 *</th>
              <th scope="col" style={{ width: "120px" }} className="text-right">단가 (원) *</th>
              <th scope="col" style={{ width: "130px" }} className="text-right">라인 금액 (원)</th>
              {isDraft && isSubmitter && (
                <th scope="col" style={{ width: "60px" }} className="text-center">삭제</th>
              )}
            </tr>
          </thead>
          <tbody>
            {lines.map((line, idx) => {
              const lineTotal = (Number(line.quantity) || 0) * (Number(line.unitPrice) || 0);

              if (isDraft && isSubmitter) {
                return (
                  <tr key={idx}>
                    <td className="text-center font-mono">{line.lineNumber}</td>
                    <td>
                      <input
                        type="text"
                        className="form-input"
                        value={line.rawItemName}
                        onChange={(e) => handleLineChange(idx, "rawItemName", e.target.value)}
                        placeholder="예: A4 복사용지 80g"
                        required
                        id={`input-item-name-${idx}`}
                      />
                    </td>
                    <td>
                      <input
                        type="text"
                        className="form-input font-mono"
                        value={line.confirmedItemId || ""}
                        onChange={(e) => handleLineChange(idx, "confirmedItemId", e.target.value)}
                        placeholder="예: ITEM-A4-80"
                        id={`input-confirmed-item-${idx}`}
                      />
                    </td>
                    <td>
                      <input
                        type="number"
                        min="1"
                        className="form-input text-right font-mono"
                        value={line.quantity}
                        onChange={(e) => handleLineChange(idx, "quantity", e.target.value)}
                        required
                        id={`input-quantity-${idx}`}
                      />
                    </td>
                    <td>
                      <input
                        type="number"
                        min="0"
                        className="form-input text-right font-mono"
                        value={line.unitPrice}
                        onChange={(e) => handleLineChange(idx, "unitPrice", e.target.value)}
                        required
                        id={`input-price-${idx}`}
                      />
                    </td>
                    <td className="text-right font-mono" style={{ fontWeight: 600 }}>
                      {lineTotal.toLocaleString("ko-KR")}
                    </td>
                    <td className="text-center">
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        style={{ color: "#dc2626" }}
                        onClick={() => handleRemoveLine(idx)}
                        aria-label={`${line.lineNumber}번 라인 삭제`}
                        id={`btn-remove-line-${idx}`}
                      >
                        ✕
                      </button>
                    </td>
                  </tr>
                );
              }

              // Read-only view
              return (
                <tr key={idx}>
                  <td className="text-center font-mono">{line.lineNumber}</td>
                  <td>{line.rawItemName}</td>
                  <td>
                    {line.confirmedItemId ? (
                      <span className="font-mono badge" style={{ backgroundColor: "#f1f5f9" }}>
                        {line.confirmedItemId}
                      </span>
                    ) : (
                      <span style={{ color: "var(--color-text-dim)" }}>- 미지정 -</span>
                    )}
                  </td>
                  <td className="text-right font-mono">{Number(line.quantity).toLocaleString("ko-KR")}</td>
                  <td className="text-right font-mono">{Number(line.unitPrice).toLocaleString("ko-KR")}</td>
                  <td className="text-right font-mono" style={{ fontWeight: 600 }}>
                    {lineTotal.toLocaleString("ko-KR")}
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr style={{ backgroundColor: "#f8fafc", fontWeight: 700 }}>
              <td colSpan={isDraft && isSubmitter ? 5 : 5} className="text-right">
                총 청구 금액 합계:
              </td>
              <td className="text-right font-mono" style={{ fontSize: "1rem", color: "#1e3a8a" }}>
                {totalAmount.toLocaleString("ko-KR")} 원
              </td>
              {isDraft && isSubmitter && <td />}
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}
