# Invoice Match

매입 청구서를 발주·검수 자료와 비교하고, 사람이 검토해 지급요청으로 확정하는 업무 코어다. 승인·검수 잔량 배분·지급요청을 원자적으로 처리하고 Outbox로 외부 인계를 분리한다.

Java 21 / Spring Boot / PostgreSQL / Next.js / MinIO / RabbitMQ / Python 파서를 사용한다. Mock ERP 인계 성공은 실제 송금이 아니다. AI 추출·DLQ·운영 재처리는 후속 작업이다.

## 로컬 실행

Docker Desktop과 Compose가 필요하다. `.env`는 커밋하거나 로그에 남기지 않는다.

```sh
cp .env.example .env
# POSTGRES_PASSWORD와 MOCK_ERP_WEBHOOK_SECRET을 고유한 개인 값으로 변경
docker compose up --build -d --wait
```

Web: <http://localhost:3000>, Core health: <http://localhost:8080/actuator/health>. Compose가 필요한 이미지를 빌드하므로 별도 jar/web 빌드는 필요 없다.

| 로컬 시연 계정 | 비밀번호 | 역할 |
| --- | --- | --- |
| submitter | submitter-pass | 제출자 |
| approver | approver-pass | 승인자 |
| operator | operator-pass | 운영자 |

이 계정은 local 프로필 전용이다. 중지는 `docker compose down`이며 named volume을 보존한다. `down -v`는 로컬 데이터까지 삭제하므로 버려도 되는 환경에서만 사용한다.

## 문서 저장소 선택 실행

`.env`의 `MINIO_ROOT_USER`와 `MINIO_ROOT_PASSWORD`를 고유한 로컬 값으로 설정한다.

```sh
docker compose -f compose.storage.yaml build --progress=plain
docker compose -f compose.storage.yaml up -d --wait --wait-timeout 90
docker compose -f compose.storage.yaml run --rm minio-init
docker compose -f compose.yaml -f compose.storage.yaml -f compose.documents.yaml up --build -d --wait --wait-timeout 120
```

초기화가 종료 코드 0으로 끝나면 비공개 `invoice-documents` bucket이 준비된다. API는 localhost:9000, 콘솔은 localhost:9001이며 파일은 volume에 보존된다. 최초 MinIO 소스 빌드는 시간이 걸린다.

포트를 변경하면 브라우저용 `DOCUMENT_STORAGE_PUBLIC_ENDPOINT`도 맞춘다. 로컬 JVM은 `.env`를 자동으로 읽지 않으므로 `DOCUMENT_STORAGE_*` 환경변수와 `DOCUMENT_STORAGE_ENABLED=true`를 별도로 설정한다.

중지할 때도 위 세 Compose 파일 조합으로 `down`을 실행한다. 데이터가 필요하면 `-v`를 붙이지 않는다. RabbitMQ relay는 기본 비활성이고 broker 연결 설정은 `core-api/src/main/resources/application.yml`에서 확인한다. Python 파서 실행은 [ai-worker README](ai-worker/README.md)를 따른다.

## 검증

로컬 검증에는 Java 21, Node 24/npm, Docker가 필요하다. Windows의 Gradle 명령은 `gradlew.bat`을 사용한다.

```sh
cd core-api
./gradlew test bootJar --console=plain
cd ../web
npm ci
npm run lint
npm test
npm run build
cd ..
```

빌드된 jar/web을 사용하는 격리 업무 인수와 별도 Compose smoke는 독립 실행 경로다.

```sh
node scripts/verify-p1-11.mjs --browser
node scripts/compose-smoke-p1-11.mjs
```

스크립트는 자기 검증 자원만 생성·회수하고 결과를 ignored `output/`에 남긴다. Compose smoke는 자체 빌드한다. 저장소 검증은 `node scripts/verify-p2-00.mjs`, 문서 API focused 검증은 `node scripts/verify-p2-01.mjs --focused`를 사용한다. 필요한 서비스만 실행하고 사용자 DB·volume은 초기화하지 않는다.

## 프로젝트 문서

- [Spec](project-docs/Spec.md): 제품·업무 기준
- [Plan](project-docs/Plan.md): 로드맵·현재 작업 계약
- [AGENTS](AGENTS.md): 공통 작업 규칙과 필요한 문서 라우팅
- [EngineeringNotes](project-docs/adr/EngineeringNotes.md) · [ADR](project-docs/adr/): 면접용 문제 해결 사례·설계 결정 이유
