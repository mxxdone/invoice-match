# Invoice Match Engineering Notes

이 문서는 포트폴리오와 면접에서 설명할 가치가 있는 문제 해결 사례만 보존한다. 작업 일지나 실패 목록이 아니며, 재현 가능한 위험·원인·해결·검증이 갖춰진 사례만 추가한다.

구현 에이전트의 기본 입력 문서가 아니다. Ticket 인수 후 기록을 갱신할 때만 읽는다.

## 기록 기준

- 업무 정합성, 동시성, 멱등성, 장애 복구, 보안, 성능 또는 운영 안정성에 실제 영향을 주는 문제
- 단순 코드 수정이 아니라 설계 선택과 trade-off를 설명할 수 있는 문제
- 자동 테스트나 재현 절차로 해결을 입증한 문제
- 해결되지 않은 사항은 완료 사례처럼 쓰지 않고 `남은 고려사항`으로 명시

## P4-03 — 사람 확인 저장과 재개 완료를 분리하기

사람 확인을 먼저 저장하고 나중에 재개 메시지를 만들면 중간 장애에서 확인은 소비됐지만 후속 실행은 사라질 수 있다. 반대로 저장 응답을 재개 완료로 표현하면 담당자가 아직 실행되지 않은 결과를 완료된 것으로 해석할 수 있다.

확인 원장·감사·actor-scoped 멱등 응답·resume Outbox를 같은 transaction에 저장하고, 지연 FK로 정확한 확인/Outbox 쌍 없이 commit할 수 없게 했다. 기존 시작 메시지가 resume 구간을 claim하지 못하게 하며, 확인 저장 응답은 예약된 상태로만 반환한다. 저장된 응답 재생은 입력 변경 이후에도 새 실행 권한이나 새 이벤트를 만들지 않는다.

실제 PostgreSQL에서 동시 확인의 단일 승자, orphan 확인과 중복 resume key 거부, 감사 실패 후 전체 rollback, 구버전·후보·원문 변조 거부를 검증했다. 실제 메시지 발행과 SDK 재개는 후속 Ticket의 책임이며 저장 인수와 구분한다.

## P4-02 — SDK 상수보다 실제 저장 계약을 검증하기

pin한 SDK의 checkpoint-base 상수는 legacy schema 2였지만 실제 graph writer는 schema 4를 생성했다. 상수 비교와 격리 codec 검증만으로 Core 저장 호환성을 판단하면 정상 SDK checkpoint가 거부된다. 채널 버전 metadata에도 상태와 같은 필드명이 나타나므로 전체 checkpoint를 재귀 탐색하면 버전 숫자를 단계 참조로 오인할 수 있다.

실제 writer 출력으로 버전 gate를 검증하고, 상태 채널 값에만 불변 단계 참조 검증을 적용했다. 새 실행은 schema 4만 허용하며 기존 schema 2 기록은 수정하지 않고 자동 복원을 거부한다. root task path도 실제 SDK가 쓰는 유한 목록으로 제한했다. 허용 범위를 넓혀 fallback하는 대신 명시적 계약으로 저장과 복원을 연결한 선택이다.

설치된 Linux wheel이 실제 Core·PostgreSQL에 checkpoint와 interrupt를 저장하도록 검증하고, Core 대기 응답 실패 후 새 프로세스에서 성공 모델 단계 재호출 없이 복원했다. 기존 schema 2 및 v1 기록 보존도 실제 migration으로 확인했다. SDK 버전 이름이나 격리 mock 통과보다 실제 writer와 저장 경계의 왕복이 호환성의 근거다.

## P4-01 — 잠금 대기 후 실행 권한을 다시 확인하기

실행 token과 lease를 조회한 뒤 구매·정책 scope 잠금을 기다리면, 처음 조회한 lease 유효성이 잠금 획득 시점에는 이미 사라질 수 있다. 처음의 상태 값만 믿는 checkpoint 읽기는 만료된 worker에 저장 내용을 내줄 수 있다.

사건·graph·scope 잠금 순서를 유지하면서 currentness를 확인한 뒤 DB의 현재 시각으로 token 소유권을 다시 검사했다. 저장은 DB admission guard에서도 실행 token과 만료를 검증하고, 사람 대기는 정확한 불변 checkpoint/write 참조 저장과 lease 해제를 한 transaction으로 묶었다. 저장 응답 유실 replay와 신규 저장의 실행 권한은 별도로 확인한다.

실제 PostgreSQL에서 scope 잠금을 선점해 checkpoint 읽기를 막고, 실제 lease 만료 후 잠금을 풀었을 때 읽기가 거부되는 것을 검증했다. 대기 전환 중 DB 오류를 주입해 interrupt 삽입까지 rollback되고 기존 checkpoint와 lease가 보존되는 것도 확인했다. 동시성 검증에서는 작업 시작 당시의 권한뿐 아니라 마지막 잠금 이후의 권한과 transaction 효과를 함께 확인해야 한다.

## P4-00 — interrupt 복원과 닫힌 JSON 경계

사람 대기를 JSON으로 저장한다는 것만으로 SDK checkpoint를 복원할 수 있지는 않다. LangGraph는 interrupt를 SDK DTO와 tuple로 pending writes에 저장하고, 재개할 때 interrupt 노드를 처음부터 실행한다. 기본 serializer에 모든 객체를 맡기면 복원 범위가 넓어지고, interrupt 노드에서 모델 호출이나 예산 예약을 수행하면 재개 시 중복 효과가 생긴다.

pin한 SDK에서 JSON 값·tuple·Interrupt만 복원하는 닫힌 codec을 사용하고, 성공 단계와 예약을 interrupt 노드 밖의 불변 Core 참조로 분리했다. 실제 새 프로세스에서 interrupt ID를 보존해 재개했으며 노드 재실행에도 성공 모델 단계와 예약이 반복되지 않았다. reserved pending-write slot을 갱신하는 SDK 계약은 Core의 새 불변 version으로 표현하기로 했다. Core의 lease fencing·원자적 대기 확정·versioned 쓰기 검증은 후속 Ticket에서 구현하며, 이 SDK 검증만으로 메시지 ACK나 DB 내구성이 완성됐다고 보지 않는다.

## P2-04 — 파서 입력 크기보다 중요한 실행 자원 경계

PDF/OOXML은 작은 입력에서도 큰 압축 해제와 SDK 할당을 유발할 수 있다. library의 파일 크기 검사만으로 wall time·메모리를 보장하지 않고, Linux child에서 SDK import 전에 RLIMIT_AS를 적용하며 parent가 입력 전달과 출력 수집을 동시에 제한하도록 했다. Windows library 검증과 실제 Linux OS 검증을 구분했다.

Head 검수에서는 DTD 기본 허용, 잘못된 workbook의 빈 성공, 음수 shared-string 인덱스를 직접 재현하고 거부하도록 수정했다. CLI의 격리 전 ZIP 접근도 제거했다. child가 먼저 종료되면 `getpgid(childPid)`로 후손을 찾을 수 없고, 그 상태에서 buffered pipe를 닫으면 reader lock에서 멈출 수 있었다. 생성 시 정해진 process group id로 후손을 종료한 뒤 I/O thread를 회수하는 순서로 바꾸었다.

실제 Linux에서 parser 89건, timeout·입력 pipe stall·512MiB memory·출력 한도·종료된 leader의 후손 정리·다음 정상 요청과 설치 CLI를 검증했다. 검증 runner도 image 준비부터 단일 deadline을 적용하고 별도 짧은 cleanup 예산으로 본인 client/container만 회수한다. 바인드 마운트의 venv/캐시 권한과 느린 import를 피해 제한된 RAM runtime을 사용하고 다운로드 cache·로그는 D에 둔다. 8초 의도적 timeout은 cleanup 포함 14.3초에 비정상 종료했다. 이 검증은 원격 CI 결과나 P2-03의 실제 인쇄 확인을 대신하지 않는다.

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

관련 커밋: `69dde46`, `5bdb6e0`, `89214db`, `d4fec02`, `b865376`, `c3648f3`

## P1-07 — 검수 잔량 경합과 결정적 잠금 순서

### 문제

같은 검수 라인의 잔량 60을 두 사건이 40씩 동시에 승인하면 하나만 성공해야 하고, 실패한 쪽은 최신 잔량을 반환해야 한다. 여러 검수 라인을 잠그는 순서가 다르면 deadlock 위험이 있다.

### 해결

- 승인은 검수 라인을 (검수일, 외부 receipt line id, receipt id, UUID) 고정 순서로 잠그고, 잠금 후 `confirmed - sum(allocation)`을 다시 계산한다.
- DB trigger는 검수 라인 잠금과 검증/잔량을 하나의 BEFORE INSERT 가드로 합치고, 케이스 `FOR UPDATE` → 동일 PO advisory lock → 검수 라인 `FOR UPDATE` → 검증/잔량 순서로만 잠근다. 알파벳순으로 따로 실행되던 validate/balance 트리거를 없애 raw SQL insert가 검수 라인을 먼저 잡고 케이스 FK를 기다리다 승인과 교착하는 경로를 제거했다.
- 승인과 refresh가 같은 구매 advisory lock을 공유해 같은 PO의 승인이 직렬화된다.

### 검증과 교훈

실제 PostgreSQL에서 40+40 경합(한 건 성공, 패자는 현재 confirmed/allocated/remaining 409), 같은 requestId 병렬 승인(효과 1세트 + replay), 다른 PO 다중 라인 동시 승인(deadlock 없음), decision/allocation/payment/audit/idempotency 단계별 실패 주입(전량 rollback)을 반복 검증했다. 케이스 락을 쥔 두 번째 커넥션이 같은 검수 라인을 잠그려는 raw insert와 역순으로 맞서는 재현 테스트와, 같은 PO의 두 raw writer가 두 검수 라인을 반대 순서로 삽입하는 다중 행 테스트에서도 `ERROR: deadlock detected` 없이 advisory lock 뒤에서 직렬화됨을 확인했다. 공유 자원 경합은 JVM 락이 아니라 DB 행 잠금과 제약으로 닫아야 한다.

관련 커밋: `69dde46`, `5bdb6e0`, `89214db`, `d4fec02`, `b865376`, `c3648f3`

## P1-07 — 저장 JSON을 신뢰하지 않는 승인 대상 독립 재구성과 관계 봉인

### 문제

초기 P1-07 구현은 승인 트랜잭션에서 저장된 `review_snapshot`/`match_result`/`evidence_bundle`의 payload JSON을 파싱해 배분 계획과 금액을 읽었다. payload가 append-only라도 raw SQL로 다른 해시를 가진 행을 넣거나(jsonb는 key 순서를 보존하지 않아 재현성도 약함) FK가 표현하지 못하는 관계(승인 결정 종류, 동결 draft 라인, external receipt id/version, active 여부)는 위조될 수 있었다. 또한 직접 서비스 호출이 기존 트랜잭션 안에서 이뤄지면 외부 HTTP가 호출자 잠금을 쥔 채 실행될 수 있었다.

### 해결

- 승인 트랜잭션 안에서 권위 있는 관계형 사실(케이스 헤더 + 봉인된 draft 라인)로 evidence canonical payload/hash를 다시 만들고, 현재 구매 사실과 유효 매핑으로 `MatchEngine`을 재실행해 match payload/hash와 source 컬럼을, 다시 `ReviewSnapshotPayloadBuilder`로 snapshot payload/hash를 재구성해 저장값과 비교한다.
- 배분 계획과 금액은 재계산된 typed match 결과에서만 유도하고, JSON `asInt/asLong` 강제 파싱을 제거했다(checked `Money`/`Quantity`).
- `receipt_allocation`에 승인 결정 종류·동결 draft 라인·external receipt id/version/active를 단일 BEFORE INSERT 트리거로 강제하되, 잠금을 케이스 `FOR UPDATE` → PO advisory → 검수 라인 `FOR UPDATE` → 검증/잔량 순서로만 잡아 application/raw SQL이 같은 순서를 따르게 했다. `(decision, invoice line, receipt line)` 유일성과 검수 라인 UPDATE 가드(할당 합 이하로 confirmed 감소·할당 있는 라인 비활성 금지, 증가/버전 진화 허용), 승인 시점 confirmed quantity 저장(`confirmed_quantity_at_approval`)을 추가했다.
- `payment_request`는 승인 결정의 `(case, snapshot, hash, approved amount/currency)` 복합 FK로 금액·주체를 고정하고, 결정적 외부 key CHECK + UPDATE/DELETE 보호 트리거를 추가했다.
- APPROVED `review_decision`에 approval audit context를 immutable 컬럼(`approval_actor_roles`/`approval_request_id`/`approval_trace_id`)으로 저장한다. actor roles는 서버가 인증한 principal의 canonical 역할, request id는 actor-scoped command `requestId`(idempotency 유일성 입력, 클라이언트 제공), trace id는 `X-Trace-Id`를 P1-06 경계에서 검증해 전파한 correlation metadata(없거나 형식이 무효면 `trc-` 생성)에서 같은 트랜잭션으로 채운다. trace id는 인증·인가·idempotency 유일성·승인 identity가 아니며 decision↔audit 상관관계에만 쓰인다. `ck_review_decision_approval_metadata`는 APPROVED의 nonblank canonical roles/request/trace를 강제하되 NOT VALID로 legacy V6 APPROVED 행을 보존한다.
- V6 감사 검증 함수를 V7에서 교체(V6 파일 불변)해 APPROVE가 저장된 approval actor roles/request id/trace id와 audit `actor_roles`/`request_id`/`trace_id`, 그리고 `actor == decided_by`가 정확히 일치할 것을 요구한다. 이어 before_state/after_state를 권위 있는 결정·payment_request·allocation 행에서 재구성한 정확한 JSONB 객체와 whole-object로 비교해 모든 값과 JSON 타입, 정확한 field set(위조 extra·누락 금지), 결정적으로 정렬된 allocation 배열까지 강제하고 object key 순서만 무시한다.
- 승인 집계는 int 합/축소 대신 checked `long`(`Math.addExact`)으로 계산하고 overflow를 안정된 409/도메인 오류로 변환하며, shortfall의 confirmed/allocated/remaining/requested도 `long`으로 보고한다.
- `ApprovalApplicationService`는 `Propagation.NEVER`로 활성 호출자 트랜잭션이 있으면 메서드 본문 전에 즉시 거부한다(중단 후 계속하지 않음). 외부 조회는 트랜잭션 없는 호출에서만 실행된다.

### 검증과 교훈

위조 evidence/match/snapshot payload·hash, 임의 receipt date/ID, 잘못된 타입, overflow, REJECTED 결정, 교차 사건, 중복 배분, 비활성 검수, 임의 지급 금액/key, 보호 필드 UPDATE/DELETE를 raw SQL로 재현해 모두 side effect 없이 실패함을 확인했다. APPROVE 감사는 저장 metadata와 어긋나는 actor roles(부풀린/비정규 순서/미지원 역할/빈 값), 임의의 nonblank request id, 임의 trace id, 누락된 request/trace, actor 불일치, 각 숫자 필드의 문자열·boolean·null 치환, extra/missing field를 모두 거부하고, 권위 있는 행과 저장 metadata로 재구성한 audit만 수락함을 확인했다. 다른 계층이 소유한 직렬화 경계(jsonb key 재정렬)를 넘겨 값을 재사용하면 해시 재현성이 깨질 수 있으므로, 승인 같은 고위험 판단은 저장 JSON이 아니라 권위 있는 관계형 사실에서 다시 계산하고 DB 제약으로 관계를 봉인해야 한다.

관련 커밋: `69dde46`, `6077d3d`, `5bdb6e0`, `89214db`, `d4fec02`, `b865376`, `c3648f3`

## P1-08 — 전달 유실/중복과 RESULT_UNKNOWN을 구분하는 최소 Outbox 릴레이

### 문제

승인 트랜잭션이 `PaymentRequest`를 commit한 직후 relay가 멈추거나 worker가 죽으면 ERP 인계 요청이 영영 사라질 수 있다. 반대로 HTTP timeout이나 5xx 후 무조건 재전송하면 ERP가 이미 처리했을 수 있는 요청이 중복될 수 있고, 상태를 `FAILED`로 단정하면 실제 처리 여부를 잘못 기록하게 된다. 또한 `PaymentRequest`/payload/key를 raw SQL로 다른 값으로 바꿔 인계하면 감사 근거가 깨진다.

### 해결

- 외부 HTTP 호출 전에 승인 트랜잭션 안에서 `PaymentRequest`와 `PaymentRequestExportRequested` Outbox 행을 함께 저장하고, outbox insert 실패 시 배분·결정·지급·감사·사건 상태가 전부 rollback되게 했다.
- Java와 PostgreSQL이 byte 단위로 동일한 canonical payload 텍스트와 SHA-256을 생성하고, V8 insert/update trigger가 payload·hash·idempotency key·export version을 `payment_request`에 묶어 다른 지급·금액·주체로의 치환을 거부한다. 문자열 값은 PostgreSQL `to_jsonb` typed escaping과 동일한 규칙으로 Java에서 escape하므로 quote·backslash·CR/LF/control·Unicode가 양쪽에서 같은 bytes가 된다.
- Outbox 상태를 `READY → CLAIMED → SENDING → {DELIVERED | FAILED | RESULT_UNKNOWN}`으로 분리하고, `payment_request` 상태 `NOT_SENT → SENDING → {ACKNOWLEDGED | RETRY_SCHEDULED | FAILED | RESULT_UNKNOWN}`도 DB check/trigger로 제한했다.
- claim은 `FOR UPDATE SKIP LOCKED`와 opaque claim token + worker id + lease deadline을 사용하는 짧은 트랜잭션으로만 수행하고, HTTP 중에는 DB lock을 유지하지 않는다. 모든 전이는 claim token compare-and-set으로 수행해 stale worker가 현재 상태를 덮지 못한다. 만료 회수는 payment → outbox 순서로만 잠그고(식별은 비잠금 조회, 잠금 후 동일 token/status/expiry 재검증) 최종화(case → payment → outbox)와 같은 순서를 공유해 교착을 없앴다.
- `CLAIMED` 만료는 안전하게 `READY`로 복구하지만, HTTP 시작 직전 commit한 `SENDING` 만료는 `RESULT_UNKNOWN`으로 종결해 자동 재전송하지 않는다. timeout·reset·5xx도 `RESULT_UNKNOWN`으로 처리하고, 명시적 429만 같은 key/payload로 bounded backoff 재시도한다. 2xx는 outbox `DELIVERED`·지급 `ACKNOWLEDGED`·사건 `EXPORTED`를 한 트랜잭션으로 확정한다.
- relay scheduler는 fail-closed로 기본 disabled이며, Compose도 명시적 opt-in 없이는 켜지지 않는다. mock-erp가 health-only인 P1-09 이전에는 활성화하지 않는다(활성화 시 승인이 확정 404 `FAILED`가 되므로). HTTP adapter는 JDK `HttpClient`로 바꿔 body를 버리면서도 `sendAsync(...).get(totalDeadline)`으로 connect·header·body를 포함한 전체 요청 완료를 하나의 deadline으로 제한한다. sending lease는 개별 timeout이 아니라 `request-timeout + positive safety-margin`보다 길어야 하며 위반 시 기동이 실패한다. `runOnce`는 `@Transactional(propagation = NEVER)`로 ambient 호출자 트랜잭션을 HTTP/DB 효과 전에 거부한다(프록시 경계는 scheduler 직접 호출에도 적용).
- lease deadline 생성·만료 비교와 attempt `occurred_at`은 JVM Clock이 아니라 PostgreSQL `clock_timestamp()`를 단일 기준으로 사용한다. 여러 node의 clock offset이 회수 판단에 영향을 주지 못하며, 테스트용 만료는 lease 행을 명시적으로 만료시킨다. 사건 `updated_at`만 P1-07과 일관되게 애플리케이션 clock을 쓴다.
- 각 시도는 append-only `outbox_delivery_attempt`의 bounded code와 HTTP status로만 남긴다. `BEFORE INSERT` guard가 attempt를 실제 전이 증거로 강제한다: `SENDING`은 event가 `CLAIMED`이고 token/worker가 일치하며 `attempt_number = attempt_count + 1`일 때만, terminal/retry는 event가 `SENDING`이고 같은 attempt의 `SENDING` evidence가 있으며 HTTP status가 outcome 계약(`ACKNOWLEDGED`=2xx, `RETRY_SCHEDULED`=429, `FAILED`=비재시도 4xx 또는 소진된 429, `RESULT_UNKNOWN`=null/3xx/5xx, `LEASE_EXPIRED`=null)과 맞을 때만 허용한다. 애플리케이션은 evidence를 먼저 쓰고 같은 트랜잭션에서 상태를 바꾸며, 지연 constraint trigger가 outbox UPDATE 방향과 attempt INSERT 방향을 모두 검증한다. 즉 attempt 행 자체도 commit 최종 event/payment/case tuple과 정확히 일치해야 하고, attempt당 terminal/retry outcome은 partial unique로 최대 1개다(SENDING 1개 + terminal 1개는 허용). standalone evidence는 어떤 상태 전이도 만들지 못한다.
- commit 시점 지연 guard가 사건의 모든 payment에 대해 `(payment, outbox, case)` tuple을 양방향으로 검증한다. 허용 조합은 `NOT_SENT`/`RETRY_SCHEDULED ↔ READY|CLAIMED`, `SENDING ↔ SENDING`, `ACKNOWLEDGED ↔ DELIVERED ↔ EXPORTED`, `FAILED ↔ FAILED`, `RESULT_UNKNOWN ↔ RESULT_UNKNOWN`이고, 성공이 아닌 모든 상태는 사건을 `EXPORT_PENDING`으로 강제한다. 같은 상태에서 claim identity(worker/token/attempt)를 바꾸는 raw UPDATE도 거부한다.

### 검증과 교훈

실제 PostgreSQL과 HTTP stub으로 전체 허용 tuple의 positive commit과 `ACK/READY`, `FAILED/READY`, `RESULT_UNKNOWN/SENDING`, `EXPORTED`-only 같은 mismatch의 commit-time 거부를 고정했다. attempt protocol은 jump·mixed token·standalone SENDING/terminal commit 거부·terminal-without-SENDING·duplicate·conflicting terminal·wrong HTTP·attempt_count 불일치를, 상태는 payload/hash/key/version/status/lease 위조·DELETE·token 탈취를 거부함을 확인했다. HTTP는 2xx/4xx/429/5xx/timeout에 더해 301/302/307이 rollback 없이 `RESULT_UNKNOWN`으로 저장되고 batch를 중단하지 않음을, header 즉시 응답 후 body를 trickle하는 응답이 전체 deadline으로 중단됨을, lease는 직전/직후 회수와 두 node clock offset(DB time 사용으로 무관)을 검증했다. ambient 트랜잭션 안에서 bean `runOnce`를 호출하면 `IllegalTransactionStateException`으로 ERP 호출·상태 변경 전에 거부됨도 확인했다. claim 경합(`SKIP LOCKED`로 각 event 1회 선점), stale token 거부, 4개 crash window와 `RESULT_UNKNOWN` 영구 비재전송, 회수/최종화 lock 순서(2-connection barrier, 교착 없음), V7 다건 `PENDING` backfill과 quote/backslash/CR/control/Unicode parity도 확인했다. 명시적 429 경로만 같은 key로 재전송되는 at-least-once이며 단일 업무 결과 보장(정확히 한 번)을 주장하지 않는다. 처리 여부를 알 수 없는 결과를 실패로 단정하지 않는 것이 중복 지급을 막는 핵심이며, 그 판단 근거는 애플리케이션 코드가 아니라 DB 상태 전이·attempt evidence·canonical payload binding으로 고정해야 raw SQL에 무너지지 않는다.

관련 커밋: `9ac57df`, `bb74078`, `d07c426`, `a42a901`, `479504e`

## P1-08 — 불변 이력 위에 상태 제약을 얹는 migration 순서

### 문제

V7까지 `payment_request.status`는 `PENDING`만 허용했고 V7의 변경 보호 trigger가 상태 전이를 검사한다. V8에서 새 status check를 먼저 추가하면 아직 `PENDING`인 기존 행 때문에 migration이 실패하고, 보호 trigger가 legacy 전이를 모르면 backfill UPDATE 자체가 거부된다.

### 해결

- 기존 status check를 먼저 drop하고, 보호 trigger 함수가 legacy `PENDING → NOT_SENT` 전이를 허용하도록 교체한 뒤 backfill UPDATE를 수행하고, 그 다음에 확장된 check를 추가했다.
- V7까지만 적용한 별도 데이터베이스에 승인 완료 지급요청 2건을 넣고 V8로 올리는 upgrade test에서 `PENDING → NOT_SENT` 마이그레이션과 결정적 outbox backfill을 검증했다.

### 검증과 교훈

제약과 trigger가 이미 존재하는 불변 이력에 새 상태 모델을 도입할 때는 drop → legacy 전이 허용 → backfill → 새 제약 순서를 지켜야 한다. 새 설치 테스트만으로는 운영 upgrade를 보장하지 못하므로 `N-1 → N` 데이터 포함 migration test가 필요하다. V8은 아직 미병합이므로 전체 상태 행렬, attempt evidence guard, canonical escaping, DB time 같은 후속 수정을 같은 migration에서 마무리했다.

관련 커밋: `9ac57df`, `bb74078`, `d07c426`, `a42a901`, `479504e`

## P1-09 — 외부 결과를 기존 relay 상태 기계에 멱등하게 수렴시키기

### 문제

relay가 2xx를 관측하지 못하면 P1-08은 지급요청을 `RESULT_UNKNOWN`으로 종결하고 자동 재전송하지 않는다. 그런데 mock ERP는 이미 처리했을 수 있어, 후속 webhook이나 동일 key 상태 조회로 `ACKNOWLEDGED`로 수렴시켜야 한다. 하지만 P1-08의 상태 행렬·attempt ledger·tuple guard는 relay worker만을 전제로 해서, webhook이 outbox를 직접 전이하면 그 전이를 뒷받침할 증거가 없다. 동시에 중복·위조·역순 webhook과 응답 유실이 업무 효과를 두 번 만들면 안 된다.

### 해결

- V9에서 append-only `payment_result_event`를 도입했다. `(provider, external_event_id)` 유일, 수신 body의 SHA-256, 외부 지급 key를 outbox idempotency key에 묶은 FK를 저장하고, 지연 commit guard가 결과 outcome이 최종 `(payment, outbox, case)` tuple과 정확히 일치할 때만 commit을 허용한다. 즉 webhook 결과 자체가 전이 증거이며 raw SQL로도 standalone 위조 결과는 commit되지 않는다.
- P1-08 행렬에는 `RESULT_UNKNOWN -> ACKNOWLEDGED | FAILED`(payment·outbox 동일)만 추가하고, `check_outbox_attempt_evidence`를 교체해 result event가 있으면 그 전이를 증거로 인정한다. relay 경로는 여전히 attempt ledger 증거를 요구한다. 성공만 `EXPORTED`로 가고 `FAILED`와 `RESULT_UNKNOWN`은 구분된다.
- 수신은 `sha256=<HMAC(secret, "<timestamp>.<raw body>")>`를 timing-safe로 검증하고 timestamp tolerance로 재생을 제한하며 secret 미설정 시 fail-closed다. `(provider, externalEventId)` advisory lock으로 같은 event 중복을 직렬화한 뒤 outbox를 canonical 순서(case → payment → outbox)로 다시 잠그고 재검증한다. 같은 event 다른 payload·key 불일치·역순/downgrade는 side effect 없이 409, unknown key는 404, 위조/누락/만료 서명은 401이다.
- webhook 적용은 relay finalize와 같은 조건부 update를 공유해, 동시에 도착한 relay finalize나 webhook과 경합해도 정확히 하나만 commit되고 나머지는 no-op이 된다. 새 PaymentRequest나 새 export key를 만들지 않는다.
- mock-erp는 같은 key+같은 payload replay·다른 payload conflict·key 기반 상태 조회·응답 유실 후 동일 key 수렴·서명 webhook을 구현했다. relay 기본값은 계속 fail-closed이고, idempotent receiver가 준비된 local/compose에서만 명시적으로 켠다.

### 검증과 교훈

실제 PostgreSQL + MockMvc로 응답 유실(`RESULT_UNKNOWN`) 후 ACK/FAILED webhook 수렴, 중복 replay, 동일 event 다른 payload conflict, 병렬 중복 1회 효과, 위조/누락/만료 서명 거부, unknown key·payment key mismatch·역순/downgrade 거부와 zero side effect, standalone 위조 result event commit 거부, in-flight SENDING과의 경합에서 relay no-op을 고정했다. mock-erp 7개 테스트와 core-api 전체 496개 테스트(relay 상태 행렬·attempt ledger·`Propagation.NEVER`·lease/deadline 회귀 포함)가 통과한다. 알 수 없는 외부 결과를 "실패"로 단정하지 않고 별도 권위 증거로 모델링한 뒤 기존 relay 상태 기계에는 최소 전이만 추가하는 것이 중복 지급과 유실을 동시에 막는 핵심이었다.

관련 커밋: `1bf9273`

## P1-10 — 요청 취소만으로 해결되지 않는 화면 재진입 경합

### 문제와 원인

이전 세션의 늦은 401 응답이 새 세션을 로그아웃시키거나, 감사 더보기 중 A→B→A로 이동하면 이전 추가 페이지·로딩·오류가 되살아났다. AbortController만으로는 이미 완료된 본문의 후속 처리를 막지 못한다. 요청 토큰을 무효화해도 같은 화면 키로 돌아오면 저장된 pagination 상태가 재사용되어 기록 누락과 더보기 정체가 발생했다.

### 해결

로그인 완료 직전에 취소 신호와 세대를 함께 확인하고, 목록·상세의 늦은 응답은 세션과 요청 세대로 차단했다. 상세는 단조 증가하는 조회 run identity에 첫 페이지와 감사 추가 페이지·cursor·로딩·오류를 함께 귀속시켜 같은 ID로 재진입해도 과거 조회 상태를 표시하지 않는다. 조회 실패·권한 거부는 업무상 빈 자료와 구분하고 재시도를 제공한다.

### 검증과 교훈

실제 React DOM에서 늦은 401 완료 경합, 추가 페이지 요청 중 이동, 완료된 추가 페이지 후 재진입, 이전 오류 후 재진입을 재현했다. 독립 리뷰는 StrictMode와 중단된 렌더도 확인했다. 요청 취소, 응답 적용 자격, 저장 상태의 수명은 각각 보호해야 한다. 브라우저에서는 실제 API 자료의 탭 표시와 목록 검색 조건 왕복을 확인했으며, pagination 재진입 경합의 증거는 React DOM 테스트로 한정한다.

관련 커밋: `04df7bd`, `2868ef1`, `e3c394d`, `0f52a2c`, `57e7516`

## P1-10 — 최신 조회 결과와 최신 제출자료를 혼동하지 않기

### 문제와 해결

보완 후 증빙 v2를 제출해도 재비교 전의 최신 비교 결과는 v1을 대상으로 한다. 이를 단순히 정상으로 표시하면 현재 자료의 판정으로 오인할 수 있다. 서버 판정을 다시 계산하지 않고 결과의 입력 증빙 버전·해시, 현재 증빙, 서버의 현재 자료 일치 여부를 분리해 표시했다. 오래된 결과는 당시 자료의 판정으로 명시한다.

### 검증과 교훈

격리된 실제 백엔드에서 v1 비교→보완→v2 제출을 만들고, HTTP 출처와 브라우저의 경고·비교값·탭을 함께 확인했다. 최초 이미지는 자료 준비 전 로딩 화면을 캡처했으므로 시각 증거에서 제외하고, 비교표와 필요한 문구가 표시된 조건을 기다린 뒤 다시 캡처했다. API 성공, 화면 assertion, 준비된 화면 이미지는 서로 다른 증거다. 큰 정수는 정확하게 표시할 수 없는 경우 지원 범위 초과로 안내하며 임의 반올림 값을 정확한 금액처럼 표시하지 않는다.

관련 커밋: `0f52a2c`, `57e7516`

## P1-10 — 화면이 보여준 근거와 승인 대상 결합

### 문제와 해결

비교 결과 A와 최신 검토 대상 B를 서로 다른 조회 시점에 받으면, 검토 대상의 현재 자료 일치 여부만으로는 화면에 표시한 A가 아닌 B를 승인할 수 있다. 표시한 증빙·비교 결과·구매 스냅샷의 식별자, 버전, 해시와 매핑 watermark를 동결 검토 대상의 관계형 필드 및 payload 근거와 대조했다. 필수 근거가 없거나 서로 다르면 결정 버튼을 차단한다. UI는 해시나 서버 판정을 재계산하지 않으며 실제 승인 권위는 기존 서버 검증에 둔다. 409의 변경 원인과 검수 부족량은 transport에서 화면까지 구조를 보존하고 자동 승인 재시도는 하지 않는다.

### 검증과 교훈

이전 비교/새 검토 대상 조합, 누락 해시와 동결 payload 불일치의 합성 사례를 테스트하고 독립 리뷰로 차단을 확인했다. 최신인 개별 응답을 모았다는 사실은 하나의 일관된 업무 대상을 보여줬다는 증거가 아니다.

관련 커밋: `5d63c04`, `fa93821`

## P1-10 — 결과 미확정 쓰기의 재시도와 화면 수명

### 문제와 해결

쓰기 응답 유실 후 입력을 바꾸거나 저장부터 다시 시작하면 새 requestId로 같은 업무를 재실행할 수 있다. 결과가 미확정인 동안 operation·직렬화 payload·requestId·expectedCaseVersion을 동결하고 다른 작업과 입력 변경을 막았다. 단순 GET 성공은 처리 결과 증명이 아니므로 동결 intent를 해제하지 않는다. 정확한 재요청 성공 후에만 원래 작업의 완료 상태를 적용한다. 보완 작성 시작의 완료 상태도 hook이 소유하여 일반 성공과 replay 성공이 같은 다음 저장 경로를 사용하도록 했다.

세션·청구서·화면 실행 세대로 ref/state뿐 아니라 완료 callback, 페이지 재조회, 늦은 401의 로그아웃까지 보호한다. StrictMode의 effect setup/cleanup은 대칭으로 만들었다. 실행 중과 결과 미확정을 분리해 미확정 상태에서 재시도 버튼이 다시 막히는 문제도 제거했다.

### 검증과 교훈

워커 최종 테스트 171개, lint/typecheck/build가 통과했다. 마지막 독립 실제 페이지 공격 6건과 composer 집중 11건은 draft 503 후 변경 없는 GET 및 다른 버전 GET에도 intent가 유지됨, 동일 요청 replay 후에만 해소됨, revision replay 후 version 1의 draft PUT과 제출/상세 이동, 세션·대상 변경의 늦은 성공/401 차단을 확인했다. 앞선 독립 검수의 proxy deadline, 표시 근거, Structured 409, StrictMode 회귀도 인수했다.

실제 Chromium은 정상 승인, 매핑, 보완 v2, 거절, 자기 승인 403, 오래된 검토 대상 차단, 목록 복귀와 뷰포트를 검증했다. 브라우저 unknown-retry는 upstream 전 취소 후 동일 ID 복구 사례이며 반영 후 응답 유실 증거로 확대하지 않는다. 후자의 서버 멱등 replay는 격리 HTTP 검증 근거다. 인계 화면 브라우저 근거는 NOT_SENT 지급요청과 송금의 구분이며 실제 ACK/DELIVERED 시연을 주장하지 않는다. 전체 Phase 1의 경합·ERP 장애 통합 시연은 P1-11에서 수행한다.

hook 단위 테스트만으로 실제 페이지의 상태 소유권과 버튼 복구를 입증할 수 없다. 재조회는 관측이고 재요청은 같은 업무 intent의 복구라는 구분을 UI까지 유지해야 한다.

관련 커밋: `5d63c04`, `fa93821`, `003ff43`

## P1-10 — 업로드까지 포함하는 유한 프록시 deadline

### 문제와 해결

upstream fetch에만 timeout을 걸면 아직 끝나지 않은 요청 body 읽기나 cancel 대기가 프록시를 무한 정체시킬 수 있다. 업로드 읽기·취소, upstream 호출, 응답 소비를 같은 deadline과 취소 신호로 제한하고 실제 byte 기준 256 KiB 한도를 적용했다. timeout과 취소는 각각 504/503으로 종료하며 모든 반환 경로는 no-store다.

### 검증과 교훈

독립 검수는 끝나지 않는 업로드가 기본 10초 후 504, 끝나지 않는 cancel에서도 초과 body가 413, client abort가 503으로 끝남을 재현했다. 검증 프로세스는 실시간 로그와 유한 timeout을 사용하며 생성한 컨테이너 ID와 프로세스만 정리한다. 사용자 preview 4176은 보존했다. 요청 취소와 자원 정리도 정상 흐름 밖의 명시적 계약이어야 한다.

관련 커밋: `5d63c04`

## P1-11 — jsonb key 순서로 깨지는 검토 snapshot hash와 schema version 분기

### 문제

매핑으로 만든 successor `ReviewSnapshot`을 재-freeze 없이 바로 승인하면 `409 REVIEW_STATE_CONFLICT`("the review snapshot payload does not match its authoritative sources")가 발생했고, 같은 대상을 재-freeze하면 승인됐다. 같은 semantics인데 두 snapshot의 hash가 달라지는 불일치였다.

### 원인

`ReviewSnapshotPayloadBuilder`가 중첩 match payload를 raw JSON 문자열로 삽입하고 그 key 순서를 직렬화에 그대로 반영했다. `match_result.payload`는 jsonb라 PostgreSQL이 key를 재정렬한다. 매핑은 같은 트랜잭션의 in-memory match payload(발생 순서)로, 승인 verifier와 재-freeze는 jsonb에서 재로드한 key 순서로 snapshot을 재구성해 hash가 갈렸다. match 결과 검증은 key 순서를 무시하는 비교라 통과해 문제가 드러나지 않았다.

### 해결

- 새 snapshot은 canonical `review-snapshot-v2`로 직렬화·hash한다: 모든 객체 key를 재귀 정렬하고 배열 순서는 보존한다.
- 이미 발급된 `review-snapshot-v1`은 재작성하지 않는다. 검증은 저장된 `schemaVersion`이 지정한 **단일** 알고리즘(v1 legacy 삽입 순서 / v2 재귀 정렬)으로만 수행하고 서로 fallback하지 않는다. missing/unknown version은 fail-closed이며 v2 실패를 v1로 재검증하지 않는다.
- 스키마·API·DTO·DB migration 변경 없이 verifier의 hash·semantic·관계 검증은 유지했다. 부정합 v1 snapshot은 `409` 후 v2로 재-freeze한다.

### 검증과 교훈

실제 PostgreSQL에서 매핑 successor 직접 승인(추가 freeze 없음) 200, 유효한 legacy v1 승인 200, v2·v1·unknown version 위조는 409와 zero side effect임을 확인했다. 단위 테스트로 중첩 반대 key 순서의 hash 동일, 배열 순서 차이의 hash 상이, unknown version 거부를 고정했다. 다른 계층의 직렬화 순서에 의존해 저장 payload를 재사용하면 hash 재현성이 깨지므로, 불변 payload는 알고리즘을 schema version으로 명시·분기하고 의미가 같은 입력은 표현 순서와 무관하게 같은 hash가 되도록 canonical화해야 한다.

관련 커밋: `c1e14d6`

## P1-11 — 검증 하네스의 false PASS와 정리·환경 격리

### 문제

P1-11 하네스가 (a) 매핑 successor 직접 승인 실패를 재-freeze로 우회하고 `ALL CHECKS PASSED`를 출력했으며, (b) Compose smoke의 `down` 비정상/throw가 WARN에 그쳐 workflow가 성공이면 exit 0이 됐다. env 파일 고정 경로, 호스트 publish `0.0.0.0`, caller의 `POSTGRES_*`/`COMPOSE_*` 상속 위험도 있었다.

### 원인

검증 하네스가 실패 원인을 기록만 하고 run 실패로 전파하지 않는 경로(우회 fallback, cleanup WARN)를 두었고, Compose 실행 환경과 생성 파일의 수명을 격리하지 않았다.

### 해결

- 시나리오에서 재-freeze 우회를 제거했고, 직접 승인 실패는 run FAILED/exit 1로 전파한다.
- Compose lifecycle core가 cleanup 오류(`down`≠0/throw, timeout, env/override 삭제 실패)를 모아 workflow 성공과 무관하게 exit 1을 낸다. 프로세스 timeout은 bounded kill 후 close를 확인한 뒤에만 정리하고 kill/close 실패를 보존한다.
- env 격리: caller의 `COMPOSE_*`와 generated key를 child env에서 제거하고, per-run 고유 env/override를 exclusive 생성 후 scoped delete한다. 5개 포트를 `127.0.0.1`에만 publish하고 `docker compose config`를 fail-closed로 검증한다. ERP fixture는 bounded server close + owned socket 정리를, browser 세션 close 실패는 전파를 추가했다.

### 검증과 교훈

DI 테스트로 `down`≠0/throw, up timeout/partial up/no-exe, verify fault, env 삭제 실패, 동시 2 run, `runStreaming` bounded kill/close를 검증했고, 실제 clean Compose가 고유 project로 up→workflow→`down`함을 확인했다. 열린 요청/dropResponse를 완료 2xx로 오인하지 않는 negative도 fixture 단위 테스트로 고정했다. 검증 하네스의 통과는 실패를 감추지 않는 전파 계약과 자원 수명·환경 격리 위에 세워야 한다.

관련 커밋: `c1e14d6`, `3a1285c`

## P1-11 — 지연 2xx를 handler 진입으로 오인하지 않는 인과 증거

### 문제

SENDING 도중 signed webhook이 수렴한 뒤 ERP의 늦은 HTTP 2xx가 relay finalize를 시도하는지에 대한 증거로, fixture가 요청 handler 진입 시각과 고정 sleep을 사용했다. 이는 실제 응답 완료가 아니며 열린 요청·dropResponse에도 기록돼 늦은 2xx를 주장할 수 없었다.

### 원인

관찰 지점이 HTTP 응답 완료가 아니라 handler 진입이었고, relay가 실제로 finalize에 도달했는지 직접 관찰하지 않았다.

### 해결

- fixture가 response `finish`/`close`를 구분해 요청별 `{status, completedAt, finished, closedWithoutFinish}`를 기록하고, 실제 status가 finish된 응답만 완료로 노출한다.
- focused JUnit이 기존 package-private `PaymentExportInterceptor` seam으로 `beforeFinalize(outcome)`/`afterFinalized`를 관찰한다. 지연 2xx(총 deadline 이하)에서 relay가 200을 수신해 `beforeFinalize(Acknowledged)`에 도달하고, 이미 webhook이 종결했으므로 stale no-op(`afterFinalized` 없음)과 tuple·counts 불변임을 확인한다.
- negative로 초과 deadline open과 `respondDrop()` 모두 `beforeFinalize(ResultUnknown)`임을 고정해 완료 2xx로 오인하지 않음을 보인다.

### 검증과 교훈

focused JUnit 2건과 Node fixture 단위 3건이 통과한다. 이 JUnit은 `PaymentResultWebhookApplicationService`를 직접 호출하므로 서명(HMAC) 검증 경로를 지나지 않으며, HMAC 경로는 별도 검증이며 여기서 주장하지 않는다.

증거 범위: whole backend 514 / web 191 / browser / Compose PASS는 `3a1285c` 시점의 결과이고, 이후 `fe97247`은 focused JUnit 2건과 Node fixture 3건, 독립 fixture에 한정하며 수정된 전체 harness(browser/Compose)는 재실행하지 않았다. 인과 증거의 관찰 지점은 요청 도착이 아니라 응답 완료와 상대 측 후속 동작 도달이어야 하며, 고정 sleep·handler 진입 timestamp·attempt 0은 늦은 2xx나 finalize 도달의 증거가 아니다.

관련 커밋: `fe97247`, `3a1285c`

## R1-02 — CI 환경변수가 테스트 서명 secret을 덮어써 webhook 통합 테스트가 실패한 문제

### 문제

사용자 제공 CI 로그에서 `PaymentResultWebhookIntegrationTest` 10건의 실패를 확인했다. 로컬 `main`(`766889c`)에 CI 환경변수를 적용해 재현하니 12건 중 동일한 10건이 실패했다. 서명된 webhook 요청은 모두 401을 받았고, 잘못된 서명 거부와 직접 DB 삽입 방어를 검사하는 나머지 2건은 통과했다. CI 환경변수를 적용하지 않은 이전 Windows 전체 suite는 통과했었다. 실패 run의 SHA는 미확인이다.

### 원인

테스트는 상수 `SECRET = "test-webhook-secret"`으로 HMAC을 계산하지만, `application-test.yml`의 `mock-erp.webhook.secret=test-webhook-secret`보다 OS 환경변수 `MOCK_ERP_WEBHOOK_SECRET=ci-only-webhook-secret`이 우선한다. CI 환경변수를 적용하면 애플리케이션 검증 secret과 테스트 서명 secret이 달라져 서명 검증이 실패했다. 테스트가 주변 환경에 암묵적으로 의존한 문제다.

### 해결

`PaymentResultWebhookIntegrationTest`의 `@DynamicPropertySource`에서 서명 상수와 동일한 값으로 `mock-erp.webhook.secret`을 등록했다. 테스트 전용 dynamic property는 OS 환경변수와 profile 속성보다 우선하므로 주변 CI env와 무관하게 테스트가 자기 서명 secret을 고정한다. 제품 HMAC 검증, 커밋된 CI env, `application-test.yml`은 바꾸지 않았고 기존 위조·만료·누락 서명 거부 검증도 유지했다.

### 검증과 교훈

커밋된 CI env를 그대로 둔 focused 재현에서 수정 전 12 tests/10 failures(모두 401), 수정 후 12 tests/0 failures였고, 같은 env의 whole backend `clean test bootJar`는 516 tests/0 failures/0 errors/0 skipped로 성공했다. web 208, mock-erp 7, mock-purchasing 6와 Compose config/build/격리 smoke도 통과했다. 테스트는 주변 환경변수에 의존하지 않고 자기가 검증할 secret을 명시적으로 고정해야 하며, 같은 suite가 로컬에서 통과해도 CI 환경변수 우선순위로 실패할 수 있음을 보여준다. 원격 실패 run URL·SHA는 확보하지 못해 Windows 로컬 재현이며 Ubuntu CI 복구를 직접 주장하지 않는다.

관련 커밋: `96a4b5b`

## P2-01 — 동시 문서 완료 응답의 시간 정밀도 불일치

### 문제

동일 문서를 서로 다른 requestId로 동시에 완료하면 문서 등록·사건 version 증가·감사는 한 번만 반영됐지만 두 성공 응답의 `registeredAt`이 달랐다. 최초 응답은 `...702561700Z`, 이미 등록된 문서를 읽은 응답은 `...702562Z`였다. 같은 논리적 완료 결과를 정확히 replay한다는 계약을 위반했다.

### 위험 또는 원인

JVM Instant의 나노초 정밀도를 PostgreSQL timestamptz가 마이크로초로 반올림했다. DB 저장 전 객체를 응답한 경로와 저장 후 조회한 경로가 서로 다른 시각 표현을 반환했다. byte/hash·caseVersion·문서 ID는 같아 파일 자체의 정합성 오류는 아니었다.

### 해결

문서 등록 시각과 예약 시각을 DB 정밀도인 마이크로초로 절삭한 뒤 저장·응답·멱등 응답에 공통으로 사용한다. 시간 비교 허용오차나 응답 필드 제거로 테스트를 완화하지 않았다. 저장소 읽기/쓰기는 잠금 밖에 두고 마지막 DB 트랜잭션이 현재 사건/작성 차수를 재검증한다. 경합 loser 및 실패 요청의 candidate object는 트랜잭션 밖에서 제거한다.

### 검증과 교훈

실제 MinIO/PostgreSQL에서 동일 requestId와 다른 requestId의 동시 완료가 동일한 성공 JSON을 반환하며 문서·감사·version은 한 번만 생성됨을 확인했다. URL 재사용 후 확정 원본 불변과 감사 실패 시 문서/version/멱등 응답 rollback 및 같은 요청 재시도도 통과했다. focused 16건 및 whole backend 538건, 실패/오류/skip 0과 bootJar PASS. 멱등성은 side effect 수뿐 아니라 저장·조회 경로의 응답 표현까지 검증해야 한다. 원격 CI는 확인 대기다.

## P2-03 — 스토리지 health와 S3 초기화 완료 시점의 차이

### 문제

MinIO 컨테이너 health가 통과한 직후 bucket을 만드는 통합 테스트에서 `XMinioServerNotInitialized`가 발생했다. static 초기화 실패가 문서 테스트 전체로 전파돼 기능 회귀와 인프라 준비 실패를 구분하기 어려웠다.

### 위험 또는 원인

HTTP health 성공이 실제 S3 API의 초기화 완료까지 보장하지 않았다. 전체 suite를 반복 실행하거나 모든 오류를 재시도하면 실제 설정·권한 오류도 가려질 수 있다.

### 해결

공통 테스트 bootstrap은 이 초기화 오류만 30초 한도에서 기다린다. 이미 소유한 bucket만 성공으로 처리하고 다른 오류·인터럽트는 즉시 실패시킨다. 업무 assertion·전체 테스트 재시도나 skip은 추가하지 않았다. 기존 MinIO 이미지는 재사용하고 저장소 호출과 업무 DB 트랜잭션 경계 검증을 유지했다.

### 검증과 교훈

실제 PostgreSQL/MinIO를 사용하는 backend 전체 단일 실행에서 571 tests/64 classes, failures/errors/skipped 0 및 bootJar가 통과했다. 실제 API/브라우저에서도 PDF/XLSX 원본 byte/checksum, 권한·URL 만료·재발급을 확인했다. 준비 확인은 해당 자원이 실제로 제공해야 할 연산까지 검증하고, transient 오류를 좁혀 업무 실패를 숨기지 않아야 한다.

관련 backend 커밋: `cd8f690`

## P2-06 — SDK 종료 timeout과 실제 I/O 중단 경계

### 문제

발행 Future에 10초 timeout을 적용해도 timeout 처리에서 호출한 RabbitMQ SDK abort의 종료 프레임 쓰기가 멈추면 호출자도 대기할 수 있었다. 종료 응답 대기 timeout만으로 전체 호출 시간을 제한했다고 볼 수 없었다.

### 위험 또는 원인

SDK abort의 timeout은 close-ok 대기를 제한하며 그 앞의 socket write는 별도 경계다. 공유 cancellation/connection 상태나 timeout 직후 슬롯 해제는 이전 시도가 새 연결에 영향을 주거나 작업을 누적시키는 위험도 있었다.

### 해결

단일 admission 슬롯을 실제 작업 finally까지 유지하고 시도별 cancellation·연결·raw TCP socket을 분리했다. 소켓을 connect 이전 등록하고 timeout/interruption/close에서 소유 소켓만 직접 닫아 I/O를 중단한다. SDK 정리는 닫힌 소켓 위에서 worker가 수행하며 호출자는 SDK 종료 요청을 기다리지 않는다. application은 relay 정책/port, persistence는 token 조건부 갱신, infrastructure는 SDK/socket 수명을 맡는다.

### 검증과 교훈

실제 PostgreSQL/RabbitMQ에서 mandatory return·lease fencing·보완 취소 rollback·confirm 후 DB finalize 전 중단 재발행을 검증했다. 실제 handshake 중 close의 소유 소켓 EOF, 등록 경합의 늦은 연결 종료, outer deadline 후 busy 거부와 정상 발행 회복도 확인했다. 전체 baseline 612건과 국소 최종 focused 50건은 실패/오류/skip 0, bootJar 통과. 시간 제한은 timeout API 이름보다 어느 스레드가 어떤 I/O를 실제로 중단하는지로 판단해야 한다. 현재 plain TCP 경계는 TLS 도입 시 재검토한다.

관련 최종 커밋: `17f869d`

## P2-07 — 멱등 응답과 신규 실행 권한의 분리

같은 문서 결과를 다시 받았다는 이유만으로 바로 성공 응답을 반환하면, 다른 입력 버전이나 증빙 hash를 보낸 요청까지 replay로 통과할 수 있다. 반대로 모든 요청에 미만료 실행 token을 요구하면 완료 후 중복 전달을 안전하게 수락할 수 없다.

입력 식별자를 먼저 검증하고, 이미 저장된 동일 결과의 replay와 신규 결과의 실행 권한 검사를 분리했다. lease의 상태 값만 검사하는 DB guard도 충분하지 않았다. 같은 RUNNING 상태 안에서 heartbeat와 재claim을 token·attempt·만료 여부의 조합으로 구분해야 만료 lease 부활이나 실행권 탈취를 막을 수 있다.

회귀 fixture는 정상 제출자료와 실제 run을 기준으로 한 조건씩 바꾸고, 실패 뒤 DB 효과가 없는지 확인했다. lease를 SQL로 되감아 만료를 흉내 내는 대신 짧은 실제 lease와 제한된 DB 시계 대기를 사용했다. 거부 테스트에는 정상 대조군과 정확한 오류 검증이 함께 있어야 다른 제약의 실패를 원하는 guard의 증거로 오인하지 않는다.

## R1-01 — 업무 정책과 SQL의 책임 분리

조회와 webhook 처리에서 SQL을 application과 함께 두면 권한·상태 정책을 바꿀 때 저장 방식까지 함께 읽어야 했다. 조회 조건과 잠금·조건부 갱신은 persistence가 소유하고, actor 범위와 상태 판단·transaction orchestration은 application이 소유하도록 분리했다.

canonical 직렬화는 hash 호환성을 유지해야 하므로 전역 JSON 설정과 분리했다. 반면 멱등 응답은 저장된 HTTP status와 응답값을 그대로 재생해야 하므로, 계층 순수성을 이유로 기존 replay 표현을 일괄 변경하지 않았다. 책임을 나누는 리팩터링에서도 관찰 가능한 계약과 write/lock 순서는 보존해야 한다.

## P2-09~12 — 메시지 ACK보다 먼저 복구 책임을 저장하기

워커 종료 뒤 메시지를 재전달해도 아직 살아 있는 실행 lease를 만나면 바로 재실행할 수 없다. 메시지를 ACK해버리면 후속 실행이 사라지고, 계속 requeue하면 바쁜 루프가 된다. 따라서 BUSY·일시 실패는 같은 eventId의 다음 전달 시점을 DB checkpoint로 저장한 뒤 ACK한다. Core가 checkpoint를 확인해주지 못하면 ACK하지 않는다.

완료한 문서 결과는 immutable하게 보존하고 재시도에서는 같은 checksum/parser/hash의 결과만 재생한다. 운영 재처리도 기록을 지우거나 새 사건을 만드는 대신 현재 소진된 실행에 제한된 추가 예산을 부여한다. 이 구조는 DB 결과를 한 번 저장하게 하지만, 외부 OCR/LLM 호출을 정확히 한 번 수행하거나 비용을 완전히 없앤다는 뜻은 아니다.

실제 broker 중단 중 제출과 source I/O 중 worker 강제 종료를 검증했다. 실제 lease 만료 후 재claim이 같은 요청을 완료했고 업무 version은 바뀌지 않았다. 이 과정에서 RabbitMQ 노드 ping만으로 AMQP 준비를 판단할 수 없음을 확인해 앱/포트 준비 확인을 추가했다. 장애 검증은 복구 작업의 책임이 어디에 영속되는지와 서비스가 실제로 받을 준비가 됐는지를 함께 확인해야 한다.

## P3-03~04 — 검색 전에 입력 scope와 버전을 고정하기

최신 정책이 검색에서 빠졌다고 예전 공개 버전을 대신 쓰면, 적용 가능한 문서를 찾았다는 이유로 잘못된 근거를 반환할 수 있다. 먼저 발주에 연결된 적용 버전을 선택하고 그 버전의 읽기 권한을 확인한다. 동결한 정책 목록이 바뀌면 기존 AI 실행은 stale이며, 같은 발주의 다른 사건에서 정책을 등록하는 경합도 짧은 scope 잠금으로 직렬화한다.

실제 vector DB에서는 Java float와 DB JSON 숫자의 타입 차이로 동일 요청의 replay가 충돌했다. 객체 타입 비교 대신 canonical JSON을 비교하고 저장 표현으로 응답을 재생했다. 멱등성은 같은 프로세스의 같은 객체보다 DB 왕복 후에도 같은 의미가 유지되는지로 검증해야 한다. 검색 순위·권한·동률은 실제 pgvector로 검증했지만 이는 실제 embedding 모델의 검색 품질 측정과는 별개다.

## P3-06 — 모델의 설명과 승인 사실을 분리하기

JSON schema를 통과한 초안이라도 잘못된 정책 버전이나 금액을 인용할 수 있다. citation은 저장한 적용 문단의 version·위치·원문과 대조하고, 금액·수량은 동결한 대사 결과에서 다시 계산한다. 근거 없음·충돌·품목 확인 필요는 검토 상태로 남겨 모델의 설명이 승인 효력을 만들지 않게 했다.

서버가 정확한 정수를 계산해도 브라우저의 숫자 표현 범위를 넘으면 표시 단계에서 값이 바뀔 수 있다. 검토 자료의 금액은 정수 문자열로 전달하고 출처와 함께 표시한다. 검증은 모델 응답 형식뿐 아니라 저장·재생·표시를 거친 사실의 일치까지 포함해야 한다.

## P3-07 — 응답 유실과 호출 비용은 별개의 사실이다

외부 요청의 응답을 받지 못해도 제공자가 이미 처리했을 수 있다. 재시도에서 호출 예산을 초기화하면 장애가 비용 상한을 우회한다. 호출 전에 예약을 저장하고, 불확실한 예약도 누적 비용에 포함했다. 성공 단계는 저장한 checkpoint를 재생하지만 저장하지 못한 외부 실행의 중복까지 방지한다고 주장하지 않는다.

실제 broker·Core·설치된 Linux worker에 응답 유실과 프로세스 중단을 주입해 복구를 확인했다. 결정된 결과를 재생하는 멱등성과 외부 호출의 exactly-once는 서로 다른 보장이다.

## P4-04 — 재개 명령 저장과 사람 노드 완료는 다른 경계다

사람 확인의 resume write가 저장된 뒤 프로세스를 강제 종료하면, lease를 되찾아도 SDK가 사람 노드를 다시 실행할 수 있다. resume 슬롯 존재만으로 노드 완료를 판단하면 확인이 다시 interrupt로 끝난다. 또한 pin한 SDK의 `None + checkpoint_id`는 저장된 현재 작업의 이어가기와 달리 과거 checkpoint 재실행으로 해석된다. 예외로 응답 유실을 흉내 낸 테스트만으로는 실제 SIGKILL의 저장 경계를 충분히 검증하지 못했다.

Core에 불변 확인·소비 identity를 저장하고 정확한 대기 checkpoint에 같은 resume 명령을 다시 적용한다. 정확한 review 출력이 이미 저장됐거나 head가 진행된 경우에는 현재 head에서 이어간다. 성공 단계와 호출 예약은 보존하며, 실제 broker·Core·설치된 Linux worker의 새 프로세스에서 SIGKILL과 lease reclaim 뒤 완료까지 확인했다. 복구 검증은 ACK만이 아니라 최종 결과와 기존 호출·예산의 보존까지 확인해야 한다.

BUSY의 복구 delivery도 한 번 발행됐다는 이유로 재예약을 무시하면 후속 전달이 사라질 수 있다. 같은 실행 token의 defer는 이미 발행된 delivery까지 다시 READY로 만들고 다음 실행 시점을 저장한다. 진행 중인 발행의 옛 finalize는 token fencing으로 막는다. 소비 여부는 확인을 중복 생성하지 않는 기록이며, 아직 완료하지 못한 실행의 복구를 막는 표식으로 사용하지 않는다.

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
