# Invoice Match

공급사로부터 받은 매입 청구서를 우리 회사의 발주·검수 자료와 비교하고, 불일치 사건을 사람이 검토해 지급요청으로 확정하는 기업용 업무 코어다. 수동 입력을 결정론적으로 대사하며, 승인·검수 잔량 배분·지급요청을 한 트랜잭션으로 처리하고 transactional Outbox로 Mock ERP에 인계한다.

## 핵심 기능

- 수동 청구 등록·제출 시 증빙 version 동결·요청 단위 멱등성
- AI 없는 결정론적 3-way 대사(5가지 예외 유형)
- 검토 스냅샷, 품목 매핑, 보완요청/거절, 역할 기반 권한과 append-only 감사이력
- 원자적 승인: 검수 잔량 배분 + 승인 결정 + 지급요청 + Outbox를 한 트랜잭션으로 처리
- 인프로세스 relay → Mock ERP 인계, 서명 결과 webhook 수렴(`RESULT_UNKNOWN` 포함)
- 청구 목록·상세/비교·검토·승인·인계·운영 화면(Next.js)

## 기술 스택

- Backend: Java 21, Spring Boot 3, Spring Security, Spring Data JPA, Flyway, PostgreSQL
- Web: Next.js 16 (TypeScript), same-origin `/backend` 프록시
- Infra: Docker Compose, Mock ERP / Mock purchasing (Node, 외부 의존성 없음)
- Tests: JUnit + Testcontainers(실제 PostgreSQL), Node test, Playwright

## 실행

Docker Desktop(Compose)이 필요하다. 개인용 secret은 저장소에 커밋하지 않는다.

```sh
cp .env.example .env
```

`.env`의 `POSTGRES_PASSWORD`와 `MOCK_ERP_WEBHOOK_SECRET`을 **각자 고유한 개인 값**으로 바꾼 뒤(테스트/예시 값 금지):

```sh
docker compose up --build -d --wait
```

접속 주소:

- Web: <http://localhost:3000>
- Core API health: <http://localhost:8080/actuator/health>
- Mock ERP: <http://localhost:8081/health>
- Mock purchasing: <http://localhost:8082/health>

로컬 데모 계정(`local` 프로필에서만 제공되는 공개 시연 계정):

| 사용자 | 비밀번호 | 역할 |
| --- | --- | --- |
| `submitter` | `submitter-pass` | `SUBMITTER` |
| `approver` | `approver-pass` | `APPROVER` |
| `operator` | `operator-pass` | `OPERATOR` |

## Phase 1 범위와 제약

- 포함: 수동 입력 → 결정론적 대사 → 사람 검토 → 원자적 승인 → 지급요청 Outbox → Mock ERP 인계.
- 제약: Mock ERP 인계 성공(ACK)은 실제 송금이 아니며 실제 지급·회계는 외부 ERP 범위다. 구매·검수 데이터는 결정론적 read-only Mock이다. 문서 업로드·AI 추출/LangGraph/RAG, RabbitMQ·DLQ는 아직 구현하지 않는다.

정지: `docker compose down`(named volume 유지) / 데이터까지 제거: `docker compose down -v`.

## Phase 2 문서 저장소 (P2-00)

`.env`의 `MINIO_ROOT_USER`와 `MINIO_ROOT_PASSWORD`를 고유한 로컬 값으로 바꾼 뒤 선택 실행한다. API는 `http://localhost:9000`, 콘솔은 `http://localhost:9001`이며 두 포트 모두 localhost에만 바인딩한다. 콘솔에는 해당 로컬 자격 증명으로 로그인한다. 기존 포트와 겹치면 `.env`의 `MINIO_API_PORT`/`MINIO_CONSOLE_PORT`를 변경한다.

```sh
docker compose -f compose.storage.yaml build --progress=plain
docker compose -f compose.storage.yaml up -d --wait --wait-timeout 90
docker compose -f compose.storage.yaml run --rm minio-init
```

`minio-init`은 `invoice-documents` bucket을 생성하고 익명 접근을 차단한 뒤 정상 종료한다. 종료 코드 0은 초기화 완료 상태다. 파일은 named volume에 보존된다. 첫 빌드는 Go 의존성 다운로드와 컴파일에 시간이 걸린다. MinIO는 공식 [보안 수정 릴리스](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z)를 소스 빌드하며, builder/client 버전도 Dockerfile에 고정한다. 이는 로컬 개발용 실행 기준선이고 운영 배포의 유지보수·업데이트 정책은 별도 검토 대상이다.

반복 검증은 자동 생성한 secret·빈 포트·고유 Compose project로 실행하며, 실제 object 쓰기/읽기·익명 접근 거부·초기화 반복·container 재생성 후 보존을 확인한다. 성공과 실패 모두 검증용 container/volume/secret 파일을 제거한다. 기존 사용자 서버는 건드리지 않으며 credential 없는 증거를 `output/p2-00/<project>/evidence.json`에 저장한다. Node 22+와 Docker Desktop이 필요하다.

```sh
node scripts/verify-p2-00.mjs
```

저장소만 정지하거나 로컬 저장소 데이터까지 제거하는 명령은 각각 다음과 같다.

```sh
docker compose -f compose.storage.yaml down
docker compose -f compose.storage.yaml down -v
```

현재는 저장소 기준선만 제공한다. 사건별 Document와 presigned 업로드/완료 검증은 다음 P2-01에서 연결한다.
