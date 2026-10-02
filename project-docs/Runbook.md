# Runbook — Phase 1 demo and verification

## Prerequisites

- Docker Desktop with Compose, Java 21, Node 24 and npm.
- For an interactive Compose run (Path 1), a private `.env` copied from
  `.env.example` with a unique `POSTGRES_PASSWORD` and
  `MOCK_ERP_WEBHOOK_SECRET`. `.env` is git-ignored and is never committed, logged
  or stored in a payload. Paths 2 and 3 generate their own ephemeral secrets.

## Execution paths

There are three independent paths. Pick one; they do not require each other's
build steps.

### Path 1 — interactive Compose demo (UI click-through)

```sh
cp .env.example .env   # set unique personal secrets, never test/example values
docker compose up --build -d --wait
```

Open <http://localhost:3000> and sign in at `/login` with a demo identity from
`README.md`. `docker compose up --build` builds the five images itself; no
separate `bootJar`/`npm run build` is needed. Stop with `docker compose down`
(named volume kept) or `docker compose down -v` to remove local data.

### Path 2 — reproducible isolated acceptance (recommended)

This path needs a built jar and web standalone, then the script starts its own
throwaway stack:

```sh
cd core-api && ./gradlew bootJar
cd ../web && npm ci && npm run build
cd ..
node scripts/verify-p1-11.mjs --browser
```

It creates an ephemeral PostgreSQL container plus child processes
(mock-purchasing, the in-process Mock ERP fixture, core-api, web standalone) and,
on exit, removes only those. It resets only its own throwaway database (never the
user's) between receipt-consuming scenarios. It prints per-scenario PASS and
writes artifacts to `output/p1-11/evidence.json` and
`output/playwright/p1-11-phase-one/` (screenshots + `browser-summary.json`). The
browser pass covers the real hand-off states `ACKNOWLEDGED/DELIVERED/EXPORTED`,
`FAILED` and `RESULT_UNKNOWN`.

Expected tuples / where to look:

| Scenario | Where | Expected persisted tuple |
| --- | --- | --- |
| 정상 승인 | evidence `ac-normal-approval`; hand-off screen | amount `5*2500=12500`; 1 APPROVED decision, 1 payment, 1 outbox, 1 allocation (5), 1 `APPROVE` audit; case `EXPORTED`, payment `ACKNOWLEDGED`, outbox `DELIVERED`; ERP one logical record |
| 60/100 보완→재제출 | `ac-60-100-supplement` | qty 100 → `QUANTITY_EXCEEDS_RECEIPT_BALANCE`; after v2 resubmission qty 60 → allocated 60 |
| 단가 차이 | `ac-unit-price-difference` | `UNIT_PRICE_MISMATCH`; approve `409 APPROVAL_NOT_PERMITTED`, 0 payments |
| 품목 매핑 | `ac-item-mapping` | successor snapshot approved **directly** (no re-freeze), allocated 5 |
| 근거 부족 | `ac-insufficient-evidence` | `EVIDENCE_INSUFFICIENT`; approve `409 APPROVAL_NOT_PERMITTED`, 0 payments |
| 번호 중복 | `ac-duplicate-invoice-number` | `DUPLICATE_INVOICE_SUSPECTED` |
| 거절 종료 | `ac-rejected-zero-effects` | case `REJECTED`; 0 payment/allocation/APPROVED/approve-audit; revision reopen `409` |
| ERP 응답 유실 | `ac-erp-response-loss`, `ac-failed-distinct` | persisted `RESULT_UNKNOWN` (no auto-resend across ticks), same-key inquiry, then the ERP's actual signed result converges it; `FAILED` is distinct and leaves `EXPORT_PENDING` |
| 40+40 경합 ×3 | `ac-shared-receipt-contention` | each run overlaps in time, one winner, loser `INSUFFICIENT_RECEIPT_BALANCE` with remaining 20, committed sum 40, loser zero side effects |
| 브라우저 hand-off | `output/playwright/p1-11-phase-one/` | `ACKNOWLEDGED/DELIVERED/EXPORTED`, `FAILED`, `RESULT_UNKNOWN`, and the DB tuple re-checked after the browser pass |

### Path 3 — clean Compose smoke

```sh
node scripts/compose-smoke-p1-11.mjs
```

Brings the five services up as a uniquely named isolated Compose project with
generated test-only credentials on `127.0.0.1`-only host ports, runs one full
create → submit → match → freeze → approve → Outbox → Mock ERP workflow, and
removes only that project on exit. It builds its own images; it needs no
pre-build.

## Runtime

- **5–7 minutes** is the *target scenario budget* for the manual UI walkthrough,
  not a measured number and not a guarantee.
- Image/standalone preparation (`docker compose up --build` in Path 1,
  `bootJar` + `npm ci` + `npm run build` in Path 2) takes environment-dependent
  minutes and is **separate** from the 5–7 minute budget.
- The automated acceptance (`verify-p1-11.mjs`) and smoke
  (`compose-smoke-p1-11.mjs`) runtimes are separate from the demo budget; quote
  them separately, and record the actual measured wall time when running rather
  than presenting an unmeasured value as accepted.

## Full verification

```sh
# Backend: real PostgreSQL and MinIO via Testcontainers
# P2-01 tests require the fixed local storage image before invoking Gradle.
docker build --progress=plain -t invoice-match-minio:p2-security-2025-10-15 infra/minio
cd core-api
./gradlew clean test bootJar

# Web
cd ../web
npm ci
npm run lint
npm test
npm run build

# Mocks
cd ../mock-erp && npm test
cd ../mock-purchasing && npm test
```

## Interactive UI on an owned stack (optional)

For a live click-through of scenarios that consume the shared receipt, use an
**owned** Compose project with generated ports/credentials (as in Path 3) and
reset only that project's own postgres data between scenarios
(`docker compose -p <project> down -v` then back up). Do not reuse a shared/user
stack: `PO-1001` has one confirmed receipt line of `60`, and a normal case that
consumes `5` leaves only `55`, so a following "corrected 60" approval and a
`40+40` contention cannot both run against the same receipt. Never reset or
modify the user's database or named volume.

## Guardrails

- Never run a bare `docker compose down` in a shared environment and never
  `docker prune` broadly. The scripts only stop resources they created.
- Never read or print a real `.env`; the scripts generate ephemeral secrets.
- A `429` is retried with bounded backoff; a timeout or transport failure is
  `RESULT_UNKNOWN` and is never automatically re-sent. The verification uses no
  blind retry loop: a failure fails the run.
- If a build does not return after completing, inspect for an owned residual
  process before cleaning up; do not repeat an identical long-running command
  blindly.
