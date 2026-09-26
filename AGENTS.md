# Invoice Match Agent Instructions

`AGENTS.md`는 문서 라우터다. 모든 문서를 미리 읽지 말고 현재 작업에 필요한 자료만 연다.

## Read only what the task needs

- 구현 작업: `Implement.md`와 `docs/Plan.md`의 현재 Ticket만 읽는다. Ticket 계약이 충분하면 다른 문서를 열지 않는다.
- 제품 범위·업무 흐름·상태·불변식을 변경하거나 Ticket이 모호할 때: `docs/Spec.md`의 관련 절만 읽는다.
- 도메인 용어를 추가하거나 바꿀 때: `CONTEXT.md`만 읽고 필요하면 갱신한다.
- 기존 설계 선택을 변경할 때: 직접 관련된 `docs/adr/` 파일만 읽는다.
- `docs/EngineeringNotes.md`는 포트폴리오·면접용 문제 해결 기록이다. 구현 중에는 읽지 말고, Ticket 인수 후 기록을 추가하거나 해당 기록을 요청받았을 때만 연다.
- 문서나 코드가 서로 충돌하면 임의로 해석하지 말고 충돌을 보고한다.
