# API — current Phase 1 contract

All application endpoints are under `/api/**` and require HTTP Basic
authentication (demo identities exist only in the `local`/`test` profiles; the
deployable default fails closed). The actuator health probes and the Mock ERP
webhook are public.

This document is a summary index. The authoritative wire contract is the
implemented DTOs and controllers (exact fields, statuses and codes); the
business rules, states and invariants are authoritative in `Spec.md`. If this
summary conflicts with either the implementation or `Spec.md`, do not silently
adopt one side: report the conflict and confirm consistency against the
DTO/controller for the wire and `Spec.md` for the rules.

## Identity and errors

| Method | Endpoint | Roles | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/me` | any authenticated | Login name and business roles |

Error body shape is `{ "code", "message", … }`. `401` = unauthenticated,
`403` = authenticated but forbidden, `404` = unknown case/resource, `409` =
stale/conflict/state/balance, `400` = validation, `413` = body over the
256 KiB cap, `503` = external purchasing timeout. Every request accepts an
optional `X-Trace-Id`; the effective id is echoed in the response and stored on
audit entries. The canonical JSON form applies to the persisted payloads whose
hash is computed (for example the evidence bundle, match result and review
snapshot payloads); it does not constrain the object key order of an HTTP
request body.

## Idempotency

Every core business command under `/api/invoice-cases/**` requires a
`requestId` (≤128 chars); the Mock ERP export and the signed result webhook are
machine-to-machine endpoints with their own keys (export idempotency key and
`provider + externalEventId`), not the actor-scoped `requestId`. The actor-scoped
key is `(scope, resource_key, actor, requestId)`. Repeating the same command with
the same payload **as the same principal** replays the stored response with no
second side effect; the same `requestId` with a different payload is an
actor-local `409` conflict. A different actor can never inherit a replay.

## Invoice case authoring (P1-03)

| Method | Endpoint | Roles | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/invoice-cases` | SUBMITTER | Create after validating the external purchase order |
| `GET` | `/api/invoice-cases/{id}` | owner SUBMITTER, APPROVER, OPERATOR | Case header, current OPEN draft and lines |
| `PUT` | `/api/invoice-cases/{id}/draft` | owner SUBMITTER | Atomically replace current draft lines (`expectedCaseVersion`) |
| `POST` | `/api/invoice-cases/{id}/submit` | owner SUBMITTER | Freeze the draft into the next immutable `EvidenceBundle` |
| `POST` | `/api/invoice-cases/{id}/revisions` | owner SUBMITTER | Open the next revision in `SUPPLEMENT_REQUIRED` |
| `GET` | `/api/invoice-cases/{id}/evidence-bundles[/{version}]` | owner SUBMITTER, APPROVER, OPERATOR | Frozen bundle versions and payload/hash |

## Deterministic matching (P1-04)

| Method | Endpoint | Roles | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/invoice-cases/{id}/match` | OPERATOR | Append a deterministic match result (requires a frozen bundle in `REVIEW_PENDING`) |
| `GET` | `/api/invoice-cases/{id}/match` | APPROVER, OPERATOR | Latest match result |
| `GET` | `/api/invoice-cases/{id}/matches` | APPROVER, OPERATOR | Append-only match history |

Canonical payload schema `match-result-v3`. Exception taxonomy:
`ITEM_UNCONFIRMED`, `EVIDENCE_INSUFFICIENT`, `QUANTITY_EXCEEDS_RECEIPT_BALANCE`,
`UNIT_PRICE_MISMATCH`, `DUPLICATE_INVOICE_SUSPECTED`. The expected allocation
plan is non-consuming.

## Review workflow (P1-05)

| Method | Endpoint | Roles | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/invoice-cases/{id}/review-snapshots` | APPROVER | Freeze the review subject (requires `expectedCaseVersion`) |
| `GET` | `/api/invoice-cases/{id}/review-snapshots[/latest]` | APPROVER, OPERATOR | Snapshot history / latest |
| `GET` | `/api/invoice-cases/{id}/review-snapshots/{number}/freshness` | APPROVER, OPERATOR | Current/stale with explicit reasons |
| `GET` | `/api/invoice-cases/{id}/review-decisions` | APPROVER, OPERATOR | Append-only decision history |
| `POST` | `/api/invoice-cases/{id}/mapping-decisions` | APPROVER | Confirm one line mapping and freeze a successor snapshot |
| `POST` | `/api/invoice-cases/{id}/supplement-requests` | APPROVER | Confirm a supplement request |
| `POST` | `/api/invoice-cases/{id}/reject` | APPROVER | Reject with a reason |

Human actions send `reviewSnapshotId`, `reviewPayloadHash`,
`expectedCaseVersion` and `requestId`. Stale reasons: `CASE_STATE`,
`CASE_VERSION`, `EVIDENCE_BUNDLE`, `MATCH_RESULT`, `MAPPING`,
`PURCHASING_SNAPSHOT`, `SUPERSEDED`. The reviewer actor is derived server-side;
a client `decidedBy` is ignored. Canonical payload schema `review-snapshot-v2`
for newly frozen snapshots; the existing `review-snapshot-v1` verification
algorithm is retained for already-frozen snapshots (see below).

## Atomic approval (P1-07)

| Method | Endpoint | Roles | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/invoice-cases/{id}/approve` | APPROVER, not the submitter | Approve the exact frozen snapshot |

Body: `{ requestId, expectedCaseVersion, reviewSnapshotId, reviewPayloadHash }`.
One transaction writes all `ReceiptAllocation` rows, one APPROVED
`ReviewDecision`, one `PaymentRequest`, the `APPROVE` audit and the case
transition to `EXPORT_PENDING`.

Approval conflicts are all `409` but mean different things, and the distinction
is part of the contract:

| Code | Meaning |
| --- | --- |
| `STALE_CASE_VERSION` | `expectedCaseVersion` is not the committed case version (carries `latestVersion`) |
| `STALE_REVIEW_TARGET` | the subject is well-formed but no longer current: superseded case/bundle/match/mapping/purchasing (carries explicit `reasons`) |
| `REVIEW_STATE_CONFLICT` | the **authoritative subject itself cannot be reconciled**: the stored snapshot payload/hash/relational sources disagree with the independent reconstruction, the source match result is missing, the purchasing source changed under the lock, or the snapshot `schemaVersion` is missing/unknown. A tampered or irreconcilable subject is rejected here, never approved |
| `INSUFFICIENT_RECEIPT_BALANCE` | the subject is current and consistent, but the shared receipt line no longer has balance (carries `shortfalls` with `confirmedQuantity`/`allocatedQuantity`/`remainingQuantity`/`requestedQuantity`) |
| `APPROVAL_NOT_PERMITTED` | the subject is current and internally consistent, but is not approvable as-is: the match is abnormal (any exception), or the allocation plan is incomplete / the total is inconsistent |

So "the subject is inconsistent" (`REVIEW_STATE_CONFLICT`) is separate from "the
subject is consistent but not approvable" (`APPROVAL_NOT_PERMITTED`).

### Review snapshot canonical version

Newly frozen snapshots are canonical `review-snapshot-v2`: every JSON object's
keys are recursively sorted and array order is preserved before hashing, so an
embedded match payload hashes equally whether it is held in memory or reloaded
from PostgreSQL `jsonb`.

Already-frozen `review-snapshot-v1` snapshots are **not rewritten**. Each
snapshot is verified with the single canonical algorithm named by its stored
`schemaVersion` — v1 legacy insertion order or v2 recursively sorted — with **no
fallback** from one algorithm to the other, and a missing or unknown
`schemaVersion` fails closed. Existing valid v1 snapshots still approve. A v1
snapshot whose stored payload cannot be reconciled under the v1 algorithm is
rejected `409 REVIEW_STATE_CONFLICT` with no side effect and must be re-frozen
as a new v2 snapshot; it is not silently upgraded or verified under v2.

## Payment export and Mock ERP (P1-08/P1-09)

| Method | Endpoint | Auth / required headers | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/payment-exports` (Mock ERP) | no auth (mock); requires the `Idempotency-Key` and `X-Payment-Request-Id` transport headers, which are **business identity, not authentication** | Idempotent export receipt |
| `GET` | `/api/payment-exports/{idempotencyKey}` (Mock ERP) | no auth (mock) | Status inquiry for the same key |
| `POST` | `/webhooks/mock-erp/payment-results` | shared-secret HMAC signature (the only authentication on this endpoint) | Signed external result (`ACKNOWLEDGED`/`FAILED`) |
| `GET` | `/api/invoice-cases/{id}/handoff` | HTTP Basic, APPROVER or OPERATOR | Payment and outbox delivery state |

The in-process relay is fail-closed by default (`PAYMENT_EXPORT_RELAY_ENABLED`).
`paymentStatus ∈ {NOT_SENT, SENDING, ACKNOWLEDGED, RETRY_SCHEDULED, FAILED,
RESULT_UNKNOWN}`; `outboxStatus ∈ {READY, CLAIMED, SENDING, DELIVERED, FAILED,
RESULT_UNKNOWN}`. `ACKNOWLEDGED`/`EXPORTED` means the ERP accepted the export,
**not** that funds moved. A timeout/transport failure is `RESULT_UNKNOWN` and is
never blind-resent; a signed result in the same key converges it exactly once.

## Workflow reads (P1-10)

| Method | Endpoint | Roles | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/invoice-cases` | SUBMITTER (own rows), APPROVER, OPERATOR | Server-paged/filtered work list |
| `GET` | `/api/invoice-cases/{id}/audit-entries?limit&cursor` | APPROVER, OPERATOR | Cursor-paged audit history |

List filters: `status`, `supplierId`, `purchaseOrderId`, `invoiceNumber`,
`submittedBy`, `submittedFrom`/`submittedTo` (inclusive ISO-8601), `page`,
`size` (≤100). The web app reaches these only through the same-origin
`/backend/...` allowlisted proxy; `CORE_API_URL` is a server-only variable.

## Phase 1 non-claims

There is no AI extraction, document upload/preview, object storage, RabbitMQ,
DLQ, automatic re-send/reconciliation or actual fund transfer in Phase 1. The
frozen interfaces and the phase mapping live in the Phase 2+ handoff section of
`Plan.md`.
