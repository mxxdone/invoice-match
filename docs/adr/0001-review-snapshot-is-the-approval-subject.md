---
status: accepted
---

# 검토 스냅샷을 승인 대상으로 사용한다

정상 대사 건에는 AI 처리 제안이 없을 수 있으므로 AI `Proposal`을 승인 필수값으로 삼지 않는다. 승인 화면에 표시한 증빙 version, 결정론적 대사 결과, 사람의 품목 매핑, 예상 검수 배분과 금액을 `ReviewSnapshot`으로 동결하고 그 ID와 payload hash를 승인 계약으로 사용한다. AI 처리 제안은 Phase 3부터 snapshot을 설명하는 선택적 근거가 되며 그 자체로 업무 효력을 갖지 않는다.

## Consequences

- AI 사용 여부와 무관하게 동일한 승인·stale 검증 경로를 사용한다.
- snapshot 입력이 하나라도 바뀌면 새 snapshot과 사람의 재검토가 필요하다.
