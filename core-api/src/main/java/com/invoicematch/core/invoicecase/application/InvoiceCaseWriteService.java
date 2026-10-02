package com.invoicematch.core.invoicecase.application;

import com.invoicematch.core.audit.application.AuditEvent;
import com.invoicematch.core.audit.application.AuditRecorder;
import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.document.domain.DocumentEvidence;
import com.invoicematch.core.document.persistence.DocumentStore;
import com.invoicematch.core.invoicecase.domain.CaseStateConflictException;
import com.invoicematch.core.invoicecase.domain.DraftNotEditableException;
import com.invoicematch.core.invoicecase.domain.DraftRevision;
import com.invoicematch.core.invoicecase.domain.DraftRevisionStatus;
import com.invoicematch.core.invoicecase.domain.EvidenceBundle;
import com.invoicematch.core.invoicecase.domain.InvoiceCase;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseId;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseNotFoundException;
import com.invoicematch.core.invoicecase.domain.InvoiceCaseStatus;
import com.invoicematch.core.invoicecase.domain.InvoiceLine;
import com.invoicematch.core.invoicecase.domain.StaleCaseVersionException;
import com.invoicematch.core.invoicecase.persistence.DraftRevisionRepository;
import com.invoicematch.core.invoicecase.persistence.EvidenceBundleRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceLineRepository;
import com.invoicematch.core.purchasingreference.application.PreparedPurchaseOrderSnapshot;
import com.invoicematch.core.purchasingreference.application.PurchaseOrderSnapshotReader;
import com.invoicematch.core.purchasingreference.application.PurchasingReferenceService;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.security.Actor;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.Quantity;
import com.invoicematch.core.shared.domain.SupplierId;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional write side of the manual invoice submission flow. Every public
 * method reserves the request id inside the same transaction as its side
 * effects, so a retry replays the stored response and never repeats work.
 *
 * <p>Draft edits replace the OPEN revision's line set atomically and bump the
 * case version, so a stale {@code expectedCaseVersion} is rejected. Submission
 * seals the revision and freezes a canonical, hashed evidence bundle before
 * moving the case to {@code REVIEW_PENDING}. The next revision may only be
 * opened in {@code SUPPLEMENT_REQUIRED}, copying the frozen lines forward
 * without touching the sealed version.
 */
@Service
public class InvoiceCaseWriteService {

    public static final String SCOPE_CREATE = "invoice-case:create";
    public static final String SCOPE_REPLACE_DRAFT = "invoice-case:draft:replace";
    public static final String SCOPE_SUBMIT = "invoice-case:submit";
    public static final String SCOPE_OPEN_REVISION = "invoice-case:revision:open";
    public static final String CREATE_RESOURCE_KEY = "NEW";

    /**
     * V1 physical bound on a draft: the Swiss-style manual invoice case is a
     * small document, and bounding the line set keeps the request body, the
     * frozen payload and the audit diff bounded. The API DTO enforces the same
     * limit before the request enters a transaction; this check covers direct
     * service calls.
     */
    public static final int MAX_DRAFT_LINES = 100;

    private final InvoiceCaseRepository invoiceCases;
    private final DraftRevisionRepository draftRevisions;
    private final InvoiceLineRepository invoiceLines;
    private final EvidenceBundleRepository evidenceBundles;
    private final DocumentStore documentEvidence;
    private final PurchaseOrderSnapshotReader purchaseOrderSnapshots;
    private final PurchasingReferenceService purchasingReferenceService;
    private final RequestIdempotencyStore idempotency;
    private final RequestFingerprint fingerprint;
    private final EvidenceBundlePayloadHasher payloadHasher;
    private final DraftRevisionLockInterceptor draftRevisionLockInterceptor;
    private final AuthorizationService authorization;
    private final AuditRecorder audit;
    private final Clock clock;

    public InvoiceCaseWriteService(
            InvoiceCaseRepository invoiceCases,
            DraftRevisionRepository draftRevisions,
            InvoiceLineRepository invoiceLines,
            EvidenceBundleRepository evidenceBundles,
            DocumentStore documentEvidence,
            PurchaseOrderSnapshotReader purchaseOrderSnapshots,
            PurchasingReferenceService purchasingReferenceService,
            RequestIdempotencyStore idempotency,
            RequestFingerprint fingerprint,
            EvidenceBundlePayloadHasher payloadHasher,
            ObjectProvider<DraftRevisionLockInterceptor> draftRevisionLockInterceptors,
            AuthorizationService authorization,
            AuditRecorder audit,
            Clock clock) {
        this.invoiceCases = invoiceCases;
        this.draftRevisions = draftRevisions;
        this.invoiceLines = invoiceLines;
        this.evidenceBundles = evidenceBundles;
        this.documentEvidence = documentEvidence;
        this.purchaseOrderSnapshots = purchaseOrderSnapshots;
        this.purchasingReferenceService = purchasingReferenceService;
        this.idempotency = idempotency;
        this.fingerprint = fingerprint;
        this.payloadHasher = payloadHasher;
        this.draftRevisionLockInterceptor =
                draftRevisionLockInterceptors.getIfAvailable(() -> DraftRevisionLockInterceptor.NONE);
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public CommandResult<InvoiceCaseDetail> create(
            CreateInvoiceCaseCommand command, String requestHash, PreparedPurchaseOrderSnapshot preparedSnapshot) {
        authorization.requireRole(Role.SUBMITTER);
        Actor actor = authorization.actor();
        RequestIdempotencyStore.BeginResult begin = idempotency.begin(
                SCOPE_CREATE, CREATE_RESOURCE_KEY, actor.username(), command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return replay(replay.response(), InvoiceCaseDetail.class);
        }

        // Only the winner of the request-id reservation applies the external
        // snapshot, inside this transaction, so a conflicting loser leaves no
        // half-applied purchase order or case behind.
        purchasingReferenceService.applyPrepared(preparedSnapshot);

        Instant now = clock.instant();
        InvoiceCase invoiceCase = invoiceCases.saveAndFlush(InvoiceCase.create(
                InvoiceCaseId.newId(),
                SupplierId.of(command.supplierId()),
                PurchaseOrderId.of(command.purchaseOrderId()),
                command.invoiceNumber(),
                InvoiceNumberNormalizer.normalize(command.invoiceNumber()),
                actor.username(),
                now));

        DraftRevision draft = draftRevisions.saveAndFlush(DraftRevision.open(invoiceCase.id().value(), 1, now));

        // Spring Data merges entities with an assigned id, so continue with the
        // returned managed instance; otherwise its optimistic version would lag
        // behind the committed row.
        invoiceCase.attachDraftRevision(draft.id(), now);
        invoiceCase = invoiceCases.saveAndFlush(invoiceCase);

        InvoiceCaseDetail detail = InvoiceCaseDetail.from(invoiceCase, draft, List.of());
        audit.record(new AuditEvent(
                invoiceCase.id().value(),
                actor,
                AuditAction.CASE_CREATED,
                AuditTargetType.CASE,
                invoiceCase.id().value().toString(),
                invoiceCase.version(),
                null,
                Map.of(
                        "supplierId", invoiceCase.supplier().value(),
                        "purchaseOrderId", invoiceCase.purchaseOrder().value(),
                        "invoiceNumber", invoiceCase.invoiceNumber(),
                        "status", invoiceCase.status().name()),
                command.requestId(),
                now));
        idempotency.recordResponse(SCOPE_CREATE, CREATE_RESOURCE_KEY, actor.username(), command.requestId(), 201, detail);
        return CommandResult.created(detail);
    }

    @Transactional
    public CommandResult<InvoiceCaseDetail> replaceDraft(ReplaceDraftLinesCommand command) {
        // Authoritative ownership check after the case row lock: the submitter
        // is read from the committed row, never from request input.
        InvoiceCase invoiceCase = loadForUpdate(command.caseId());
        authorization.requireSubmitterOwner(invoiceCase);
        Actor actor = authorization.actor();

        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.replaceDraft(command);
        RequestIdempotencyStore.BeginResult begin = idempotency.begin(
                SCOPE_REPLACE_DRAFT, resourceKey, actor.username(), command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return replay(replay.response(), InvoiceCaseDetail.class);
        }

        checkExpectedVersion(invoiceCase, command.expectedCaseVersion());
        DraftRevision draft = currentDraft(invoiceCase);
        validateLines(command.lines(), invoiceCase.purchaseOrder().value());

        List<InvoiceLine> existing = invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(draft.id());
        List<Map<String, Object>> beforeLines = existing.stream().map(this::lineSummary).toList();
        invoiceLines.deleteAll(existing);
        invoiceLines.flush();

        Instant now = clock.instant();
        List<InvoiceLine> replacement = replaceLines(command.lines(), invoiceCase, draft, now);
        invoiceLines.flush();

        invoiceCase.markModified(now);
        invoiceCase = invoiceCases.saveAndFlush(invoiceCase);

        InvoiceCaseDetail detail = InvoiceCaseDetail.from(invoiceCase, draft, replacement);
        audit.record(new AuditEvent(
                command.caseId(),
                actor,
                AuditAction.DRAFT_LINES_REPLACED,
                AuditTargetType.DRAFT_REVISION,
                draft.id().toString(),
                invoiceCase.version(),
                Map.of("lines", beforeLines),
                Map.of("lines", replacement.stream().map(this::lineSummary).toList()),
                command.requestId(),
                now));
        idempotency.recordResponse(SCOPE_REPLACE_DRAFT, resourceKey, actor.username(), command.requestId(), 200, detail);
        return CommandResult.ok(detail);
    }

    @Transactional
    public CommandResult<SubmissionResult> submit(SubmitInvoiceCaseCommand command) {
        InvoiceCase invoiceCase = loadForUpdate(command.caseId());
        authorization.requireSubmitterOwner(invoiceCase);
        Actor actor = authorization.actor();

        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.submit(command);
        RequestIdempotencyStore.BeginResult begin = idempotency.begin(
                SCOPE_SUBMIT, resourceKey, actor.username(), command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return replay(replay.response(), SubmissionResult.class);
        }

        checkExpectedVersion(invoiceCase, command.expectedCaseVersion());
        String statusBefore = invoiceCase.status().name();
        // The revision row lock is taken before any line is read or hashed, so a
        // concurrent line mutation either commits before this lock or is rejected
        // after it. Reading first would allow a frozen payload that omits a
        // not-yet-committed line change.
        DraftRevision revision = currentDraft(invoiceCase);
        draftRevisionLockInterceptor.afterLocked(revision.id());
        List<InvoiceLine> lines = invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(revision.id());
        if (lines.isEmpty()) {
            throw new DomainValidationException(
                    "At least one invoice line is required to submit case " + command.caseId());
        }

        Instant now = clock.instant();
        // Only completed document references are part of the frozen evidence.
        // Unfinished upload reservations are deliberately ignored and never
        // block submission; they are not evidence until completion commits.
        List<DocumentEvidence> documents = documentEvidence.evidenceForRevision(revision.id());
        String payloadSchema = documents.isEmpty()
                ? EvidenceBundlePayloadHasher.LEGACY_SCHEMA
                : EvidenceBundlePayloadHasher.DOCUMENT_SCHEMA;
        EvidenceBundlePayloadHasher.CanonicalPayload canonical =
                payloadHasher.canonicalize(invoiceCase, revision.revisionNumber(), lines, documents);
        int nextVersion = evidenceBundles.maxVersionNumber(command.caseId()) + 1;

        revision.seal(now);
        draftRevisions.saveAndFlush(revision);

        EvidenceBundle bundle = EvidenceBundle.freeze(UUID.randomUUID(), command.caseId(), revision.id(), nextVersion,
                payloadSchema, canonical.hash(), canonical.json(), now);
        evidenceBundles.saveAndFlush(bundle);

        invoiceCase.transitionTo(InvoiceCaseStatus.SUBMITTED, now);
        invoiceCase.transitionTo(InvoiceCaseStatus.REVIEW_PENDING, now);
        invoiceCase.clearDraftRevision(now);
        invoiceCase = invoiceCases.saveAndFlush(invoiceCase);

        SubmissionResult result = new SubmissionResult(
                command.caseId(), invoiceCase.status(), invoiceCase.version(), EvidenceBundleSummary.from(bundle));
        audit.record(new AuditEvent(
                command.caseId(),
                actor,
                AuditAction.CASE_SUBMITTED,
                AuditTargetType.CASE,
                command.caseId().toString(),
                invoiceCase.version(),
                Map.of("status", statusBefore, "draftRevisionNumber", revision.revisionNumber()),
                Map.of(
                        "status", invoiceCase.status().name(),
                        "evidenceBundleVersion", bundle.versionNumber(),
                        "evidencePayloadHash", bundle.payloadHash(),
                        "documentCount", documents.size()),
                command.requestId(),
                now));
        idempotency.recordResponse(SCOPE_SUBMIT, resourceKey, actor.username(), command.requestId(), 200, result);
        return CommandResult.ok(result);
    }

    @Transactional
    public CommandResult<InvoiceCaseDetail> openSupplementRevision(OpenSupplementRevisionCommand command) {
        InvoiceCase invoiceCase = loadForUpdate(command.caseId());
        authorization.requireSubmitterOwner(invoiceCase);
        Actor actor = authorization.actor();

        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.openRevision(command);
        RequestIdempotencyStore.BeginResult begin = idempotency.begin(
                SCOPE_OPEN_REVISION, resourceKey, actor.username(), command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return replay(replay.response(), InvoiceCaseDetail.class);
        }

        checkExpectedVersion(invoiceCase, command.expectedCaseVersion());
        if (invoiceCase.status() != InvoiceCaseStatus.SUPPLEMENT_REQUIRED) {
            throw new CaseStateConflictException(
                    command.caseId(),
                    "next revision can only be opened in SUPPLEMENT_REQUIRED but status was " + invoiceCase.status());
        }
        draftRevisions
                .findByInvoiceCaseIdAndStatus(command.caseId(), DraftRevisionStatus.OPEN)
                .ifPresent(open -> {
                    throw new CaseStateConflictException(
                            command.caseId(), "an OPEN draft revision already exists: " + open.revisionNumber());
                });

        EvidenceBundle latestBundle = evidenceBundles
                .findFirstByInvoiceCaseIdOrderByVersionNumberDesc(command.caseId())
                .orElseThrow(() -> new CaseStateConflictException(
                        command.caseId(), "no frozen evidence bundle exists to supplement"));
        EvidenceBundlePayload payload = payloadHasher.parse(latestBundle.payload());
        DraftRevision latestRevision = draftRevisions
                .findFirstByInvoiceCaseIdOrderByRevisionNumberDesc(command.caseId())
                .orElseThrow(() -> new IllegalStateException("A frozen bundle exists without a draft revision"));

        Instant now = clock.instant();
        DraftRevision draft =
                draftRevisions.saveAndFlush(DraftRevision.open(command.caseId(), latestRevision.revisionNumber() + 1, now));

        List<InvoiceLine> copies = new ArrayList<>();
        for (EvidenceBundlePayload.EvidenceLine line : payload.lines()) {
            copies.add(InvoiceLine.create(
                    UUID.randomUUID(),
                    command.caseId(),
                    draft.id(),
                    line.lineNumber(),
                    line.rawItemName(),
                    Quantity.of(line.quantity()),
                    Money.of(line.unitPrice()),
                    blankToNull(line.confirmedItemId()),
                    now));
        }
        invoiceLines.saveAll(copies);
        invoiceLines.flush();

        invoiceCase.attachDraftRevision(draft.id(), now);
        invoiceCase = invoiceCases.saveAndFlush(invoiceCase);

        // Inherit exactly the document set of the prior submission into the new
        // revision; files, documents and the prior bundle are never copied or
        // modified. The prior draft must be the current one first, because the
        // reference guard requires the new revision to be the current open draft.
        List<UUID> inherited = inheritedDocumentIds(command.caseId(), latestBundle, payload);
        for (UUID documentId : inherited) {
            documentEvidence.reference(draft.id(), command.caseId(), documentId, now);
        }

        InvoiceCaseDetail detail = InvoiceCaseDetail.from(invoiceCase, draft, copies);
        audit.record(new AuditEvent(
                command.caseId(),
                actor,
                AuditAction.SUPPLEMENT_REVISION_OPENED,
                AuditTargetType.DRAFT_REVISION,
                draft.id().toString(),
                invoiceCase.version(),
                Map.of(
                        "status", InvoiceCaseStatus.SUPPLEMENT_REQUIRED.name(),
                        "evidenceBundleVersion", latestBundle.versionNumber()),
                Map.of(
                        "status", invoiceCase.status().name(),
                        "revisionNumber", draft.revisionNumber(),
                        "copiedLineCount", copies.size(),
                        "inheritedDocumentCount", inherited.size()),
                command.requestId(),
                now));
        idempotency.recordResponse(
                SCOPE_OPEN_REVISION, resourceKey, actor.username(), command.requestId(), 201, detail);
        return CommandResult.created(detail);
    }

    /**
     * The document ids a supplement inherits from the immediately prior
     * submission. A legacy, document-less bundle inherits nothing (the V11
     * backfilled references on its sealed revision are historical and are not
     * retroactively promoted). A document-v2 bundle must carry an explicit
     * {@code schemaVersion: 2} and freeze exactly the authoritative reference
     * set of its sealed revision, with no duplicate ids, and that set is
     * inherited. A missing/unknown JSON schema or a legacy payload carrying
     * document fields is rejected rather than guessed.
     */
    private List<UUID> inheritedDocumentIds(
            UUID caseId, EvidenceBundle bundle, EvidenceBundlePayload payload) {
        String schema = bundle.payloadSchema();
        List<EvidenceBundlePayload.DocumentLine> frozen = payload.documents();
        Integer jsonSchema = payload.schemaVersion();
        if (EvidenceBundlePayloadHasher.LEGACY_SCHEMA.equals(schema)) {
            if (jsonSchema != null || (frozen != null && !frozen.isEmpty())) {
                throw new CaseStateConflictException(caseId,
                        "the legacy evidence bundle payload carries document evidence fields");
            }
            return List.of();
        }
        if (!EvidenceBundlePayloadHasher.DOCUMENT_SCHEMA.equals(schema)) {
            throw new CaseStateConflictException(caseId, "unsupported evidence bundle schema " + schema);
        }
        if (jsonSchema == null || jsonSchema != EvidenceBundlePayloadHasher.DOCUMENT_SCHEMA_VERSION) {
            throw new CaseStateConflictException(caseId,
                    "the document-v2 evidence bundle payload has a missing or unsupported schemaVersion");
        }
        if (frozen == null || frozen.isEmpty()) {
            throw new CaseStateConflictException(caseId, "document-v2 evidence bundle has no frozen documents");
        }
        Map<UUID, DocumentEvidence> authoritative = new LinkedHashMap<>();
        for (DocumentEvidence evidence : documentEvidence.evidenceForRevision(bundle.draftRevisionId())) {
            authoritative.put(evidence.documentId(), evidence);
        }
        Set<UUID> frozenIds = new LinkedHashSet<>();
        for (EvidenceBundlePayload.DocumentLine line : frozen) {
            UUID documentId;
            try {
                documentId = UUID.fromString(line.documentId());
            } catch (RuntimeException e) {
                throw new CaseStateConflictException(caseId, "the frozen document id is not a UUID");
            }
            if (!frozenIds.add(documentId)) {
                throw new CaseStateConflictException(caseId, "the frozen document set contains a duplicate id");
            }
            DocumentEvidence evidence = authoritative.get(documentId);
            if (evidence == null
                    || !evidence.sourceDraftRevisionId().toString().equals(line.sourceDraftRevisionId())
                    || !evidence.fileName().equals(line.fileName())
                    || !evidence.mediaType().equals(line.mediaType())
                    || evidence.sizeBytes() != line.sizeBytes()
                    || !evidence.checksum().equals(line.checksum())) {
                throw new CaseStateConflictException(caseId,
                        "the frozen document metadata does not match the sealed revision references");
            }
        }
        if (frozenIds.size() != authoritative.size() || !frozenIds.equals(authoritative.keySet())) {
            throw new CaseStateConflictException(caseId,
                    "the frozen document set does not match the sealed revision references");
        }
        return new ArrayList<>(frozenIds);
    }

    private List<InvoiceLine> replaceLines(
            List<InvoiceLineInput> inputs, InvoiceCase invoiceCase, DraftRevision draft, Instant now) {
        List<InvoiceLine> replacement = new ArrayList<>(inputs.size());
        for (InvoiceLineInput input : inputs) {
            replacement.add(InvoiceLine.create(
                    UUID.randomUUID(),
                    invoiceCase.id().value(),
                    draft.id(),
                    input.lineNumber(),
                    input.rawItemName(),
                    Quantity.of(input.quantity()),
                    Money.of(input.unitPrice()),
                    blankToNull(input.confirmedItemId()),
                    now));
        }
        return invoiceLines.saveAll(replacement);
    }

    private InvoiceCase loadForUpdate(UUID caseId) {
        return invoiceCases.findByIdForUpdate(caseId).orElseThrow(() -> new InvoiceCaseNotFoundException(caseId));
    }

    private void checkExpectedVersion(InvoiceCase invoiceCase, long expectedVersion) {
        if (invoiceCase.version() != expectedVersion) {
            throw new StaleCaseVersionException(invoiceCase.id().value(), expectedVersion, invoiceCase.version());
        }
    }

    private DraftRevision currentDraft(InvoiceCase invoiceCase) {
        InvoiceCaseStatus status = invoiceCase.status();
        if (status != InvoiceCaseStatus.DRAFT && status != InvoiceCaseStatus.SUPPLEMENT_REQUIRED) {
            throw new DraftNotEditableException(invoiceCase.id().value(), "case status is " + status);
        }
        UUID pointer = invoiceCase.currentDraftRevisionId();
        if (pointer == null) {
            throw new DraftNotEditableException(invoiceCase.id().value(), "no current revision is attached");
        }
        DraftRevision draft = draftRevisions
                .findByIdForUpdate(pointer)
                .orElseThrow(() -> new DraftNotEditableException(invoiceCase.id().value(), "current revision is missing"));
        if (!draft.isEditable()) {
            throw new DraftNotEditableException(
                    invoiceCase.id().value(), "revision " + draft.revisionNumber() + " is " + draft.status());
        }
        return draft;
    }

    private void validateLines(List<InvoiceLineInput> lines, String purchaseOrderId) {
        if (lines == null) {
            throw new DomainValidationException("lines must not be null");
        }
        if (lines.size() > MAX_DRAFT_LINES) {
            throw new DomainValidationException(
                    "lines must not contain more than " + MAX_DRAFT_LINES + " entries");
        }
        Set<Integer> numbers = new TreeSet<>();
        for (InvoiceLineInput line : lines) {
            if (line == null) {
                throw new DomainValidationException("lines must not contain null entries");
            }
            if (line.lineNumber() <= 0) {
                throw new DomainValidationException("lineNumber must be positive: " + line.lineNumber());
            }
            if (!numbers.add(line.lineNumber())) {
                throw new DomainValidationException("Duplicate lineNumber: " + line.lineNumber());
            }
            if (line.rawItemName() == null || line.rawItemName().isBlank()) {
                throw new DomainValidationException("rawItemName must not be blank for line " + line.lineNumber());
            }
            if (line.quantity() <= 0) {
                throw new DomainValidationException("quantity must be positive for line " + line.lineNumber());
            }
            if (line.unitPrice() < 0) {
                throw new DomainValidationException("unitPrice must not be negative for line " + line.lineNumber());
            }
        }
        int expected = 1;
        for (int number : numbers) {
            if (number != expected) {
                throw new DomainValidationException(
                        "lineNumber must be contiguous starting at 1; expected " + expected + " but found " + number);
            }
            expected++;
        }
        validateConfirmedItems(lines, purchaseOrderId);
    }

    private void validateConfirmedItems(List<InvoiceLineInput> lines, String purchaseOrderId) {
        List<String> confirmedItemIds = lines.stream()
                .map(InvoiceLineInput::confirmedItemId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (confirmedItemIds.isEmpty()) {
            return;
        }
        PurchaseOrderAggregate aggregate = purchaseOrderSnapshots
                .findCurrent(PurchaseOrderId.of(purchaseOrderId))
                .orElseThrow(() -> new DomainValidationException(
                        "No purchase order snapshot for " + purchaseOrderId + " is available to validate confirmed items"));
        Set<String> validItemIds = aggregate.purchaseOrder().lines().stream()
                .map(line -> line.itemId())
                .collect(Collectors.toSet());
        for (String confirmedItemId : confirmedItemIds) {
            if (!validItemIds.contains(confirmedItemId)) {
                throw new DomainValidationException("confirmedItemId " + confirmedItemId
                        + " does not match an active item of purchase order " + purchaseOrderId);
            }
        }
    }

    private <T> CommandResult<T> replay(RequestIdempotencyStore.StoredResponse stored, Class<T> type) {
        return new CommandResult<>(stored.status(), idempotency.decode(stored, type));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Compact, bounded audit view of one invoice line. The full raw item name can
     * be up to 500 characters and is retained as a bounded preview plus its
     * length and SHA-256, so a 100-line replacement stays far below the audit
     * size limit regardless of multibyte characters or JSON escaping, while the
     * meaningful values (identity, quantity, price, confirmed item) are exact.
     */
    private Map<String, Object> lineSummary(InvoiceLine line) {
        String rawItemName = line.rawItemName();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("lineNumber", line.lineNumber());
        summary.put("quantity", line.quantity().value());
        summary.put("unitPrice", line.unitPrice().amount());
        summary.put("confirmedItemId", line.confirmedItemId());
        summary.put("rawItemNamePreview", preview(rawItemName));
        summary.put("rawItemNameLength", rawItemName.length());
        summary.put("rawItemNameSha256", sha256Hex(rawItemName));
        return summary;
    }

    private static String preview(String value) {
        int previewChars = 64;
        if (value.codePointCount(0, value.length()) <= previewChars) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, previewChars));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
