# Runbook — Phase 1 demo and verification

## Prerequisites

- Docker Desktop with Compose, Java 21, Node 24 and npm.
- A private `.env` copied from `.env.example` with a unique
  `POSTGRES_PASSWORD` and `MOCK_ERP_WEBHOOK_SECRET`. `.env` is git-ignored and is
  never committed, logged or stored in a payload.

## 5–7 minute demo

1. **Bring the stack up** (about a minute cold):
   `docker compose up --build -d --wait`
   Open <http://localhost:3000> and sign in at `/login`.
2. **정상 청구 (2 min):** as `submitter`, create a case (`SUP-1` / `PO-1001` /
   `INV-DEMO-1`), enter line 1 `Copy Paper`, qty `5`, unit price `2500`, confirmed
   item `ITEM-A4-80`, and submit. Switch to `operator`, run 대사, then as
   `approver` freeze the 검토 대상 and approve. The case moves
   `REVIEW_PENDING → EXPORT_PENDING → EXPORTED`; the hand-off screen shows the
   real `ACKNOWLEDGED`/`DELIVERED` state and the explicit “ACK is not a fund
   transfer” note.
3. **예외·매핑 (1.5 min):** create a second case with no confirmed item, run
   대사 → `품목 매핑 미확정`, freeze, map line 1 to `ITEM-A4-80`, and show the
   successor comparison. This reproduces the mapping exception.
4. **보완 후 재제출 (1 min):** create a case with qty `100` on `PO-1001`
   (receipt is `60`), run 대사 → `검수 수량 초과`, request 보완, open the
   supplement revision, resubmit qty `60`, re-match (normal) and approve.
5. **동시 승인 충돌 (1 min):** two cases each requesting `40` against the same
   receipt with `60` confirmed; approving the two concurrently lets exactly one
   win and the other shows `INSUFFICIENT_RECEIPT_BALANCE` with the remaining
   quantity.
6. **ERP 응답 유실 (1 min):** the hand-off screen distinguishes
   `RESULT_UNKNOWN` (결과 불명, never auto-resent) from `FAILED`. The isolated
   acceptance reproduces the loss-then-status-inquiry-then-signed-result path.
7. **감사·추적 (0.5 min):** open the audit tab and show the actor, action,
   before/after diff, request id and trace id for the approval.

Stop when done: `docker compose down` (keeps the named volume) or
`docker compose down -v` to remove local data deliberately.

## Full verification

```sh
# Backend: real PostgreSQL via Testcontainers
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

## Isolated integration acceptance (P1-11)

Owns only what it creates (ephemeral PostgreSQL container, mock-purchasing,
in-process Mock ERP fixture, core-api jar, web standalone). Writes sanitized
evidence to `output/p1-11/evidence.json`.

```sh
node scripts/verify-p1-11.mjs            # HTTP + DB + concurrency + fault
node scripts/verify-p1-11.mjs --browser  # plus real Chromium hand-off states
```

The browser pass covers `ACKNOWLEDGED/DELIVERED/EXPORTED`, `FAILED` and
`RESULT_UNKNOWN` and writes
`output/playwright/p1-11-phase-one/` (screenshots + `browser-summary.json`).

## Clean Compose smoke

Brings the five services up as a uniquely named, isolated project with generated
test-only credentials, runs one full workflow, and removes only that project.

```sh
node scripts/compose-smoke-p1-11.mjs
```

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
