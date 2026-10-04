# Invoice Match

매입 청구서를 발주·검수 자료와 비교하고, 사람이 검토해 지급요청으로 확정하는 업무 코어다. 승인·검수 잔량 배분·지급요청을 원자적으로 처리하고 Outbox로 외부 인계를 분리한다.

Java 21 / Spring Boot / PostgreSQL / Next.js / MinIO / RabbitMQ / Python 파서를 사용한다. Mock ERP 인계 성공은 실제 송금이 아니다. 운영자는 `/operations/analysis`에서 실패 이력을 확인하고 원인 수정 후 재처리를 예약한다. 선택적 AI 추출·매핑·정책 근거·처리 초안과 검토 화면을 구현했으며, AI는 기본 비활성화다. 실제 제공자 품질·비용 평가는 API 설정 후 수행한다.

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

임시 업로드 정리는 기본 비활성이다. `DOCUMENT_CLEANUP_ENABLED=true`로 활성화하면 예약 만료 후 기본 24시간이 지난 임시 사본만 삭제한다. 확정 원본과 제출 근거는 보존한다.

## 검증

문서 비동기 파싱은 `.env`의 `ANALYSIS_RABBIT_USERNAME`, `ANALYSIS_RABBIT_PASSWORD`, 32자 이상의 `ANALYSIS_WORKER_TOKEN`을 설정하고 기존 세 Compose 파일에 `-f compose.analysis.yaml`을 추가해 실행한다. worker는 Linux에서 실행하며 저장소 secret을 받지 않는다. 일시적 장애는 DB checkpoint 후 최대 3회 실행하고 소진 시 `invoice.analysis.requests.dlq`로 보낸다. core 전체 장애·인증 오류로 checkpoint를 저장할 수 없으면 ACK 없이 중단한다. process 재시작도 3회로 제한하므로 원인을 해결한 뒤 같은 Compose 조합의 `restart ai-worker`로 재개한다.

정책 검색용 pgvector는 새 Compose project에 `-f compose.ai.yaml`을 추가해 사용한다. AI는 기본 비활성화이며 `ANALYSIS_AI_ENABLED`로 명시적으로 켠다. 일반 PostgreSQL은 lexical 검색을 지원하고 vector/hybrid는 extension을 요구한다. 실제 vector 회귀는 `INVOICE_MATCH_TEST_POSTGRES_IMAGE=pgvector/pgvector:0.8.6-pg18-bookworm`을 설정해 같은 backend 테스트를 실행한다. embedding 모델·버전·차원을 고정하고 검색과 ingestion에서 일치시킨다.

AI API 준비 전에는 `.env.example`의 AI 항목을 비워 두고 `ANALYSIS_AI_ENABLED=false`를 유지한다. 준비 후 `.env`에 HTTPS 모델 endpoint·키·모델명·공개 토큰 단가·통화·실행별 비용 상한을 입력하고 기존 Compose 파일 조합에 `compose.ai.yaml`과 `--profile ai`를 추가한다. 별도 proposal worker가 실행하며 실제 제공자 품질은 설정 후 측정한다.

스캔 PDF용 Azure는 [Document Intelligence 생성 안내](https://learn.microsoft.com/en-us/azure/ai-services/document-intelligence/how-to-guides/create-document-intelligence-resource?view=doc-intel-4.0.0)에 따라 Azure 구독에서 F0 리소스를 만들고 **Keys and Endpoint** 값을 `AZURE_DOCUMENT_KEY`·`AZURE_DOCUMENT_ENDPOINT`에 입력한다. `AZURE_DOCUMENT_TIER=F0`를 유지한다. Azure 키와 모델 키는 별개이며 로컬 `.env`만 사용한다.

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

실제 분석 흐름 인수는 최신 `bootJar`와 worker 이미지 빌드 후 `WORKER_RUNTIME_IMAGE=<이미지> node scripts/verify-p2-08.mjs`로 실행한다.

Phase 3 복구 흐름도 같은 스크립트에서 `VERIFY_PROPOSALS=true`로 검증한다. 설치된 Linux worker·Core·RabbitMQ와 격리 HTTPS 모델 fixture를 사용해 중복, 외부 실패, 저장 응답 유실, 워커 중단과 stale을 확인한다. 유료 제공자 API를 호출하지 않으며 실제 모델 품질 평가와 구분한다.

Phase 4는 별도 graph의 Core 영속화까지 구현했으며 worker adapter·사람 확인·resume 연결은 후속 Ticket이다. graph는 기본 비활성이고 기존 parser·v1 제안·사람 검토를 유지한다. 상태·저장·재개 계약은 [Spec 8.3](project-docs/Spec.md#83-langgraph-상태), 현재 진입점은 [Plan](project-docs/Plan.md)을 따른다.

AI 평가 기준선은 `python scripts/evaluate-p3.py`로 실행한다. 고정된 64개 합성 사례를 사용하며 결과는 ignored `output/p3/evaluation/offline.json`에 저장한다. 실제 측정 결과는 `--ai-predictions <파일>`로 같은 사례와 비교한다. 실패·누락도 분모에 포함하고 호출 사용량과 누적 예약 예산을 구분한다. 합성 OCR 자료는 Azure 정확도 근거가 아니며, API 미설정 시 AI 품질·비용·사람 검토시간은 미측정으로 남긴다.

스크립트는 자기 검증 자원만 생성·회수하고 결과를 ignored `output/`에 남긴다. Compose smoke는 자체 빌드한다. 저장소 검증은 `node scripts/verify-p2-00.mjs`, 문서 API focused 검증은 `node scripts/verify-p2-01.mjs --focused`를 사용한다. 필요한 서비스만 실행하고 사용자 DB·volume은 초기화하지 않는다.

## 프로젝트 문서

- [Spec](project-docs/Spec.md): 제품·업무 기준
- [Plan](project-docs/Plan.md): 로드맵·현재 작업 계약
- [AGENTS](AGENTS.md): 공통 작업 규칙과 필요한 문서 라우팅
- [EngineeringNotes](project-docs/adr/EngineeringNotes.md) · [ADR](project-docs/adr/): 면접용 문제 해결 사례·설계 결정 이유
