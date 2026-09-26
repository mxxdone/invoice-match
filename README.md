# Invoice Match

P1-00 provides a runnable baseline for the invoice matching project. It has one Java 21 Spring Boot 3 API, a Next.js TypeScript web app, PostgreSQL, and a minimal Mock ERP process. P1-01 adds the domain contract and PostgreSQL schema baseline (invoice case, draft revision, evidence bundle, match result, review snapshot and decision). P1-02 adds a read-only external purchasing system Mock (`mock-purchasing`), a read-only Core API adapter and a PostgreSQL-backed current snapshot of suppliers' purchase orders, lines, receipts and receipt lines with external versions. P1-03 adds the first business write APIs: manual invoice case creation validated against the external purchase order, atomic current-draft editing, submission that freezes a canonical hashed `EvidenceBundle` version, supplement revisions that copy the previous frozen lines, past bundle version reads and request-id idempotency.

## Run all services

Install Docker Desktop with Compose. Copy `.env.example` to `.env`, then replace its placeholder `POSTGRES_PASSWORD` with a unique local password. `.env` is ignored by Git.

```sh
docker compose up --build -d --wait
```

Open <http://localhost:3000>. The health endpoints are:

| Service | URL |
| --- | --- |
| Core API, including database health | <http://localhost:8080/actuator/health> |
| Web | <http://localhost:3000/api/health> |
| Mock ERP | <http://localhost:8081/health> |
| Mock purchasing | <http://localhost:8082/health> |

The read-only Mock purchasing aggregate is deterministic, for example
<http://localhost:8082/api/purchase-orders/PO-1001> (confirmed order with a confirmed partial
receipt) and <http://localhost:8082/api/purchase-orders/PO-1002> (unconfirmed order).

## Invoice case API (P1-03)

All write endpoints require a `requestId`. Repeating a request with the same
`requestId` and the same payload replays the original response without repeating
the side effect; the same `requestId` with a different payload is a `409`
conflict.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/invoice-cases` | Create a case after validating the external purchase order, starting OPEN draft revision v1 |
| `GET` | `/api/invoice-cases/{id}` | Read the case header, current OPEN draft and its lines |
| `PUT` | `/api/invoice-cases/{id}/draft` | Atomically replace the current OPEN draft's lines (requires `expectedCaseVersion`) |
| `POST` | `/api/invoice-cases/{id}/submit` | Freeze the current draft into the next immutable `EvidenceBundle` version and move to `REVIEW_PENDING` |
| `POST` | `/api/invoice-cases/{id}/revisions` | In `SUPPLEMENT_REQUIRED`, open the next OPEN revision copied from the previous frozen bundle |
| `GET` | `/api/invoice-cases/{id}/evidence-bundles` | List frozen bundle versions with payload hashes |
| `GET` | `/api/invoice-cases/{id}/evidence-bundles/{version}` | Read one frozen bundle's canonical payload and hash |

Validation failures return `400`; stale versions, non-editable drafts, invalid
state transitions and idempotency conflicts return `409`; a missing external
purchase order returns `404`; an external purchasing timeout returns `503`.

Check PostgreSQL connectivity and its timezone:

```sh
docker compose exec postgres psql -U invoice_match -d invoice_match -c "SELECT 1, current_setting('TimeZone');"
```

Stop the services with `docker compose down`. This preserves the named PostgreSQL volume. To remove local database data deliberately, use `docker compose down -v`.

## Work on a service locally

With PostgreSQL running (`docker compose up -d postgres`), set `DB_PASSWORD` to the same value as `POSTGRES_PASSWORD` in your private `.env`, then run:

```sh
cd core-api
./gradlew bootRun
```

The Gradle wrapper requires Java 21; no global Gradle install is needed. On Windows PowerShell, use `.\gradlew.bat bootRun`.

For web development, use Node.js 24 and npm:

```sh
cd web
npm ci
npm run dev
```

The Mock ERP service has no external npm dependencies:

```sh
cd mock-erp
npm start
```

The Mock purchasing service is also dependency-free and read-only:

```sh
cd mock-purchasing
npm start
```

Ports are 8080 (core), 3000 (web), 5432 (PostgreSQL), 8081 (Mock ERP), and 8082 (Mock purchasing). The Core API database pool initializes connections in UTC, and PostgreSQL runs with UTC as its server timezone.

If a host port is already occupied, change `CORE_API_PORT`, `WEB_PORT`, `POSTGRES_PORT`, `MOCK_ERP_PORT`, or `MOCK_PURCHASING_PORT` in `.env`. Internal service addresses and ports do not change.

## Verify

The `core-api` tests run Flyway migrations and JPA persistence against a real PostgreSQL started with Testcontainers, so Docker must be available.

```sh
cd core-api
./gradlew clean test bootJar
cd ../web
npm ci
npm run lint
npm test
npm run build
cd ../mock-erp
npm test
cd ../mock-purchasing
npm test
```

The GitHub Actions workflow runs these checks and a five-service Compose smoke test. Source layout is intentionally small: `core-api` holds one Spring application organized by feature (`invoicecase`, `matching`, `purchasingreference`, `review`, `shared`); `web/src/app` holds the Next.js routes; `mock-erp` serves only a deterministic health response; `mock-purchasing` serves deterministic read-only purchase order aggregates. P1-01 defines the Phase 1 state contract and PostgreSQL baseline, P1-02 defines the external purchasing reference snapshot and refresh version semantics, P1-03 defines manual submission, evidence bundle versioning and request-id idempotency, but later tickets still own matching, review, allocation and P1-09 payment behavior.
