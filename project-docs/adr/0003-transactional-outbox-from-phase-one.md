---
status: accepted
---

# 지급요청용 Transactional Outbox를 Phase 1부터 사용한다

검수 배분과 지급요청이 commit되었지만 ERP 인계 요청이 유실되는 간극을 없애기 위해 PaymentRequest와 OutboxEvent를 승인 트랜잭션에 함께 저장한다. Phase 1은 인프로세스 relay가 Mock ERP HTTP adapter를 호출하고, Phase 2에서 같은 계약 뒤에 RabbitMQ·제한 재시도·DLQ를 추가한다.

## Consequences

- Phase 1 구조가 조금 늘지만 승인과 외부 인계의 신뢰성 경계를 처음부터 유지한다.
- 전달은 at-least-once이므로 Mock ERP와 webhook 수신자는 멱등해야 한다.
