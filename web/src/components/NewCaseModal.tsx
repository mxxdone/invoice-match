"use client";

import React, { useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { api } from "@/lib/api-client";
import { generateRequestId } from "@/lib/id-generator";
import { InvoiceCaseDetail } from "@/types/api";

interface NewCaseModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (newCase: InvoiceCaseDetail) => void;
}

export function NewCaseModal({ isOpen, onClose, onSuccess }: NewCaseModalProps) {
  const { credentials } = useAuth();
  const [supplierId, setSupplierId] = useState("SUP-1");
  const [purchaseOrderId, setPurchaseOrderId] = useState("PO-1001");
  const [invoiceNumber, setInvoiceNumber] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!credentials) return;

    if (!supplierId.trim() || !purchaseOrderId.trim() || !invoiceNumber.trim()) {
      setError("공급사 ID, 발주서 번호, 청구번호를 모두 입력해주세요.");
      return;
    }

    setIsSubmitting(true);
    setError(null);

    const requestId = generateRequestId("create");
    const res = await api.createCase(credentials, {
      requestId,
      supplierId: supplierId.trim(),
      purchaseOrderId: purchaseOrderId.trim(),
      invoiceNumber: invoiceNumber.trim(),
    });

    setIsSubmitting(false);

    if (res.ok && res.data) {
      onSuccess(res.data);
      onClose();
    } else {
      setError(res.error?.message || "청구 사건 생성 중 오류가 발생했습니다.");
    }
  };

  return (
    <div className="modal-overlay" role="dialog" aria-modal="true" aria-labelledby="modal-new-case-title">
      <div className="modal-content">
        <div className="modal-header">
          <h2 id="modal-new-case-title" className="card-title">
            새 인보이스(청구서) 사건 작성
          </h2>
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={onClose}
            aria-label="닫기"
          >
            ✕
          </button>
        </div>

        {error && (
          <div className="alert alert-danger" role="alert">
            <span>{error}</span>
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label htmlFor="new-supplier-id" className="form-label">
              공급사 ID (Supplier ID) *
            </label>
            <input
              id="new-supplier-id"
              type="text"
              className="form-input"
              value={supplierId}
              onChange={(e) => setSupplierId(e.target.value)}
              placeholder="예: SUP-1"
              required
              disabled={isSubmitting}
            />
          </div>

          <div className="form-group">
            <label htmlFor="new-po-id" className="form-label">
              발주서 번호 (Purchase Order ID) *
            </label>
            <input
              id="new-po-id"
              type="text"
              className="form-input"
              value={purchaseOrderId}
              onChange={(e) => setPurchaseOrderId(e.target.value)}
              placeholder="예: PO-1001"
              required
              disabled={isSubmitting}
            />
          </div>

          <div className="form-group">
            <label htmlFor="new-invoice-num" className="form-label">
              인보이스(청구서) 번호 *
            </label>
            <input
              id="new-invoice-num"
              type="text"
              className="form-input"
              value={invoiceNumber}
              onChange={(e) => setInvoiceNumber(e.target.value)}
              placeholder="예: INV-2026-001"
              required
              disabled={isSubmitting}
            />
            <small style={{ color: "var(--color-text-muted)", fontSize: "0.75rem", marginTop: "0.25rem", display: "block" }}>
              * 동일 공급사 내에서 정규화된 청구번호는 중복될 수 없습니다.
            </small>
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={onClose}
              disabled={isSubmitting}
            >
              취소
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={isSubmitting}
              id="btn-create-case-submit"
            >
              {isSubmitting ? (
                <>
                  <span className="spinner" aria-hidden="true" />
                  <span>생성 중...</span>
                </>
              ) : (
                "초안 사건 생성 (DRAFT)"
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
