# Invoice Match 구현 런북

이 문서는 Ticket을 실행할 때 따르는 방법만 정의한다. 제품 요구사항의 원본은 `Spec.md`, 로드맵과 Ticket의 원본은 `Plan.md`다.

## 실행 원칙

- 한 번에 `Plan.md`의 Ticket 하나를 완료한다.
- 현재 Ticket 범위와 의존성을 지키고 미래 Phase 기능이나 무관한 정리를 추가하지 않는다.
- 업무 규칙을 변경해야 하면 코드보다 `Spec.md`, `CONTEXT.md`와 사건 기반 테스트를 먼저 갱신한다.
- Ticket의 Acceptance Criteria와 검증을 통과하지 못하면 다음 Ticket으로 넘어가지 않는다.
- 변경 범위에 맞는 테스트, lint, build와 smoke test를 실행하고 실패 원인을 수정한다.
- Ticket 인수 과정에서 재현된 유의미한 정합성·동시성·복구·성능·운영 문제가 있으면 해결과 검증이 끝난 뒤 `EngineeringNotes.md`에 결과 중심으로 추가한다. 단순 컴파일 오류나 일회성 시행착오는 기록하지 않는다.
- 문서와 코드가 충돌하면 임의로 해석하지 않고 Head에게 보고한다.

## 위임과 교차 검증

- 같은 workspace에서 병렬 writer를 실행하지 않는다.
- P1-02, P1-03, P1-06은 계약 고정 후 격리된 worktree에서 최대 2개까지 병렬화할 수 있다.
- 비시각 구현은 Implementation Worker, UI는 frontend specialist에게 위임할 수 있다.
- P1-07은 별도 Review Agent와 실제 PostgreSQL 동시성 테스트로 교차 검증한다.
- 동시성·보안·아키텍처·외부 인계처럼 위험한 변경은 독립 Review Agent 또는 독립 통합 테스트로 검증한다.
- Head는 Ticket 계약, 통합 diff, 검증 증거와 최종 인수를 소유한다.

## Agent 작업 계약

```text
GOAL         완료해야 할 구체적 결과
SCOPE        소유 파일·패키지·계층
CONTRACT     API·상태·불변식·호환성
CONSTRAINTS  금지 범위와 도입 금지 기술
VERIFY       반드시 통과할 테스트와 사건
REPORT       변경 범위·검증 결과·결정·남은 위험
```

Worker 보고는 검증 증거이지 자동 승인으로 간주하지 않는다.

## Git 및 작업공간 운영 규칙

- `main`은 통합이 완료되고 검증을 통과한 상태로 유지한다.
- 최초 기준선인 P1-00은 `main`에 기준 커밋으로 확정한다.
- P1-01부터 구현 Ticket마다 짧게 유지되는 전용 브랜치를 사용한다.
  - 예: `feat/p1-01-domain-model`
  - 수정: `fix/p1-01-...`
  - 문서만 변경: `docs/...`
- 구현을 서브에이전트에게 위임할 때는 해당 Ticket 전용 Paseo
  worktree와 브랜치를 사용한다.
- 하나의 worktree에는 한 명의 작성자만 둔다.
- Worker는 할당된 Ticket 범위에서만 수정하고 테스트한다.
- Worker는 자신의 Ticket 브랜치에만 커밋할 수 있으며,
  `main` 병합과 배포는 Head가 담당한다.
- Head는 Acceptance Criteria, 자동 테스트, diff 검토와 필요한 독립 리뷰가 완료된 후에만 통합한다.
- 선행 Ticket에 의존하는 작업은 선행 변경이 통합된 뒤 분기한다.
- 병합이 끝난 Ticket의 worktree와 브랜치는 정리한다.
- 사소한 문서 수정은 활성 Ticket 브랜치에서 처리할 수 있지만, 구현 코드는 `main`에 직접 작성하지 않는다.

## Phase 1 착수 Gate

- `Spec.md` 1.1과 관련 ADR이 구현 기준으로 승인되어 있다.
- P1-00 외 Ticket은 선행 계약 Ticket 완료 전 시작하지 않는다.
- 각 Ticket에 구현 담당과 검증 방법이 정해져 있다.
- 구현은 사용자의 별도 착수 지시 후 Ticket 단위로 수행한다.
