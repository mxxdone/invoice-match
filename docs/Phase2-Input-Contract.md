# Phase 2 input contract

Phase 2 adds document intake, AI extraction/reasoning, asynchronous analysis and
production messaging. This document records the **frozen Phase 1 interfaces** it
must build on and the **Phase 2 additions** that do not exist yet. It is derived
from the implemented domain/API and the relevant `docs/Spec.md` sections only; it
does not invent endpoints or tables. The source of truth for scope is
`docs/Spec.md` 4.1/4.2 and 8–9, and for the current contract `docs/API.md` and
`docs/ERD.md`.

## What Phase 1 freezes (Phase 2 must not silently change)

### Approval subject (ADR 0001)

- `ReviewSnapshot` is the approval subject. It records the case version, the
  evidence bundle id/version/hash, the source match result id/number/hash and
  full calculation evidence, the effective case-local mappings, invoice line
  amounts/total, the captured purchasing snapshot versions/hash, and a canonical
  `review-snapshot-v1` payload hash.
- `POST /api/invoice-cases/{id}/approve` consumes the exact snapshot id +
  payload hash into `ReceiptAllocation`, one APPROVED `ReviewDecision`, one
  `PaymentRequest`, the `APPROVE` audit and `EXPORT_PENDING`, atomically.
- Invariant: a stale, superseded or mismatched subject is rejected with `409`
  and no side effect. Phase 2 analysis results must never approve, change an
  approval subject, or bypass the human decision (Spec invariant 8).

### Evidence and matching (ADR 0002; Spec 4.4)

- `EvidenceBundle` freezes the input at submission. The canonical
  `match-result-v3` payload and its SHA-256 are deterministic and AI-free.
- Exception taxonomy: `ITEM_UNCONFIRMED`, `EVIDENCE_INSUFFICIENT`,
  `QUANTITY_EXCEEDS_RECEIPT_BALANCE`, `UNIT_PRICE_MISMATCH`,
  `DUPLICATE_INVOICE_SUSPECTED`.
- Receipt facts live in the external purchasing snapshot; local consumption
  lives in `ReceiptAllocation`. Phase 2 must extend the same two namespaces
  rather than conflate them.
- A superseded evidence/mapping version can be retained but never used as the
  current approval basis (Spec invariants 4, 9).

### Transactional outbox and ERP hand-off (ADR 0003; Spec 13–14)

- `PaymentRequest` + `outbox_event` are written in the approval transaction.
  The export idempotency key is `paymentRequestId:exportVersion`; the external
  request key is `PAYMENT:{caseId}:{snapshotId}`.
- Phase 1 has an in-process relay only. Phase 2 may put RabbitMQ, bounded
  retry/DLQ and operator reprocessing **behind the same event contract**
  (`P1-08` relay → `PaymentRequestExportRequested` → ERP adapter), without
  changing the durable keys or the `RESULT_UNKNOWN` no-blind-resend rule.
- The signed result webhook (`provider + externalEventId` dedup) is the only
  writer that resolves `RESULT_UNKNOWN`; a result never creates a new payment or
  export key. `ACKNOWLEDGED` is ERP acceptance, not a fund transfer.

### Identity, idempotency and audit (P1-06)

- Actor-scoped idempotency `(scope, resource_key, actor, requestId)` and the
  authoritative server-derived actor are frozen. Phase 2 workers must carry a
  system/service actor with the same audit and idempotency guarantees.
- `audit_entry` is append-only and transactionally recorded with the mutation.

### State machine (Spec 10)

- Invoice case: `DRAFT → SUBMITTED → REVIEW_PENDING → (SUPPLEMENT_REQUIRED →
  SUBMITTED | REJECTED | EXPORT_PENDING → EXPORTED)`. Approval writes the
  immutable `ReviewDecision(APPROVED)`, not an intermediate `APPROVED` case
  state.
- Analysis run: `QUEUED → RUNNING → (WAITING_HUMAN_INPUT → QUEUED |
  RETRY_SCHEDULED → RUNNING | COMPLETED | FAILED | STALE)` — **not implemented
  in Phase 1**; Phase 2 introduces it without changing the case state machine.
- External hand-off: `NOT_SENT → SENDING → (ACKNOWLEDGED | RETRY_SCHEDULED →
  SENDING | FAILED | RESULT_UNKNOWN)` — implemented.

## Phase 2 additions (not present in Phase 1)

These are explicitly out of scope for Phase 1 and must not be claimed as done:

- Document upload (presigned URL to S3/MinIO), `Document` metadata, PDF/Excel
  parsing and 원문 미리보기/다운로드/표준 인쇄 (Spec 4.2, 15, 19.2).
- `AnalysisRun`, the LangGraph workflow, tool/API calling, RAG over contracts and
  internal guidance with applied document version and citations (Spec 8–9).
- Proposed resolutions/`Proposal` as non-binding review material only; they can
  never become `ReviewDecision(APPROVED)` or `EXPORT_PENDING` on their own
  (Spec invariants 8, 9).
- AI evaluation baseline and cost/latency/error reporting (Spec 24.3).
- RabbitMQ, limited retries, DLQ and operator reprocessing behind the existing
  outbox event contract (Spec 14.3).
- Daily processing statistics/dashboard (Spec 21).

## Integration seams Phase 2 will use

| Existing seam | Phase 2 use |
| --- | --- |
| `EvidenceBundle` (id/version/hash) | input version for an analysis run; a new submission creates a new bundle and marks prior runs `STALE` |
| `match-result-v3` canonical payload | AI findings are attached as review material beside it; the deterministic match remains authoritative |
| `ReviewSnapshot` / `ReviewDecision` | human decision stays the only approval authority |
| `outbox_event` + ERP adapter | broker publish replaces the in-process relay without changing keys |
| `payment_result_event` webhook | unchanged dedup/ordering contract |
| `POST /api/invoice-cases/{id}/revisions` | correction loop after supplement/rejection |
| Actor-scoped idempotency | service/worker actor identity and replay semantics |

## Phase 2 acceptance implications

- AI results must pass the same amount/quantity/balance/authorization checks as
  manual input (Spec 28).
- A worker that stores a result and then dies before ack may re-call the LLM;
  duplicate business effect must still be prevented by the existing idempotency
  and analysis-input keys (Spec 14.3).
- The Compose/README reproduction requirement (Spec 28) still holds.
