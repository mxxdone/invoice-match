# 백엔드 3계층/클린코드 정리 (R1-01)

작성일: **2026-10-02**  
브랜치: `refactor/three-layer-cleanup`  
범위: `core-api` main/test, `project-docs`  
관련 Ticket: `Plan.md` R1-01 (완료), R1-02 (후속·미착수)

## 1. 배경과 목적

Phase 1 인수 후 백엔드 코드 리뷰에서 application 계층이 일부 persistence/SQL과 canonical 직렬화 책임을 함께 들고 있는 지점이 확인됐다. 이 문서는 그 정리 작업의 근거·범위·결과를 기록한다. 목표는 계층 책임을 분리해 테스트 가능성과 변경 안정성을 높이는 것이며, 공개 계약과 동시성 불변식을 바꾸는 것이 아니다.

## 2. 범위와 비범위

- 범위는 `core-api`의 main/test와 문서다. 프런트엔드(`web`), DB migration, 신규 의존성, 무관 기능은 포함하지 않는다.
- `main` 병합·push는 하지 않는다.
- 리팩터링은 bounded finding 단위로만 수행하고, finding에 없는 대규모 패키지 이동은 하지 않는다.

## 3. 보존한 계약 (변경 금지)

- 공개 API의 JSON/status/error schema. 예외적으로 oversized page 입력만 새로 `400 VALIDATION_ERROR`가 된다.
- actor-scoped 멱등 `(scope, resource, actor, requestId)`의 정확한 HTTP replay(저장된 status + response 값).
- 승인의 단일 트랜잭션: allocation + decision + PaymentRequest + Outbox + case + audit.
- lock 순서(`invoice_case -> payment_request -> outbox_event`)와 트랜잭션 전파. 외부 HTTP는 lock/트랜잭션 밖.
- canonical JSON byte와 hash 알고리즘(legacy review version 포함). Spring 전역 `ObjectMapper`를 canonical serializer에 주입하지 않는다.

## 4. Finding별 변경 내용

### R1-01-1 — 목록 조회 page/size 정수 overflow

- `InvoiceCaseQueryService.list`가 page/size를 clamp한 뒤 offset을 `long`으로 계산하고 `Integer.MAX_VALUE` 초과 시 `DomainValidationException`을 던진다. `hasNext`는 `page + 1L < totalPages`로 계산하고 `totalPages`도 overflow 없이 계산한다.
- 회귀 HTTP 통합 테스트 추가: `page=2147483647`(default size 20)와 `size=100`은 `400 VALIDATION_ERROR`, 정상 empty/out-of-range page와 `size=1` 최대 page는 `hasNext=false`.

### R1-01-2 — 목록 Criteria/SQL의 persistence 분리

- `invoicecase.persistence.InvoiceCaseListQueryStore`가 `EntityManager`, CriteriaBuilder, projection, 모든 predicate와 `LIKE` escaping을 소유한다.
- projection은 persistence 소유 typed record `InvoiceCaseListRow`이며 application(`InvoiceCaseSummary.from`)이 DTO로 매핑한다. persistence는 application 업무 타입에 역의존하지 않는다.
- actor scope 판단(`SUBMITTER` 본인 한정)과 invoice number normalization은 application에 남긴다. access-scope 정책을 SQL에 넣지 않는다.

### R1-01-3 — Webhook 저장 SQL 분리

- `payment.persistence.PaymentResultEventStore`가 event advisory lock/dedup, canonical outbox context lock, typed status read, conditional acknowledge/failed update, result event insert를 소유한다.
- `PaymentResultWebhookApplicationService`가 replay/conflict/state 정책과 단일 `@Transactional` owner를 유지한다. 저장 모듈 메서드는 `Propagation.MANDATORY`로만 참여해 독립 커밋(부분 커밋)이 불가능하다.
- ACK write order `invoice_case -> payment_request -> outbox_event -> event insert`와 실패 시 rollback을 그대로 유지한다. application은 `Map` cast 대신 typed record를 사용한다.

### R1-01-4 — OutboxStore 이동

- SQL 중심 `OutboxStore`와 그 데이터 계약(`ClaimedEvent`, `SendingEvent`, `StaleClaimException`)을 `payment.application`에서 `payment.persistence`로 옮겼다. relay(application)가 persistence 계약을 import한다.
- 짧은 트랜잭션과 claim-token compare-and-set 동작은 변경하지 않았다.
- `RequestIdempotencyStore`/감사 SQL은 이미 단일 책임 store이며 패키지 이동이 기능적 이득 없이 churn만 늘려 이번 범위에서 의도적으로 유지했다.

### R1-01-5 — 승인 payload/audit helper

- `ApprovalDecisionPayload`(순수, 전용 default `ObjectMapper`)가 decision JSON을, `ApprovalAuditPayloads`가 before/after/allocation summary를 만든다.
- audit `after`는 반환하는 `ApprovalResult`에서 파생해 응답과 감사가 어긋나지 않는다. object key 순서는 저장 계약이 아니며(`AuditStateSummarizer`가 재귀 정렬, `jsonb` 저장), allocation 배열 순서만 보존된다.
- 생성자 주입 의존성은 늘리지 않았고, 승인 write는 단일 트랜잭션 owner로 유지한다.

### R1-01-6 — MatchEngine 계산/직렬화 분리

- `MatchResultPayloadEncoder`가 canonical JSON shape와 SHA-256을 소유하고, `MatchEngine.compute(MatchInput) -> MatchComputation` 공개 인터페이스는 유지한다.
- encoder는 engine이 이미 정한 collection 순서를 보존하며 새 정렬을 추가하지 않는다.
- 리팩터링 전 baseline에서 캡처한 golden canonical JSON과 hash(`30fbe4f1...040c92`)를 `MatchEngineGoldenTest`가 byte 단위로 검증한다.

### R1-01-7 — ReviewService 보완/거절 중복 제거

- `prepareSimpleDecision`(상태/버전 검증, purchase order lock, target hash/currentness)과 `completeSimpleDecision`(decision 저장, 상태 전이, audit, 멱등 응답)을 공유한다.
- `SimpleDecisionOperation` private enum이 scope/audit action/decision type/target status를 묶어 idempotency begin과 completion에서 같은 descriptor를 쓴다(boolean 제어 없음). public method, fingerprint, lock 순서, target 상태는 그대로다.

### R1-01-8 — CommandResult HTTP 결합 문서화

- `CommandResult`가 HTTP status를 품는 것은 persisted replay 호환을 위한 의도적 선택임을 명시했다. 저장된 status + response 값을 재현하며 body는 decode 후 HTTP 계층이 재직렬화한다. domain entity의 JPA annotation도 의도적으로 유지한다.

## 5. 검증 결과

환경: Java 21 (Temurin 21.0.5), Docker 29.1.3, Testcontainers PostgreSQL, Windows pwsh.

| 단계 | 명령 | 결과 |
|---|---|---|
| focused unit | `.\gradlew.bat test --tests "...MatchEngineTest" --tests "...MatchEngineGoldenTest" --tests "...ApprovalAggregatesTest" --tests "...ApprovedAllocationPlanFactoryTest" --console=plain` | BUILD SUCCESSFUL |
| focused integration | `.\gradlew.bat test --tests "...InvoiceCaseReadApiIntegrationTest" --tests "...PaymentResultWebhookIntegrationTest" --tests "...PaymentExportRelayIntegrationTest" --tests "...ApprovalWorkflowIntegrationTest" --tests "...ReviewWorkflowApiIntegrationTest" --tests "...MatchingApiIntegrationTest" --console=plain` | BUILD SUCCESSFUL (6m 1s) |
| whole backend | `.\gradlew.bat test --console=plain` | BUILD SUCCESSFUL (8m 15s), **521 tests, 0 failures, 0 errors, 0 skipped** (58 test classes) |
| boot jar | `.\gradlew.bat bootJar --console=plain` | BUILD SUCCESSFUL |

- 실패를 skip/flaky retry로 숨기지 않았다. `-q`나 출력 truncation을 쓰지 않았다.
- 검증에 사용한 DB는 Testcontainers가 띄운 일회성 PostgreSQL이며, 테스트 종료 후 컨테이너를 남기지 않았다. 별도 상주 서버/포트는 만들지 않았다.

## 6. 아키텍처 가드

- `shared/ArchitectureLayeringTest`가 소스 import를 스캔해 (a) 목록/webhook persistence가 application/webhook/api 타입에 의존하지 않고, (b) `OutboxStore`가 `payment.persistence`에 있음을 강제한다. ArchUnit 의존성은 추가하지 않았다.

## 7. 위험과 한계

- canonical byte/hash는 golden 테스트 1개 입력으로 고정했다. 기존 shuffled-order/determinism 테스트와 함께 보강되지만, 모든 입력 조합을 동결하는 것은 아니다.
- `RequestIdempotencyStore`의 패키지는 그대로다. SQL 분리 finding은 "기계적으로 안전할 때만" 조건이었고 이번엔 유지가 더 안전하다고 판단했다.
- `PaymentResultEventStore` 메서드는 `MANDATORY`이므로 반드시 application 트랜잭션 안에서만 호출된다. 향후 직접 호출하는 코드가 생기면 트랜잭션 없이는 즉시 실패한다.

## 8. 후속 (미착수, 이 브랜치 범위 아님)

- **R1-02 — main CI IntegrationTest 실패 조사·복구:** 사용자 보고만 접수된 상태다. 실패 로그·run URL·실패 테스트명이 아직 없어 원인을 추정하지 않는다. 원본 `main`에서 재현 → 원인 분류(테스트/코드/runner·환경) → 최소 수정 → 해당 IntegrationTest 및 CI 전체 재검증을 `Plan.md` R1-02에서 다룬다. 이 리팩터링 브랜치는 R1-01 결과만 담고 CI 구현을 섞지 않는다.
