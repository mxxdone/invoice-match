# API — current Phase 1 contract

All application endpoints are under `/api/**` and require HTTP Basic
authentication (demo identities exist only in the `local`/`test` profiles; the
deployable default fails closed). The actuator health probes and the Mock ERP
webhook are public. This index is current as of P1-11; the ticket-level detail
lives in `README.md`.

## Identity and errors

| Method | Endpoint | Roles | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/me` | any authenticated | Login name and business roles |

Error body shape is `{ "code", "message", … }`. `401` = unauthenticated,
`403` = authenticated but forbidden, `404` = unknown case/resource, `409` =
stale/conflict/state/balance, `400` = validation, `413` = body over the
256 KiB cap, `503` = external purchasing timeout. Every request accepts an
optional `X-Trace-Id`; the effective id is echoed in the response and stored on
audit entries. Bodies are canonical JSON.

## Idempotency

Every write requires a `requestId` (≤128 chars). The key is namespaced by the
authenticated principal: `(scope, resource_key, actor, requestId)`. Repeating the
same request with the same payload **as the same principal** replays the stored
response with no second side effect; the same `requestId` with a different
payload is an actor-local `409` conflict. A different actor can never inherit a
replay.

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
a client `decidedBy` is ignored. Canonical payload schema `review-snapshot-v1`.

## Atomic approval (P1-07)

| Method | Endpoint | Roles | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/invoice-cases/{id}/approve` | APPROVER, not the submitter | Approve the exact frozen snapshot |

Body: `{ requestId, expectedCaseVersion, reviewSnapshotId, reviewPayloadHash }`.
One transaction writes all `ReceiptAllocation` rows, one APPROVED
`ReviewDecision`, one `PaymentRequest`, the `APPROVE` audit and the case
transition to `EXPORT_PENDING`. A shared receipt shortfall is `409`
`INSUFFICIENT_RECEIPT_BALANCE` with `confirmedQuantity`/`allocatedQuantity`/
`remainingQuantity`/`requestedQuantity`; an abnormal or inconsistent subject is
`409` `APPROVAL_NOT_PERMITTED`.

## Payment export and Mock ERP (P1-08/P1-09)

| Method | Endpoint | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/payment-exports` (Mock ERP) | `Idempotency-Key` + `X-Payment-Request-Id` | Idempotent export receipt |
| `GET` | `/api/payment-exports/{idempotencyKey}` (Mock ERP) | none (mock) | Status inquiry for the same key |
| `POST` | `/webhooks/mock-erp/payment-results` | HMAC signature | Signed external result (`ACKNOWLEDGED`/`FAILED`) |
| `GET` | `/api/invoice-cases/{id}/handoff` | APPROVER, OPERATOR | Payment and outbox delivery state |

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
DLQ, automatic re-send/reconciliation or actual fund transfer in Phase 1. See
`docs/Phase2-Input-Contract.md` for the frozen interfaces Phase 2 builds on.
