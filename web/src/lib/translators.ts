// Domain translators for error reasons, match exceptions, and audit actions.

export function translateStaleReason(reason: string): string {
  switch (reason) {
    case "CASE_STATE":
      return "사건 상태 불일치 (현재 상태에서는 의사결정을 진행할 수 없습니다)";
    case "CASE_VERSION":
      return "사건 버전 불일치 (다른 사용자가 사건을 동시 수정하여 버전이 갱신되었습니다)";
    case "EVIDENCE_BUNDLE":
      return "증빙 번들 변경 (새로운 청구 라인 증빙 번들이 등록되었습니다)";
    case "MATCH_RESULT":
      return "대사 결과 불일치 (새로운 3-way 대사가 실행되었거나 결과가 갱신되었습니다)";
    case "MAPPING":
      return "품목 매핑 변경 (품목 매핑 규칙이 추가 또는 변경되었습니다)";
    case "PURCHASING_SNAPSHOT":
      return "구매/발주 스냅샷 변경 (발주서 또는 검수 정보가 외부 시스템에서 갱신되었습니다)";
    case "SUPERSEDED":
      return "스냅샷 만료 (더 최신의 검토 스냅샷이 이미 동결되었습니다)";
    default:
      return reason;
  }
}

export function translateExceptionType(type: string): string {
  switch (type) {
    case "ITEM_UNCONFIRMED":
      return "품목 코드 미확정 (청구 라인에 확정 품목코드가 누락되었습니다)";
    case "PO_LINE_NOT_FOUND":
      return "발주 라인 미존재 (일치하는 발주서 품목 라인을 찾을 수 없습니다)";
    case "PO_LINE_AMBIGUOUS":
      return "발주 라인 모호 (동일 품목코드에 대해 복수의 발주 라인이 존재합니다)";
    case "UNIT_PRICE_MISMATCH":
      return "단가 불일치 (청구 단가와 발주 단가가 일치하지 않습니다)";
    case "QUANTITY_EXCEEDS_RECEIPT_BALANCE":
      return "검수 잔여수량 초과 (확정된 검수 잔여 수량을 초과하여 청구되었습니다)";
    case "DUPLICATE_INVOICE_SUSPECTED":
      return "중복 청구 의심 (동일 공급사의 동일 청구서 번호가 다른 사건에 이미 존재합니다)";
    default:
      return type;
  }
}

export function translateAuditAction(action: string): string {
  switch (action) {
    case "CREATE":
      return "사건 생성 (CREATE)";
    case "DRAFT_REPLACE":
      return "초안 라인 저장 (DRAFT_REPLACE)";
    case "SUBMIT":
      return "청구서 제출 (SUBMIT)";
    case "REVISION_OPEN":
      return "보완 개정 시작 (REVISION_OPEN)";
    case "MATCH":
      return "3-Way 대사 실행 (MATCH)";
    case "REVIEW_FREEZE":
      return "검토 스냅샷 동결 (REVIEW_FREEZE)";
    case "MAPPING_RECORD":
      return "품목 매핑 확정 (MAPPING_RECORD)";
    case "SUPPLEMENT_REQUEST":
      return "보완 요청 (SUPPLEMENT_REQUEST)";
    case "REJECT":
      return "청구 거절 (REJECT)";
    case "APPROVE":
      return "최종 승인 (APPROVE)";
    case "OUTBOX_CREATE":
      return "ERP 아웃박스 생성 (OUTBOX_CREATE)";
    case "OUTBOX_DELIVERY":
      return "ERP 전달 완료 (OUTBOX_DELIVERY)";
    default:
      return action;
  }
}
