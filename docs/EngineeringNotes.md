# Invoice Match Engineering Notes

이 문서는 포트폴리오와 면접에서 설명할 가치가 있는 문제 해결 사례만 보존한다. 작업 일지나 실패 목록이 아니며, 재현 가능한 위험·원인·해결·검증이 갖춰진 사례만 추가한다.

구현 에이전트의 기본 입력 문서가 아니다. Ticket 인수 후 기록을 갱신할 때만 읽는다.

## 기록 기준

- 업무 정합성, 동시성, 멱등성, 장애 복구, 보안, 성능 또는 운영 안정성에 실제 영향을 주는 문제
- 단순 코드 수정이 아니라 설계 선택과 trade-off를 설명할 수 있는 문제
- 자동 테스트나 재현 절차로 해결을 입증한 문제
- 해결되지 않은 사항은 완료 사례처럼 쓰지 않고 `남은 고려사항`으로 명시

## P1-00 — CI와 실행환경의 숨은 전제 제거

### 문제

Windows에서 생성한 Gradle wrapper가 Linux GitHub Actions에서 실행되지 않았고, Docker Compose는 테스트 본문뿐 아니라 실패 후 정리 단계에서도 `POSTGRES_PASSWORD`를 해석했다. 환경변수가 뒤늦게 만들어지면 본 테스트와 `always()` 정리가 모두 실패해 최초 실패 원인이 가려질 수 있었다.

### 원인

- 실행 비트와 줄바꿈이 개발 OS에 암묵적으로 의존했다.
- Compose 설정 해석에 필요한 값의 수명을 개별 step 수준으로만 보았다.

### 해결

- `core-api/gradlew` 실행 비트와 LF 줄바꿈을 저장소에서 고정했다.
- 폐기 가능한 CI 전용 DB 암호를 job 환경변수로 올려 모든 Compose step과 정리 단계에서 동일하게 사용하도록 했다.
- CI 실행 범위를 `main` push, pull request, 수동 실행으로 명시했다.

### 검증과 교훈

GitHub Actions 전체 job이 성공하고 실패 경로의 Compose 종료도 정상 동작하는지 확인했다. CI는 테스트 명령뿐 아니라 setup·teardown이 공유하는 환경 계약까지 포함해야 한다.

관련 커밋: `aedcb91`

## P1-01 — 개별 외래키만으로는 동일 사건 정합성을 보장할 수 없음

### 문제

`ReviewSnapshot`과 `ReviewDecision`의 각 식별자에 외래키가 있어도 서로 다른 청구 사건이나 증빙 version을 조합할 수 있었다. 또한 append-only로 정의한 증빙·대사·검토 레코드는 UPDATE만 차단되고 DELETE는 가능했다.

### 위험

승인 화면의 사건, 증빙, 대사 결과와 해시가 서로 다른 대상을 가리킬 수 있으며, 이후 삭제로 감사 근거가 사라질 수 있었다.

### 해결

- `(id, invoice_case_id)`와 `(id, invoice_case_id, version)` 후보키를 추가했다.
- 사건 ID와 version을 포함한 복합 외래키로 draft, 증빙, 대사 결과, snapshot, 결정을 연결했다.
- snapshot의 `MatchResult`가 같은 사건뿐 아니라 정확히 같은 `EvidenceBundle`을 사용하도록 묶었다.
- append-only 테이블의 UPDATE와 DELETE를 모두 DB trigger로 차단했다.
- `EvidenceBundle`은 같은 사건의 `SEALED` draft만 참조하도록 상태 discriminator와 복합 외래키로 강제했다.

### 검증과 교훈

다른 사건, 다른 증빙 version, 다른 해시, OPEN draft를 조합하는 PostgreSQL 음성 테스트를 추가했다. 객체별 유효성만으로는 aggregate 간 정합성을 보장할 수 없으며, 중요한 승인 근거는 DB 관계 자체가 유효한 조합만 표현하도록 설계해야 한다.

관련 커밋: `4d01981`, `9f7df58`

## P1-02 — 외부 snapshot 갱신과 안정적인 행 식별자

### 문제

새 구매·검수 snapshot을 받을 때 기존 자식 행을 전부 삭제하고 새 UUID로 생성하면, 향후 `ReceiptAllocation` 외래키와 행 잠금 대상이 매번 바뀐다. payload는 최신인데 정규화된 조회 행은 오래된 발주 라인을 가리키거나, 읽는 도중 refresh가 커밋되어 root version과 자식 version이 섞일 가능성도 있었다.

### 해결

- 외부 자연키로 기존 행을 in-place 갱신하여 내부 UUID를 보존했다.
- 최신 snapshot에서 사라진 행은 삭제하지 않고 `active=false`로 전환했다.
- 외부 aggregate, PO, receipt, receipt-line version을 각각 조회 가능한 컬럼으로 분리했다.
- receipt-line의 발주 라인 참조 변경도 동일 행에서 갱신되도록 했다.
- 조회는 `REQUIRES_NEW + REPEATABLE_READ`로 고정하여 호출자 트랜잭션과 무관하게 하나의 일관된 snapshot을 읽게 했다.
- 최초 동시 refresh는 PostgreSQL transaction advisory lock으로 직렬화했다.
- 문자열 이어붙이기 복합키 대신 구조화된 `(receiptId, receiptLineId)` 키를 사용했다.

### 검증과 교훈

UUID 보존, 비활성화, 참조 변경, 동시 최초 refresh, refresh 중 일관 읽기, 구분자 충돌을 실제 PostgreSQL 통합 테스트로 검증했다. 외부 데이터 동기화에서 자연키는 검색 조건일 뿐이며, 내부 참조와 잠금을 위한 행 정체성은 갱신 사이에도 안정적으로 유지되어야 한다.

관련 커밋: `4a8d63a`, `2b6ce80`, `1a380bd`

## P1-03 — 외부 조회와 로컬 멱등 트랜잭션의 경계

### 문제

청구 사건 생성 전에 외부 PO snapshot을 별도 트랜잭션으로 저장하면, 같은 request ID로 경쟁한 패자나 이후 validation 실패 요청도 로컬 snapshot 변경을 남길 수 있었다. 멱등성 예약 행 역시 응답 없이 commit될 수 있으면 해당 key가 영구적으로 오염될 수 있었다.

### 해결

- 외부 HTTP fetch와 payload 검증·canonicalize는 DB transaction 밖에서 수행했다.
- `PreparedPurchaseOrderSnapshot`을 만든 뒤, 멱등 key 예약 승자만 로컬 write transaction 안에서 snapshot·사건·응답을 함께 commit하도록 분리했다.
- 응답이 채워지지 않은 idempotency row는 commit 시점에 거부하는 deferred constraint trigger를 추가했다.
- invoice number 정규화처럼 외부 호출 전에 가능한 validation은 가장 먼저 수행했다.

### 검증과 교훈

같은 request ID의 동시 생성, 서로 다른 PO payload의 경쟁, 외부 조회 후 로컬 validation 실패를 재현해 패자 snapshot과 반쪽 사건이 남지 않음을 검증했다. 외부 호출을 transaction 밖으로 빼는 것과 외부 결과의 로컬 반영을 원자적으로 만드는 것은 동시에 만족할 수 있으며, 그 사이에는 명시적인 준비 객체가 유용하다.

관련 커밋: `da2a4cf`

## P1-03 — 제출 봉인과 원시 SQL 변경 사이의 TOCTOU 경합

### 문제

봉인된 revision의 라인 변경을 trigger로 차단해도, trigger가 OPEN 상태를 읽은 직후 다른 transaction이 revision을 봉인할 수 있었다. 라인 변경 transaction이 나중에 commit하면 동결 payload와 관계형 라인이 달라지는 TOCTOU(time-of-check to time-of-use) 문제가 발생했다.

### 해결

- 모든 `invoice_line` INSERT·UPDATE·DELETE trigger가 상태 확인 전에 대상 `draft_revision` 행을 `FOR UPDATE`로 잠그게 했다.
- 라인을 다른 revision으로 이동할 때는 두 UUID를 정렬해 동일한 순서로 잠갔다.
- 제출 경로도 `invoice_case → draft_revision → invoice_line` 순으로 잠근 뒤 라인을 읽고 해시를 계산하도록 통일했다.
- 이미 `SEALED`인 revision과 라인은 UPDATE·DELETE·이동을 모두 차단했다.

### 검증과 교훈

두 PostgreSQL connection으로 변경 우선과 제출 우선 순서를 모두 재현했다. 변경 우선이면 제출이 기다렸다가 최신 라인을 동결했고, 제출 우선이면 후속 변경이 봉인 후 거부됐다. 불변성 검사는 조건문만으로 완성되지 않으며, 검사 대상과 변경 대상이 같은 잠금 프로토콜을 공유해야 한다.

관련 커밋: `48e9baf`

## P1-04 — 라인별로 정상인 배분이 전체로는 초과될 수 있음

### 문제

각 청구 라인이 동일한 원본 검수 후보 목록으로 독립 계산되면, 검수 10개에 청구 라인 10개+10개가 모두 정상으로 판정되어 예상 배분 합계가 20개가 될 수 있었다. 초기 property test도 청구 라인 하나만 생성해 aggregate 초과를 발견하지 못했다.

### 해결

- 한 번의 대사 실행 전체가 공유하는 잔량 ledger를 `(receiptId, receiptLineId)` 구조화 키로 만들었다.
- 청구 라인을 `lineNumber` 순으로 처리하고 각 FIFO 계획이 ledger에서 즉시 차감되도록 했다.
- 라인별 가용량은 해당 라인 처리 직전의 남은 수량으로 정의했다.
- 부족한 라인은 남은 수량까지만 예상 배분하고 `QUANTITY_EXCEEDS_RECEIPT_BALANCE`를 함께 기록했다.

### 검증과 교훈

두 청구 라인이 하나의 검수 라인을 경쟁하는 사례와 다중 라인 randomized property test를 추가했다. property는 라인별 합뿐 아니라 모든 청구 라인을 합친 검수별 상한을 검사한다. 지역적으로 옳은 계산의 합이 전역 불변식을 깨뜨릴 수 있으므로 공유 자원은 aggregate 수준에서 검증해야 한다.

관련 커밋: `5f5cf7e`

## P1-04 — 사건 version과 최신 증빙의 혼합 읽기

### 문제

기본 `READ COMMITTED`에서 사건과 최신 `EvidenceBundle`을 별도 query로 읽으면, 그 사이 보완 제출이 commit되어 이전 사건 version과 새 bundle이 한 `MatchResult`에 섞일 수 있었다. 상태를 처음 확인한 뒤 거절·승인이 진행되어도 대사 결과가 뒤늦게 저장될 수 있었다.

### 해결

- 사건 행을 `PESSIMISTIC_WRITE`로 먼저 잠근 후 최신 bundle을 읽었다.
- 잠금을 `MatchResult` insert와 idempotency 응답 저장까지 유지했다.
- 기존 쓰기 경로와 동일한 `invoice_case → child` 잠금 순서를 사용했다.
- 대사는 최신 동결 증빙이 유효한 `REVIEW_PENDING`에서만 허용하고 `SUPPLEMENT_REQUIRED`에서는 차단했다.

### 검증과 교훈

대사가 먼저 잠금을 가진 경우와 상태 변경 writer가 먼저 가진 경우를 실제 PostgreSQL에서 각각 재현했다. 전자는 일관된 구 version 결과를 저장한 뒤 writer가 진행하고, 후자는 대사가 새 상태를 확인해 결과를 남기지 않았다. 여러 query를 한 transaction에 넣는 것만으로 동일 snapshot이 보장되는 것은 아니며, 업무 version 경계에는 명시적인 잠금 또는 격리 전략이 필요하다.

관련 커밋: `5f5cf7e`

## P1-04 — 수량 overflow와 최신 결과 순서

### 문제

개별 검수수량이 유효한 `int`여도 여러 행의 합은 `Integer.MAX_VALUE`를 넘을 수 있었다. 또한 동일 시각에 결과가 두 개 생성되면 `created_at + random UUID` 정렬은 실제 생성 순서를 의미하지 않았다.

### 해결

- 가용량, 남은 수량, 계획 합계를 `long`으로 계산하고 payload에도 그대로 보존했다.
- 사건 잠금 아래에서 `result_number = max + 1`을 발급했다.
- `(invoice_case_id, result_number)` unique constraint와 양수 check를 추가하고 최신·이력 조회를 결과 번호 기준으로 변경했다.
- `MatchResult.evidence_bundle_id`도 NOT NULL로 강화해 증빙 없는 결과를 DB에서 표현할 수 없게 했다.

### 검증과 교훈

`Integer.MAX_VALUE` 검수 두 건의 합, 중복 결과 번호, null bundle, 동일 시각의 연속 대사를 테스트했다. 필드 하나의 범위와 집계 결과의 범위는 다르며, 업무 순서를 시간·무작위 ID에 암묵적으로 기대하지 말고 명시적인 단조 번호로 표현하는 편이 안전하다.

관련 커밋: `5f5cf7e`

## P1-05 — append-only 이력의 안전한 스키마 진화

### 문제

V5가 기존 `MatchResult`, `ReviewSnapshot`, `ReviewDecision`에 새 source 필드를 추가하고 값을 backfill하려 했지만, 이 테이블들은 V1부터 UPDATE를 거부하는 append-only trigger로 보호되고 있었다. 빈 데이터베이스에서 전체 migration을 실행하는 테스트는 통과했지만, 실제 V4 데이터가 있는 환경에서는 V5 적용 자체가 실패했다.

### 해결

- 하나의 migration transaction 안에서 해당 append-only trigger만 명시적으로 비활성화했다.
- 기존 관계를 기준으로 source version, hash와 watermark를 backfill했다.
- backfill 직후 trigger를 다시 활성화하고 새 NOT NULL·복합 외래키 제약을 적용했다.
- V4까지만 적용한 데이터베이스에 실제 이력 행을 넣은 뒤 V5로 올리는 전용 upgrade test를 추가했다.

### 검증과 교훈

업그레이드 후 backfill 값과 Flyway 이력을 확인하고, 비활성 상태로 남은 trigger가 0개인지 검증했다. 새 설치 테스트만으로는 운영 migration을 보장할 수 없다. 불변 이력 테이블의 스키마를 진화시킬 때는 과거 데이터, 보호 trigger와 제약 적용 순서를 포함한 `N-1 → N` 시험이 필요하다.

관련 커밋: `1d06710`

## P1-05 — 검토 결정과 외부 사실 갱신의 원자성

### 문제

검토 쓰기가 청구 사건만 잠근 상태에서 구매 snapshot을 검증하면, 검증 직후 refresh가 새 구매 사실을 commit할 수 있었다. 그러면 이미 stale이 된 사실을 근거로 매핑·보완·거절 결정이나 후속 snapshot이 저장될 수 있다. 여러 query가 같은 transaction에 있다는 사실만으로 서로 다른 aggregate의 변경은 막히지 않았다.

### 해결

- 구매 refresh와 검토 write가 동일한 transaction advisory lock을 사용하도록 `PurchaseOrderSnapshotLock`으로 잠금 계약을 통합했다.
- 검토 write의 잠금 순서를 `invoice_case → purchase order advisory lock → currentness 검증 → 결정/commit`으로 고정했다.
- snapshot과 source match result의 bundle, 결과 번호, 구매 version/hash, mapping watermark를 복합 후보키·외래키로 묶었다.
- mapping decision도 대상 snapshot의 정확한 bundle과 일치하도록 DB 관계로 강제했다.

### 검증과 교훈

실제 PostgreSQL에서 검토 write와 refresh가 각각 먼저 잠금을 소유하는 양방향 interleaving을 재현했고, 잘못된 source 조합을 삽입하는 음성 테스트를 추가했다. stale 검사는 조회 함수 하나가 아니라 변경 주체들이 공유하는 잠금 프로토콜과 DB 제약을 함께 가져야 승인 근거로 사용할 수 있다.

관련 커밋: `1d06710`

## P1-05 — 사람의 매핑은 품목과 발주 라인의 결합된 선택

### 문제

사람이 `ITEM-A`를 `POL-1`에 매핑했어도 발주 데이터 갱신 후 기존 mapping이 item ID만으로 재해석되면 다른 발주 라인으로 이동할 수 있었다. 발주 라인 ID를 저장한 뒤에도 동일 `POL-1`의 품목이 `ITEM-B`로 교체되는 경우를 검사하지 않으면, payload에 서로 다른 품목이 기록된 채 정상 `MATCHED`가 될 수 있었다.

### 해결

- `AppliedMapping`에 사람이 선택한 `purchaseOrderLineId`를 보존했다.
- 현재 active 발주 라인의 `(itemId, purchaseOrderLineId)`가 저장된 쌍과 모두 일치할 때만 매핑을 적용했다.
- 라인 소멸, 다른 라인으로의 이동, 동일 라인의 품목 교체는 모두 `EVIDENCE_INSUFFICIENT`로 처리하고 진단 정보를 남겼다.
- 변경된 사실에 대해서는 새로운 사람의 매핑 결정이 있어야만 후속 snapshot이 `MATCHED`가 되도록 했다.

### 검증과 교훈

라인 소멸, 같은 품목의 다른 라인 등장, 동일 라인 ID의 품목 교체를 단위·API 통합 테스트로 재현했다. 기존 검토 대상은 stale로 거부되고 새 매핑 후에만 정상 결과가 생성됨을 확인했다. 사람의 결정은 검색 힌트가 아니라 당시 선택한 복합 업무 정체성이므로, 부분 식별자로 다시 추론하면 안 된다.

관련 커밋: `1d06710`, `8490b8c`

## P1-06 — 인증 주체까지 포함해야 하는 멱등성 경계

### 문제

사건 생성의 멱등 key가 `NEW + requestId`만 사용하면, 서로 다른 제출자가 같은 request ID와 payload를 보냈을 때 두 번째 사용자가 첫 번째 사용자의 전체 생성 응답을 재생받을 수 있었다. 이후 사건 조회 권한은 차단되더라도 응답 단계에서 사건 ID와 청구 정보가 이미 노출된다.

### 해결

- 모든 멱등 레코드에 서버가 인증한 actor를 포함하고 `(scope, resource, actor, requestId)`를 고유 경계로 삼았다.
- client가 보내는 `decidedBy`는 무시하고 인증 principal만 결정자와 감사 actor로 사용했다.
- 기존 레코드는 로그인할 수 없는 예약 identity로 backfill했다.
- 권한과 소유권을 controller뿐 아니라 사건 행을 잠근 write transaction 내부에서 다시 검사했다.
- `submitted_by`는 DB trigger로 변경 불가능하게 만들고, 자기 승인 정책은 잠긴 authoritative `InvoiceCase`를 입력으로 받게 했다.

### 검증과 교훈

두 제출자·두 승인자·두 운영자가 같은 request ID를 사용하는 경우, 직접 application service를 호출하는 경우, dual-role 사용자의 자기 승인, 소유자 변경 SQL을 검증했다. 멱등성은 요청 모양만의 속성이 아니라 인증 주체와 권한 영역까지 포함한 보안 경계이며, controller 검사는 트랜잭션 내부의 authoritative authorization을 대체할 수 없다.

관련 커밋: `fc8e22a`

## P1-06 — append-only만으로 감사이력의 신뢰성이 생기지 않음

### 문제

감사 행의 UPDATE와 DELETE를 막아도 최초 INSERT 시 다른 사건의 대상을 넣거나 존재하지 않는 target, 임의 역할, 잘못된 업무 version을 기록할 수 있으면 위조된 이력이 영구 보존된다. 재매핑 이력에 이전 매핑이 없으면 무엇이 바뀌었는지도 복원할 수 없다.

### 해결

- target type별로 실제 대상 존재 여부와 동일 사건 소속을 DB trigger에서 검증했다.
- actor role은 정규화된 허용 역할의 중복 없는 집합으로 제한했다.
- 감사 INSERT 시 사건 version을 검증하고 사건 행에 공유 잠금을 획득했다.
- 업무 변경과 감사 INSERT를 같은 transaction에 두어 감사 실패 시 업무 변경도 rollback되게 했다.
- 재매핑에는 이전 매핑과 이후 매핑을 모두 기록하고, 아직 구현하지 않은 `APPROVE` action은 허용 목록에서 제외했다.

### 검증과 교훈

가짜·교차 사건 target, 잘못된 역할/version/action을 raw SQL로 삽입하는 시도와 감사 저장 실패를 주입한 transaction을 검사했다. 두 connection으로 감사 version 검증과 사건 변경 경합도 재현했다. 감사이력은 삭제 불가능성뿐 아니라 생성 시점의 참조 정합성, 원자성, 의미 있는 before/after가 함께 있어야 신뢰할 수 있다.

관련 커밋: `fc8e22a`, `cbff43a`

## P1-06 — 내부용 공개 메서드도 하나의 보안 API임

### 문제

HTTP endpoint는 권한·멱등성·감사를 적용했지만, 매핑 후 재대사를 위해 공개된 application service 메서드는 Spring 내부 호출만으로 대사 결과를 추가할 수 있었다. 승인자 context에서 이를 반복 호출하면 실제 매핑 결정 없이 결과를 쌓고 최신 검토 snapshot을 stale하게 만들 수 있었다.

### 해결

- 원시 대사 결과 저장 메서드를 private으로 닫았다.
- 외부에서 호출 가능한 대사 쓰기는 OPERATOR 전용 멱등 실행만 남겼다.
- 매핑 내부 재대사는 `review.application`의 package-private 협력자로 옮겨 전체 `recordMapping` transaction에서만 접근되게 했다.
- 내부 재대사 결과는 별도 운영 재처리처럼 기록하지 않고 `ITEM_MAPPED` 감사에 후속 결과·snapshot identity를 포함했다.
- 순수 계산기인 `MatchResultPlanner`는 repository를 갖지 않게 분리했다.

### 검증과 교훈

reflection 기반 경계 테스트와 직접 service 호출 테스트로 공개 저장 경로를 열거했다. 매핑 성공은 결과·snapshot·감사를 함께 만들고, replay나 stale 실패는 아무것도 추가하지 않음을 검증했다. 네트워크에 노출되지 않은 public 메서드도 다른 component와 향후 코드가 호출할 수 있는 보안 API이므로, 호출 관례보다 언어 수준의 접근 제한과 완전한 orchestration 경계가 안전하다.

관련 커밋: `cbff43a`, `7eb675d`

## P1-06 — 감사 payload의 크기와 canonical hash

### 문제

감사 JSON의 64 KiB 제한을 Java 문자 수로 계산하면 한글처럼 UTF-8에서 여러 byte를 쓰는 입력이 제한을 우회한다. 따옴표가 많은 문자열은 JSON escaping 후 크기가 커져 DTO상 유효한 요청이 감사 직렬화 단계에서 500을 만들었다. 또한 Map만 정렬하면 Jackson `ObjectNode`의 삽입 순서에 따라 의미가 같은 payload의 hash가 달라졌다.

### 해결

- 요청 body는 Content-Length와 무관하게 실제 stream을 제한하고 draft 라인 수에도 상한을 뒀다.
- 큰 라인 값은 preview, 원문 길이와 SHA-256으로 요약해 의미 있는 before/after를 제한 안에 유지했다.
- JSON을 새 tree로 복사한 뒤 모든 object key를 재귀 정렬하고 array 순서는 보존했다.
- 동일한 canonical UTF-8 byte를 저장값, 크기 측정, `originalBytes`와 SHA-256 입력에 공통 사용했다.
- 상한을 넘는 요약은 원문 대신 크기와 hash만 가진 결정적 envelope로 저장했다.

### 검증과 교훈

한글, escape 증폭, 정확한 byte 경계, chunked 요청, 100/101 라인, 반대 삽입 순서의 중첩 `ObjectNode`를 테스트했다. caller의 JSON은 변경되지 않고 배열 순서 차이는 유지되는 것도 확인했다. 자원 제한은 논리 문자 수가 아니라 실제 저장·전송 byte를 기준으로 해야 하며, hash 계약은 사용하는 모든 JSON 표현을 canonicalize해야 한다.

관련 커밋: `cbff43a`, `7eb675d`, `488f36d`

## P1-07 — 승인 트랜잭션 안에서 갱신한 외부 snapshot을 REQUIRES_NEW로 읽을 수 없음

### 문제

승인은 외부 구매 snapshot을 트랜잭션 시작 전에 fetch하고 트랜잭션 안에서 적용한 뒤, 검토 스냅샷이 그 snapshot과 같은 version/hash인지 재검증해야 한다. 기존 currentness 검증은 구매 snapshot을 `REQUIRES_NEW` REPEATABLE_READ 트랜잭션으로 읽어, 승인 트랜잭션이 아직 commit하지 않은 적용 결과를 보지 못한다. 외부 version이 바뀐 경우에도 옛 값과 비교해 stale을 놓칠 수 있었다.

### 해결

- 승인 트랜잭션 안에서 방금 적용한 `purchase_order_snapshot` version/hash를 같은 트랜잭션으로 읽고, 그 값을 currentness 검증에 명시적으로 전달하는 overload를 추가했다.
- 외부 fetch는 트랜잭션 밖에서, 적용은 승인 트랜잭션 안에서 수행해 HTTP 호출 중 잠금을 유지하지 않는다.

### 검증과 교훈

외부 version이 승인 전에 바뀌면 `STALE_REVIEW_TARGET(PURCHASING_SNAPSHOT)`로 side effect 없이 실패하고, 동시 refresh는 승인 advisory lock 뒤에서 직렬화되어 version이 섞이지 않음을 실제 PostgreSQL 테스트로 확인했다. 다른 트랜잭션 경계의 read를 재사용할 때는 isolation/propagation이 그 트랜잭션의 미확정 쓰기를 볼 수 있는지 먼저 확인해야 한다.

## P1-07 — 검수 잔량 경합과 결정적 잠금 순서

### 문제

같은 검수 라인의 잔량 60을 두 사건이 40씩 동시에 승인하면 하나만 성공해야 하고, 실패한 쪽은 최신 잔량을 반환해야 한다. 여러 검수 라인을 잠그는 순서가 다르면 deadlock 위험이 있다.

### 해결

- 승인은 검수 라인을 (검수일, 외부 receipt line id, receipt id, UUID) 고정 순서로 잠그고, 잠금 후 `confirmed - sum(allocation)`을 다시 계산한다.
- DB trigger가 대상 검수 라인을 `FOR UPDATE`로 잠그고 초과 배분을 거부해 raw SQL에서도 같은 규칙을 지킨다.
- 승인과 refresh가 같은 구매 advisory lock을 공유해 같은 PO의 승인이 직렬화된다.

### 검증과 교훈

실제 PostgreSQL에서 40+40 경합(한 건 성공, 패자는 현재 confirmed/allocated/remaining 409), 같은 requestId 병렬 승인(효과 1세트 + replay), 다른 PO 다중 라인 동시 승인(deadlock 없음), decision/allocation/payment 단계별 실패 주입(전량 rollback)을 반복 검증했다. 공유 자원 경합은 JVM 락이 아니라 DB 행 잠금과 제약으로 닫아야 한다.

## 앞으로 추가할 때의 형식

새 사례는 아래 항목을 중심으로 짧게 추가한다.

```text
## Ticket — 사례 제목
### 문제
### 위험 또는 원인
### 해결
### 검증과 교훈
관련 커밋: `...`
```

같은 원인의 후속 수정은 새 항목을 무조건 만들지 말고 기존 사례를 갱신한다.
