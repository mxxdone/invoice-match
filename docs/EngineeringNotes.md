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
