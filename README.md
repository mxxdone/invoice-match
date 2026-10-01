# Invoice Match

P1-00 provides a runnable baseline for the invoice matching project. It has one Java 21 Spring Boot 3 API, a Next.js TypeScript web app, PostgreSQL, and a minimal Mock ERP process. P1-01 adds the domain contract and PostgreSQL schema baseline (invoice case, draft revision, evidence bundle, match result, review snapshot and decision). P1-02 adds a read-only external purchasing system Mock (`mock-purchasing`), a read-only Core API adapter and a PostgreSQL-backed current snapshot of suppliers' purchase orders, lines, receipts and receipt lines with external versions. P1-03 adds the first business write APIs: manual invoice case creation validated against the external purchase order, atomic current-draft editing, submission that freezes a canonical hashed `EvidenceBundle` version, supplement revisions that copy the previous frozen lines, past bundle version reads and request-id idempotency. P1-04 adds the deterministic, AI-free 3-way match: the latest frozen bundle is compared with zero tolerance against the current purchasing snapshot and an immutable, canonically hashed `MatchResult` with per-line calculation evidence, an exception taxonomy and a non-consuming expected FIFO allocation plan is appended. P1-05 adds the human review workflow: a frozen `ReviewSnapshot` approval subject with a canonical payload hash, case-local item mapping with deterministic re-match and a successor snapshot, supplement request and rejection, machine-checkable freshness/staleness, and request-id idempotency. P1-06 adds role-based authorization for `SUBMITTER`, `APPROVER` and `OPERATOR` with local demo identities, an authoritative server-derived `submittedBy`/review actor (the client `decidedBy` is ignored), a request trace id, and an append-only, transactionally recorded audit history. P1-07 adds atomic approval: one APPROVER command consumes the exact frozen `ReviewSnapshot` into append-only `ReceiptAllocation` rows, one APPROVED `ReviewDecision`, one internal `PaymentRequest` and an `APPROVE` audit, all in one transaction with deterministic PostgreSQL row/advisory locking. P1-08 adds the minimal transactional Outbox and the in-process relay to Mock ERP. P1-09 adds the idempotent Mock ERP receiver (ACK by export key) and the signed result webhook that converges a lost or unknown response without creating a second payment. P1-10 adds the connected workflow screens (login, list, detail/comparison, authoring, review, approval, ERP hand-off and operations). P1-11 is the Phase 1 integration acceptance: reproducible isolated fixtures, end-to-end/concurrency/fault verification, the clean Compose smoke, this runbook and the Phase 2 input contract. AI document extraction, object storage and RabbitMQ/DLQ remain Phase 2; Phase 1 ships the full manual invoice → deterministic match → human review → atomic approval → Outbox → Mock ERP hand-off path.

## Documentation

`AGENTS.md` is the routing index. The current design and verification references are:

| Document | Contents |
| --- | --- |
| `docs/Spec.md` | Product scope, user scenarios, state model, invariants, transaction/concurrency and messaging design |
| `docs/Plan.md` | Ticket roadmap and acceptance criteria (P1-00 … P1-11) |
| `docs/ERD.md` | PostgreSQL schema map produced by migrations `V1`…`V9` |
| `docs/API.md` | Current HTTP API index, roles, idempotency and error contracts |
| `docs/Runbook.md` | 5–7 minute demo script and the reproducible verification/smoke commands |
| `docs/Phase2-Input-Contract.md` | The frozen Phase 1 interfaces that Phase 2 must build on |
| `docs/adr/` | Accepted architecture decisions (review subject, receipt facts, transactional outbox) |

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

All write endpoints require a `requestId`. The idempotency key is namespaced by
the authenticated principal (see P1-06 below). Repeating a request with the same
`requestId` and the same payload **as the same principal** replays the original
response without repeating the side effect; the same `requestId` with a
different payload is a `409` conflict (actor-local).

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/invoice-cases` | Create a case after validating the external purchase order, starting OPEN draft revision v1 |
| `GET` | `/api/invoice-cases/{id}` | Read the case header, current OPEN draft and its lines |
| `PUT` | `/api/invoice-cases/{id}/draft` | Atomically replace the current OPEN draft's lines (requires `expectedCaseVersion`) |
| `POST` | `/api/invoice-cases/{id}/submit` | Freeze the current draft into the next immutable `EvidenceBundle` version and move to `REVIEW_PENDING` |
| `POST` | `/api/invoice-cases/{id}/revisions` | In `SUPPLEMENT_REQUIRED`, open the next OPEN revision copied from the previous frozen bundle |
| `GET` | `/api/invoice-cases/{id}/evidence-bundles` | List frozen bundle versions with payload hashes |
| `GET` | `/api/invoice-cases/{id}/evidence-bundles/{version}` | Read one frozen bundle's canonical payload and hash |

Draft lines are `{lineNumber, rawItemName, quantity, unitPrice, confirmedItemId}`.
`lineNumber` must be contiguous from `1`, `rawItemName` non-blank, `quantity`
positive, `unitPrice` non-negative. When `confirmedItemId` is present it must be
the **item id** of an active line of the case's current purchase order snapshot;
purchase order line ids are a different namespace and are rejected.
`requestId` is limited to 128 characters.

Validation failures (`400`) are checked before the external purchase order is
called. Stale versions, non-editable drafts, invalid state transitions and
idempotency conflicts return `409`; a missing external purchase order returns
`404`; an external purchasing timeout returns `503`.

## Deterministic matching API (P1-04)

Run matching for the latest frozen bundle of a case against the current active
purchasing snapshot. `POST` appends a new immutable `MatchResult` (it never
updates one); reusing a `requestId` replays the stored response, and a new
`requestId` for the same bundle appends another record with the same payload and
hash. Allowed tolerance is exactly zero, KRW and quantities stay integers, and
no `ReceiptAllocation` is created or receipt balance consumed in this ticket.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/invoice-cases/{id}/match` | Append a match result for the latest frozen bundle (requires `requestId`) |
| `GET` | `/api/invoice-cases/{id}/match` | Read the latest match result |
| `GET` | `/api/invoice-cases/{id}/matches` | Read the append-only match result history, oldest first |

A match only runs when the case is `REVIEW_PENDING` and has a frozen bundle;
`SUPPLEMENT_REQUIRED` is rejected with `409 MATCH_STATE_CONFLICT` because its
frozen evidence is already known to be stale and awaiting correction (as is any
other state). An unknown case is `404`, and `GET` of the latest result before any
match (or of an unknown case) is `404`.

The response contains `id`, `invoiceCaseId`, `evidenceBundleId`, `resultNumber`,
`resultHash`, `createdAt` and the canonical `payload` object. `resultNumber` is a
per-case monotonic append number enforced by the database; latest and history
read by it, so equal timestamps or concurrent requests cannot reorder results.
The payload records the bundle id/version/hash, the purchasing snapshot
version/payload hash and receipt versions, the input invoice values, the compared
purchase order values, the confirmed/available receipt values, the
`expectedAllocationPlan` and every exception with machine-readable values, so a
person can recompute the result.

| Exception type | Meaning |
| --- | --- |
| `ITEM_UNCONFIRMED` | `confirmedItemId` is null/blank; no mapping is invented |
| `EVIDENCE_INSUFFICIENT` | the confirmed item matches zero or several active purchase order lines, so price/receipt basis is not uniquely supportable |
| `QUANTITY_EXCEEDS_RECEIPT_BALANCE` | invoice quantity is above the confirmed receipt quantity available for planning |
| `UNIT_PRICE_MISMATCH` | invoice unit price differs from the matched purchase order line unit price |
| `DUPLICATE_INVOICE_SUSPECTED` | another case has the same supplier and normalized invoice number; storage is not rejected |

Several exceptions can coexist, both across lines and on one line. `normal` is
true only when there is no exception and every line's expected plan covers its
full invoice quantity.

Determinism: the payload and its SHA-256 are computed from semantic inputs only
and order every collection explicitly (invoice lines by line number, purchase
order lines by external id, receipts by receipt id, receipt lines by
`receiptDate` then external receipt line id then receipt id, exceptions by line
number then type then values). The same inputs always produce the same hash
regardless of repository or list ordering; the result id and creation time are
not part of the hash. Invoice lines are processed in line-number order against
one shared remaining-receipt ledger keyed by structured receipt identity, so
several lines mapped to the same purchase order line cannot overbook it, and
aggregate availability uses `long` so summing `Integer.MAX_VALUE` receipt lines
cannot wrap.

The `expectedAllocationPlan` is an expected, non-consuming FIFO plan
(`allocationPlan.consuming = false`, `allocationPlan.mode =
NON_CONSUMING_EXPECTED_PLAN_V1`). P1-04 writes no allocation and consumes no
balance; approval (P1-07) revalidates and consumes it against the then-current
balance. Business duplicate detection is separate from request-id idempotency.

The canonical match payload schema is `match-result-v3`; the `appliedMappings`
array records which effective case-local mappings were applied, each with the
exact purchase order line the human chose.

## Review workflow API (P1-05)

`ReviewSnapshot` is the frozen approval subject from ADR 0001: exactly what a
reviewer is shown. It contains the case version, the evidence bundle
id/version/hash, the source match result id/number/hash with its full
calculation evidence, the effective case-local mappings, the invoice line
amounts and total KRW amount, the captured purchasing snapshot
versions/hash, and a canonical payload hash. Snapshots are append-only and
carry an unambiguous per-case monotonic `snapshotNumber`; decisions carry a
per-case monotonic `decisionNumber`.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/invoice-cases/{id}/review-snapshots` | Freeze the current review subject from the latest match result (requires `requestId`, `expectedCaseVersion`) |
| `GET` | `/api/invoice-cases/{id}/review-snapshots` | Snapshot history, oldest first |
| `GET` | `/api/invoice-cases/{id}/review-snapshots/latest` | Latest snapshot |
| `GET` | `/api/invoice-cases/{id}/review-snapshots/{number}` | One snapshot by business order |
| `GET` | `/api/invoice-cases/{id}/review-snapshots/{number}/freshness` | Machine-checkable freshness with explicit stale reasons |
| `GET` | `/api/invoice-cases/{id}/review-decisions` | Append-only decision history, oldest first |
| `POST` | `/api/invoice-cases/{id}/mapping-decisions` | Confirm an item mapping for one line of the snapshot the reviewer saw |
| `POST` | `/api/invoice-cases/{id}/supplement-requests` | Confirm a supplement request |
| `POST` | `/api/invoice-cases/{id}/reject` | Reject with a reason |

A snapshot can only be frozen from a `REVIEW_PENDING` case, the latest frozen
bundle, the latest match result for that bundle, and only when that result was
computed against the current purchasing snapshot and the current effective
mappings; otherwise the request is a `409`. Freezing does not change the case
version. Human actions must send `reviewSnapshotId`, `reviewPayloadHash`,
`expectedCaseVersion` and `requestId`; a stale, superseded or mismatched target
is rejected with `409` and no side effect. The `STALE_REVIEW_TARGET` body lists
explicit reasons (`CASE_STATE`, `CASE_VERSION`, `EVIDENCE_BUNDLE`, `MATCH_RESULT`,
`MAPPING`, `PURCHASING_SNAPSHOT`, `SUPERSEDED`).

A mapping is scoped to the case and to a specific bundle and invoice line:
`{requestId, expectedCaseVersion, reviewSnapshotId, reviewPayloadHash,
lineNumber, itemId, decidedBy?}`. The chosen `itemId` must resolve to exactly
one active purchase order line of the current purchase order, and that exact
line is stored with the decision. Re-matching uses the stored purchase order
line rather than re-resolving the item, so a later purchasing refresh that moves
the item to a different line can never silently retarget the mapping; if the
chosen line is no longer active the line is reported as insufficient evidence
and needs a new mapping decision. Recording a mapping bumps the case version,
re-matches with every effective mapping, and freezes a successor snapshot whose
payload records the applied mappings and the new result. Prior snapshots and
bundles are preserved and become stale; a new bundle never silently inherits an
old bundle's mappings. Supplement and reject require a non-blank reason (max
1000 characters) and atomically move the case to `SUPPLEMENT_REQUIRED` or
`REJECTED`. `SUPPLEMENT_REQUIRED` permits neither a new match nor a new snapshot
until corrected evidence vNext is submitted and matched. Reusing a `requestId`
with the same payload replays the response; a different payload is a `409`
conflict. Amounts use checked arithmetic, so an overflowing line total or
snapshot total is rejected atomically with `400 NUMERIC_OVERFLOW`.

Review writes take the invoice case row lock and then the same PostgreSQL
advisory lock the purchasing snapshot refresh uses, and hold that advisory lock
from the final currentness validation to commit. A concurrent refresh therefore
cannot change the purchasing snapshot between the validation and the committed
decision or snapshot. The lock order is always invoice case first, then purchase
order, and no writer takes them in the opposite order.

`decidedBy` from P1-05 is accepted only for backward compatibility and is
**ignored**; the recorded reviewer is the authenticated principal. It is never
an authorization, self-approval or audit source.

The canonical snapshot payload sorts every collection and excludes the
snapshot's generated identity/time and decision ids from its SHA-256, so the
same semantic inputs hash equally regardless of repository or list ordering.

## Roles, identity and audit (P1-06)

Every `/api/**` route requires authentication; the actuator health probes stay
public. **Unauthenticated protected access is `401` and an authenticated but
forbidden action is `403`** (both use the shared `{code,message}` error body).
Spring Security HTTP Basic is used with local demo identities. The default
deployable configuration ships **no** credentials and fails closed (every
protected call is `401`); the demo identities exist only in the `local` profile
(activated by `docker compose`, or `SPRING_PROFILES_ACTIVE=local` /
`--spring.profiles.active=local`) and in the test profile:

| Username | Password | Role |
| --- | --- | --- |
| `submitter` | `submitter-pass` | `SUBMITTER` |
| `submitter2` | `submitter2-pass` | `SUBMITTER` |
| `approver` | `approver-pass` | `APPROVER` |
| `operator` | `operator-pass` | `OPERATOR` |

Passwords are stored as BCrypt hashes, never plaintext, and are demo-only. A
production deployment replaces the demo `UserDetailsService` with a real
IdP/OAuth resource server.

Authorization matrix (least privilege; forbidden rows are `403`). Controller
checks give early rejection, and **every write service re-checks the role and
ownership after taking the authoritative invoice case row lock and before any
mutation or audit**; creation enforces `SUBMITTER` inside the service/transaction
boundary:

| Action | Endpoint | SUBMITTER | APPROVER | OPERATOR |
| --- | --- | --- | --- | --- |
| Create case | `POST /api/invoice-cases` | any | — | — |
| Read case / evidence bundles | `GET /api/invoice-cases/{id}`, `.../evidence-bundles*` | own only | any | any |
| Replace draft / submit / open revision | `PUT /{id}/draft`, `POST /{id}/submit`, `POST /{id}/revisions` | own only | — | — |
| Freeze snapshot / mapping / supplement / reject | `POST /{id}/review-snapshots`, `.../mapping-decisions`, `.../supplement-requests`, `.../reject` | — | any | — |
| Approve (allocate + payment request) | `POST /{id}/approve` | — | any (not the submitter) | — |
| Read review snapshots / decisions | `GET /{id}/review-snapshots*`, `.../review-decisions` | — | any | any |
| Run deterministic match / reprocess | `POST /{id}/match` | — | — | any |
| Read match results | `GET /{id}/match`, `.../matches` | — | any | any |
| Read audit history | `GET /{id}/audit-entries` | — | any | any |

"own only" means the authenticated username equals the case's authoritative
`submittedBy`. `submittedBy` is stored from the authentication at creation,
is **immutable at the database** after insert, and is never taken from the
request body; pre-P1-06 rows are backfilled with the reserved sentinel
`__reserved__`, which no configured login may use. A request `decidedBy` cannot
change the stored actor. The reusable P1-07 approval policy, `APPROVER` and
not the case submitter, takes the **authoritative locked `InvoiceCase`**, never a
caller-supplied string; P1-06 exposes no approval endpoint.

Idempotency is namespaced by the authenticated principal: the request key is
`(scope, resource, actor, requestId)`. One principal can never replay or read
another principal's stored response (including `create` with resource `NEW`,
and review/match writes), while a replay by the same principal still works and a
same-request-id different-payload conflict is actor-local.

Every audited write appends one row to `audit_entry` in the **same transaction**
as the business mutation, so an audit failure rolls the mutation back. Audited
actions: `CASE_CREATED`, `DRAFT_LINES_REPLACED`, `CASE_SUBMITTED`,
`SUPPLEMENT_REVISION_OPENED`, `MATCH_RUN`, `REVIEW_SNAPSHOT_FROZEN`,
`ITEM_MAPPED`, `SUPPLEMENT_REQUESTED`, `CASE_REJECTED`, `APPROVE`. Each entry
stores the actor, roles, action,
case/target, case business version, structured before/after change, `requestId`
and `traceId`. Credentials, `Authorization` headers and raw documents are never
stored, and an idempotent replay records no second entry. A mapping replacement
records the exact previous mapping in `before` and the new mapping in `after`,
plus the resulting match result id/number/hash and successor snapshot number.
A mapping also performs an internal deterministic re-match; it runs inside the
same authorized, case-locked, idempotent mapping transaction via a
**package-private `review.application` collaborator**, and its result is covered
by the atomic `ITEM_MAPPED` audit rather than a standalone `MATCH_RUN`. There is
no public raw "append a match result" seam: the pure `MatchResultPlanner` cannot
persist, `MatchingService.appendResult` is private, and match_result persistence
is reachable only through the secured/idempotent OPERATOR `MatchingService.run`
or the complete public `ReviewService.recordMapping` orchestration.
`audit_entry` rejects `UPDATE` and `DELETE`.

Audit before/after summaries are canonical (object/map keys sorted, array order
preserved) and bounded by **UTF-8 byte length** (64 KiB), not character count.
Line diffs store a bounded item-name preview plus its length and SHA-256 rather
than the full 500-character name. A DTO-valid request (at most 100 lines)
therefore always audits successfully with meaningful, deterministic content; a
summary that somehow exceeds the limit is replaced by a
`{"truncated":true,"originalBytes":...,"sha256":...}` envelope (hashed over the
canonical form) instead of throwing, so the audit is never dropped, never
partial and never a 500. The audit trigger reads the case version `FOR SHARE`,
so a concurrent privileged raw-SQL version change cannot make a just-inserted
audit immediately stale.

The database validates the semantic relationships the app asserts (typed target
exists and belongs to the case, `CASE` target equals the case, actor roles are a
non-empty canonical subset, and the recorded business version matches the case
version in the same transaction). It does **not** verify the actor's identity:
authentication is a server-side application trust boundary.

`GET /api/invoice-cases/{id}/audit-entries?limit=20&cursor=...` returns a
newest-first page ordered by `(occurred_at DESC, id DESC)` plus a `nextCursor`.
The cursor is opaque; an unreadable cursor is `400`. The query is scoped to the
one case, so it never leaks another case's events.

Every request accepts an optional `X-Trace-Id` header. A bounded token of safe
characters is kept, anything oversized or containing control characters is
replaced with a generated `trc-...` id, and the effective id is returned in the
`X-Trace-Id` response header and stored on audit entries (also on `401`/`403`
responses). The trace id is propagated correlation metadata only: it never
participates in authentication, authorization, request-idempotency uniqueness or
approval identity, and for an APPROVED decision it only has to match the
`APPROVE` audit that records the same approval.

Request limits keep resources bounded: a JSON request body is capped at
`http.request.max-body-bytes` (default 256 KiB, `HTTP_MAX_BODY_BYTES` to change)
and an oversize body is rejected with `413` **before** any transaction, and a
draft may have at most 100 lines (a larger list is `400`). The body cap is
enforced by reading the stream, so chunked requests without `Content-Length` are
bounded too.

## Atomic approval API (P1-07)

Approval consumes the exact review snapshot the approver was shown into the
local allocation ledger, one APPROVED decision, one internal payment request and
one case transition, in a single transaction. There is no partial approval.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/invoice-cases/{id}/approve` | Approve the exact review snapshot (requires `requestId`, `expectedCaseVersion`, `reviewSnapshotId`, `reviewPayloadHash`) |

The body carries no actor and no amounts: the actor is the authenticated
`APPROVER` and the allocation/amount come from the frozen snapshot. The external
purchasing aggregate is fetched and validated **before** the approval
transaction opens, then applied atomically inside it, so no HTTP call is ever
made while a transaction or row/advisory lock is held. The transaction lock
order is: authoritative invoice case → APPROVER and actor ≠ immutable
`submittedBy` → actor-scoped request id → the shared purchasing advisory lock →
apply the prepared snapshot → revalidate case state/version, the exact snapshot
id/hash, evidence bundle, match result, mapping watermark and purchasing
currentness → lock every referenced active receipt line in the deterministic
order (receipt date, external receipt line id, receipt id, stable UUID) →
recompute `remaining = confirmed − committed` → write all allocations, the
decision, the payment request and the audit, then move to `EXPORT_PENDING`.

`ReceiptAllocation` is append-only (`UPDATE`/`DELETE` rejected), references the
exact case, approved decision, snapshot/evidence bundle and receipt line with
same-case composite foreign keys, and a database trigger locks the receipt line
and rejects any insert that would exceed the externally confirmed quantity. The
stored snapshot/match/bundle JSON is never trusted: the approval transaction
independently rebuilds the canonical evidence payload from the authoritative
sealed draft lines, reruns the deterministic match over the current purchasing
facts and effective mappings, and rebuilds the canonical review snapshot with
the existing payload builder, comparing hash and canonical payload before
deriving the typed allocation plan and amount (checked arithmetic). A receipt
allocation INSERT is additionally guarded by a trigger proving the decision is
APPROVED and belongs to the exact case/snapshot/hash, the invoice line belongs
to the bundle's sealed draft, and the receipt line matches the same purchase
order, stored external ids, version and active state, with a unique key per
decision/line/receipt. `PaymentRequest` is created exactly once per approval with
the deterministic global key `PAYMENT:{caseId}:{snapshotId}`, the frozen KRW
amount (positive), and a DB check, a composite FK binding the exact APPROVED
decision subject and approved amount/currency, and an immutability trigger that
allows only delivery-status changes (P1-08) and rejects DELETE. P1-08 adds the
Outbox and ERP relay; this ticket creates only the internal `PENDING` record.

A shared receipt line is the concurrency boundary: with remaining 60 and two
concurrent approvals of 40 each, exactly one succeeds and the loser gets `409`
`INSUFFICIENT_RECEIPT_BALANCE` with the current `confirmedQuantity`,
`allocatedQuantity`, `remainingQuantity` and `requestedQuantity` per line, and no
decision/allocation/payment/audit is written. A stale, superseded or mismatched
subject returns `409` (`STALE_CASE_VERSION`, `STALE_REVIEW_TARGET` with explicit
reasons, or `REVIEW_STATE_CONFLICT`); a current snapshot with an abnormal match,
an incomplete plan or an inconsistent total returns `409`
`APPROVAL_NOT_PERMITTED`. Reusing a `requestId` with the same payload and actor
replays the stored response without an external call or new effects; a different
payload is an actor-local `409` conflict, and a different actor cannot inherit
another actor's replay.

OpenAPI-style example:

```sh
curl -u submitter:submitter-pass -H 'X-Trace-Id: demo-1' \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"c-1","supplierId":"SUP-1","purchaseOrderId":"PO-1001","invoiceNumber":"INV-1"}' \
  http://localhost:8080/api/invoice-cases
```

Check PostgreSQL connectivity and its timezone:

```sh
docker compose exec postgres psql -U invoice_match -d invoice_match -c "SELECT 1, current_setting('TimeZone');"
```

Stop the services with `docker compose down`. This preserves the named PostgreSQL volume. To remove local database data deliberately, use `docker compose down -v`.

## Workflow read APIs (P1-10)

These read endpoints let the minimum work screen render login, the case list, the
case detail and the ERP hand-off state without re-deriving any server fact. They
are additive: every P1-03..P1-09 write contract is unchanged.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/api/me` | The authenticated login name and its business roles |
| `GET` | `/api/invoice-cases` | Server-paged, filtered work list |
| `GET` | `/api/invoice-cases/{id}/handoff` | Payment/outbox export status for an approved case |

`GET /api/me` returns `{ "username": "...", "roles": ["APPROVER"] }` derived
server-side from the security context. It uses the existing HTTP Basic identities
and introduces no new authentication scheme.

`GET /api/invoice-cases` accepts optional query parameters `status`
(a `DRAFT|SUBMITTED|REVIEW_PENDING|SUPPLEMENT_REQUIRED|REJECTED|EXPORT_PENDING|EXPORTED`
name), `supplierId`, `purchaseOrderId`, `invoiceNumber` (matched on the
normalized supplier invoice number, so `INV-2026-001` and `inv 2026 001` are
equal), `submittedBy`, the inclusive ISO-8601 instants `submittedFrom` /
`submittedTo`, and the server-side paging parameters `page` (0-based, default 0)
and `size` (default 20, capped at 100). The response is:

```json
{
  "items": [
    { "id": "…", "supplierId": "SUP-1", "purchaseOrderId": "PO-1001",
      "invoiceNumber": "INV-1", "submittedBy": "submitter", "status": "REVIEW_PENDING",
      "version": 4, "createdAt": "…", "updatedAt": "…", "submittedAt": "…" }
  ],
  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1, "hasNext": false
}
```

The list is ordered deterministically by `(createdAt DESC, id DESC)`, uses a
single DTO-projection query plus a count query (no entity graph, no N+1), and is
row-scoped server-side: a `SUBMITTER` only ever sees cases whose `submittedBy`
is their own login (a `submittedBy` filter cannot widen that), while
`APPROVER`/`OPERATOR` see every case.

`GET /api/invoice-cases/{id}/handoff` returns the case status/version plus the
approved payment and its outbox delivery state, or `"payment": null` before the
case has been approved:

```json
{
  "invoiceCaseId": "…", "caseStatus": "EXPORT_PENDING", "caseVersion": 5,
  "payment": {
    "paymentRequestId": "…", "externalRequestKey": "PAYMENT:{caseId}:{snapshotId}",
    "amount": 150000, "currency": "KRW", "exportVersion": 1,
    "paymentStatus": "NOT_SENT", "outboxStatus": "READY", "attemptCount": 0,
    "lastErrorCode": null, "nextAttemptAt": null, "deliveredAt": null, "createdAt": "…"
  }
}
```

`paymentStatus` is one of `NOT_SENT`, `SENDING`, `ACKNOWLEDGED`,
`RETRY_SCHEDULED`, `FAILED`, `RESULT_UNKNOWN`; `outboxStatus` is one of `READY`,
`CLAIMED`, `SENDING`, `DELIVERED`, `FAILED`, `RESULT_UNKNOWN`. Both are returned
verbatim so the UI displays the server state instead of recomputing it. A case
read that requires access to another user's case is `403`, and an unknown case is
`404`.

### Stale-conflict error contract

A write that loses an optimistic race returns `409` and now carries the latest
server identifier so the browser can refetch instead of guessing. A stale case
version returns `STALE_CASE_VERSION` with the current committed version:

```json
{ "code": "STALE_CASE_VERSION", "message": "…", "caseId": "…",
  "expectedVersion": 3, "latestVersion": 5 }
```

A human action against a superseded review subject returns `STALE_REVIEW_TARGET`
with the explicit reasons (`CASE_STATE`, `CASE_VERSION`, `EVIDENCE_BUNDLE`,
`MATCH_RESULT`, `MAPPING`, `PURCHASING_SNAPSHOT`, `SUPERSEDED`) and the committed
case version/status to re-read:

```json
{ "code": "STALE_REVIEW_TARGET", "message": "…",
  "reasons": ["MATCH_RESULT"], "currentCaseVersion": 5, "currentCaseStatus": "REVIEW_PENDING" }
```

`401` (unauthenticated) and `403` (forbidden) continue to use the shared
`{code,message}` body.

### Web login and case list (first connected slice)

The web app connects the first slices: `/login` calls `GET /api/me`, the
`/cases` list calls `GET /api/invoice-cases`, and `/cases/[id]` reads one case
detail with its evidence, match, review and hand-off state. All calls go through
the same-origin `/backend/...` proxy, so the browser never makes a cross-origin
request. The proxy target is the server-only `CORE_API_URL` (default
`http://localhost:8080`; `docker compose` sets `http://core-api:8080`) and the
path is a fixed allowlist: `api/me`, `api/invoice-cases`, and the read
subresources of one case (`/{id}`, `/evidence-bundles[/{version}]`, `/match`,
`/review-snapshots/latest`, `/review-snapshots/{number}/freshness`,
`/review-decisions`, `/audit-entries`, `/handoff`). The case id must be a UUID
and a version/number a small positive integer, so the route cannot be turned
into an open proxy and no user input selects the target. `CORE_API_URL` is never
a `NEXT_PUBLIC_` variable.

The live detail screen is read-only. It shows only values the read APIs return
(no invented supplier name, total, receipt stock, mapping candidate or
freshness verdict), and it does not request the reviewer-only sections for a
`SUBMITTER`; those show a `403`-style notice instead. A case `404` is shown as a
missing case, while a missing match result or review snapshot is shown as an
empty section. Approve, mapping, supplement, reject and create writes are not
connected in this slice: their buttons are disabled and explain that no request
is sent. The audit tab follows the server `nextCursor` with a "이전 기록 더 보기"
button. The `/cases` list keeps its filters in the URL, so opening a detail and
returning restores the same query.

HTTP Basic credentials are kept only in React memory for the tab. They are sent
as an `Authorization` header on each proxied request and are never written to
`sessionStorage`/`localStorage`/IndexedDB, the URL, logs or error output.
Reloading the page or signing out drops them, so the next visit requires signing
in again. The backend still owns identity, roles and the list row scope; the UI
never re-decides them. HTTP Basic is only acceptable on `localhost`: any
non-local deployment must terminate TLS (HTTPS) in front of the web app and the
Core API.

The `/cases/[id]` detail and `/cases` list are live reads. The `/`, `/cases/new`,
`/handoff` and `/operations` screens are still design-only previews, and the
write actions are not connected. The `/cases` list shows only the server list
summary; the supplier name is not part of the list DTO, so a row shows the raw
`supplierId` and the account `submittedBy` instead of an invented name.

### Web case detail (second connected slice)

`/cases/[id]` is a read-only live detail for a real case id reached from a list
row or summary. It reads `GET /api/invoice-cases/{id}`, the evidence bundle list
and (only when a submitted case has no OPEN draft) the latest sealed bundle
payload, `GET /api/invoice-cases/{id}/handoff`, and, for `APPROVER`/`OPERATOR`,
the latest match (`.../match`), the latest review snapshot
(`.../review-snapshots/latest`), its freshness
(`.../review-snapshots/{number}/freshness`), the decision history
(`.../review-decisions`) and the cursor-paged audit history
(`.../audit-entries?limit=20&cursor=...`). The proxy allowlist accepts only these
read paths with a validated UUID case id and positive integer version/number;
every write path stays `404` at the proxy.

A `SUBMITTER` only receives their own case (the server enforces ownership), and
the reviewer-only sections are not requested at all; they render a permission
notice. A missing case is a `404` page; a missing match result or review
snapshot is an empty section, not an error. A `503` or `403` on a section is
shown as that section's own error/permission state, never as "no data" or "not
yet approved". The comparison table renders the server match `lineOutcomes`
verbatim (invoice/PO quantity and unit price, available confirmed receipt
quantity, expected allocation and the exception taxonomy); no total, supplier
name or receipt balance is invented. The comparison states which evidence
bundle version it was computed from; if the case has since been resubmitted, it
is labelled an older result (`이전 제출자료 기준 비교 결과`) and the server's
normal verdict is shown as the verdict at that time, not the current one.
Freshness is the server `current`/`reasons` value, shown on the comparison and
decision tabs, and version/hash identifiers are under a technical disclosure.
The audit tab follows the server `nextCursor`; a failed further page keeps the
records and cursor and shows a scoped error with a retry. Approve, mapping,
supplement, reject and create writes are out of scope for this slice: the
buttons are disabled and say no request is sent.

Known boundary: monetary fields are Java `long`. A value above JavaScript's safe
integer range cannot be held exactly, so the screen marks it as outside the
supported exact range instead of formatting it as an exact amount. An exact
integer/string money contract would be a backend change and needs Head
sign-off; no backend type was changed here.

## Work on a service locally

With PostgreSQL running (`docker compose up -d postgres`), set `DB_PASSWORD` to the same value as `POSTGRES_PASSWORD` in your private `.env`, then run (the `local` profile activates the demo identities; without it the API fails closed):

```sh
cd core-api
./gradlew bootRun --args='--spring.profiles.active=local'
```

The Gradle wrapper requires Java 21; no global Gradle install is needed. On Windows PowerShell, use `.\gradlew.bat bootRun`.

For web development, use Node.js 24 and npm:

```sh
cd web
npm ci
npm run dev
```

For local web development outside Compose, the default `CORE_API_URL` already
points at `http://localhost:8080`; set it only when the Core API runs elsewhere.
Sign in at `/login` with a local demo identity from the table above (for example
`approver` / `approver-pass`).

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

The first live web slice (login + case list) has a self-contained smoke script.
It starts throwaway resources only (an ephemeral PostgreSQL container plus the
built core-api and web), records and owns their PIDs/container id, waits for
child-aware readiness with a finite timeout, runs the read-API checks, and
always cleans up only what it created. It never stops an existing container and
never logs credentials:

```sh
cd core-api && ./gradlew bootJar
cd ../web && npm ci && npm run build
cd ..
node scripts/verify-p1-10.mjs
```

Add `--browser` to also drive a real Chromium against the same throwaway stack
inside the verification callback (the stack stays up until the callback returns
and the existing cleanup then stops it). It uses `npx @playwright/cli
playwright-cli`, needs a local Chromium, adds no credentials to disk, and writes
screenshots to `output/playwright/p1-10-complete-workflow/`:

```sh
node scripts/verify-p1-10.mjs --browser
```

The browser pass covers login → create → draft → submit → operator match →
approver freeze/approve → handoff, exception mapping, supplement → resubmit v2,
reject, the server self-approval denial (a verification-only dual-role identity
is injected into the throwaway child's `SPRING_APPLICATION_JSON`), stale
blocking, list return, console and a 1024px viewport overflow check.

The Phase 1 integration acceptance is a separate self-contained script. It hosts
the committed `mock-erp` module in-process (so `dropResponse`/response-delay can
be driven deterministically), starts the same throwaway PostgreSQL + purchasing
+ core-api + web stack with the payment relay enabled, and asserts the persisted
decision/allocation/payment/outbox/webhook/audit rows plus the Mock ERP record
count over real HTTP. It covers normal approval, 60/100 supplement → corrected
resubmission, unit-price difference, item mapping, invoice-number duplicate,
insufficient evidence, a signed result that disagrees with the export, a signed
result that resolves an in-flight `SENDING` send, lost-response
`RESULT_UNKNOWN` → explicit status inquiry → signed-result convergence, `FAILED`
vs `RESULT_UNKNOWN`, and a repeated real-PostgreSQL 40+40 shared-receipt
contention. It writes a sanitized `output/p1-11/evidence.json`:

```sh
cd core-api && ./gradlew bootJar
cd ../web && npm ci && npm run build
cd ..
node scripts/verify-p1-11.mjs --browser
```

`--browser` drives a real Chromium and covers the actual hand-off states
`ACKNOWLEDGED/DELIVERED/EXPORTED`, `FAILED` and `RESULT_UNKNOWN` (not only
`NOT_SENT`), writing `output/playwright/p1-11-phase-one/`.

The clean five-service Compose smoke is also a script. It brings the stack up as
a uniquely named project on freshly chosen isolated ports with generated
test-only credentials, waits for every healthcheck, runs one full
create → submit → match → freeze → approve → Outbox → Mock ERP workflow over
HTTP, asserts the Mock ERP holds one logical record, and then removes only that
project's resources (`down -v` scoped to the project). It never runs a bare
`docker compose down` and never touches an existing stack:

```sh
node scripts/compose-smoke-p1-11.mjs
```

The 5–7 minute demo script and the full command list are in `docs/Runbook.md`.

The GitHub Actions workflow runs these checks and a five-service Compose smoke test. Source layout is intentionally small: `core-api` holds one Spring application organized by feature (`invoicecase`, `matching`, `purchasingreference`, `review`, `shared`); `web/src/app` holds the Next.js routes; `mock-erp` serves only a deterministic health response; `mock-purchasing` serves deterministic read-only purchase order aggregates. P1-01 defines the Phase 1 state contract and PostgreSQL baseline, P1-02 defines the external purchasing reference snapshot and refresh version semantics, P1-03 defines manual submission, evidence bundle versioning and request-id idempotency, P1-04 defines the deterministic AI-free 3-way match, P1-05 defines the frozen review snapshot, case-local mapping with deterministic re-match, supplement/reject and freshness, P1-06 defines role-based authorization, the authoritative submitter/reviewer identity, request trace ids and the append-only audit history, P1-07 defines atomic approval, allocation and the internal payment request, P1-08 defines the transactional Outbox and in-process relay, P1-09 defines the idempotent Mock ERP receiver and signed result webhook, and P1-10 connects the workflow screens. AI document processing, object storage and RabbitMQ/DLQ are explicitly Phase 2 (`docs/Phase2-Input-Contract.md`).
