# Invoice Match

P1-00 provides a runnable baseline for the invoice matching project. It has one Java 21 Spring Boot 3 API, a Next.js TypeScript web app, PostgreSQL, and a minimal Mock ERP process. Business APIs and data models begin in later tickets.

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

Ports are 8080 (core), 3000 (web), 5432 (PostgreSQL), and 8081 (Mock ERP). The Core API database pool initializes connections in UTC, and PostgreSQL runs with UTC as its server timezone.

If a host port is already occupied, change `CORE_API_PORT`, `WEB_PORT`, `POSTGRES_PORT`, or `MOCK_ERP_PORT` in `.env`. Internal service addresses and ports do not change.

## Verify

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
```

The GitHub Actions workflow runs these checks and a four-service Compose smoke test. Source layout is intentionally small: `core-api` holds one Spring application and leaves feature packages for future tickets; `web/src/app` holds the Next.js routes; `mock-erp` serves only a deterministic health response. There is no P1-01 domain schema or P1-09 payment behavior yet.
