# Invoice Match

P1-00 provides a runnable baseline for the invoice matching project. It has one Java 21 Spring Boot 3 API, a Next.js TypeScript web app, PostgreSQL, and a minimal Mock ERP process. P1-01 adds the domain contract and PostgreSQL schema baseline (invoice case, draft revision, evidence bundle, match result, review snapshot and decision). P1-02 adds a read-only external purchasing system Mock (`mock-purchasing`), a read-only Core API adapter and a PostgreSQL-backed current snapshot of suppliers' purchase orders, lines, receipts and receipt lines with external versions. P1-03 adds the first business write APIs: manual invoice case creation validated against the external purchase order, atomic current-draft editing, submission that freezes a canonical hashed `EvidenceBundle` version, supplement revisions that copy the previous frozen lines, past bundle version reads and request-id idempotency. P1-04 adds the deterministic, AI-free 3-way match: the latest frozen bundle is compared with zero tolerance against the current purchasing snapshot and an immutable, canonically hashed `MatchResult` with per-line calculation evidence, an exception taxonomy and a non-consuming expected FIFO allocation plan is appended. P1-05 adds the human review workflow: a frozen `ReviewSnapshot` approval subject with a canonical payload hash, case-local item mapping with deterministic re-match and a successor snapshot, supplement request and rejection, machine-checkable freshness/staleness, and request-id idempotency. P1-06 adds role-based authorization for `SUBMITTER`, `APPROVER` and `OPERATOR` with local demo identities, an authoritative server-derived `submittedBy`/review actor (the client `decidedBy` is ignored), a request trace id, and an append-only, transactionally recorded audit history. Approval/allocation, payment/outbox and AI remain out of scope.

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
`ITEM_MAPPED`, `SUPPLEMENT_REQUESTED`, `CASE_REJECTED` (approval adds its own
action when implemented). Each entry stores the actor, roles, action,
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
responses).

Request limits keep resources bounded: a JSON request body is capped at
`http.request.max-body-bytes` (default 256 KiB, `HTTP_MAX_BODY_BYTES` to change)
and an oversize body is rejected with `413` **before** any transaction, and a
draft may have at most 100 lines (a larger list is `400`). The body cap is
enforced by reading the stream, so chunked requests without `Content-Length` are
bounded too.

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

The GitHub Actions workflow runs these checks and a five-service Compose smoke test. Source layout is intentionally small: `core-api` holds one Spring application organized by feature (`invoicecase`, `matching`, `purchasingreference`, `review`, `shared`); `web/src/app` holds the Next.js routes; `mock-erp` serves only a deterministic health response; `mock-purchasing` serves deterministic read-only purchase order aggregates. P1-01 defines the Phase 1 state contract and PostgreSQL baseline, P1-02 defines the external purchasing reference snapshot and refresh version semantics, P1-03 defines manual submission, evidence bundle versioning and request-id idempotency, P1-04 defines the deterministic AI-free 3-way match, P1-05 defines the frozen review snapshot, case-local mapping with deterministic re-match, supplement/reject and freshness, P1-06 defines role-based authorization, the authoritative submitter/reviewer identity, request trace ids and the append-only audit history, but later tickets still own approval, allocation, payment and P1-09 behavior.
