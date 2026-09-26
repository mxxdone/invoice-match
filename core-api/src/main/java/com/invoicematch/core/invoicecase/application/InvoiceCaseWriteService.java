package com.invoicematch.core.invoicecase.application;

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
import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.Quantity;
import com.invoicematch.core.shared.domain.SupplierId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
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

    private final InvoiceCaseRepository invoiceCases;
    private final DraftRevisionRepository draftRevisions;
    private final InvoiceLineRepository invoiceLines;
    private final EvidenceBundleRepository evidenceBundles;
    private final PurchaseOrderSnapshotReader purchaseOrderSnapshots;
    private final PurchasingReferenceService purchasingReferenceService;
    private final RequestIdempotencyStore idempotency;
    private final RequestFingerprint fingerprint;
    private final EvidenceBundlePayloadHasher payloadHasher;
    private final Clock clock;

    public InvoiceCaseWriteService(
            InvoiceCaseRepository invoiceCases,
            DraftRevisionRepository draftRevisions,
            InvoiceLineRepository invoiceLines,
            EvidenceBundleRepository evidenceBundles,
            PurchaseOrderSnapshotReader purchaseOrderSnapshots,
            PurchasingReferenceService purchasingReferenceService,
            RequestIdempotencyStore idempotency,
            RequestFingerprint fingerprint,
            EvidenceBundlePayloadHasher payloadHasher,
            Clock clock) {
        this.invoiceCases = invoiceCases;
        this.draftRevisions = draftRevisions;
        this.invoiceLines = invoiceLines;
        this.evidenceBundles = evidenceBundles;
        this.purchaseOrderSnapshots = purchaseOrderSnapshots;
        this.purchasingReferenceService = purchasingReferenceService;
        this.idempotency = idempotency;
        this.fingerprint = fingerprint;
        this.payloadHasher = payloadHasher;
        this.clock = clock;
    }

    @Transactional
    public CommandResult<InvoiceCaseDetail> create(
            CreateInvoiceCaseCommand command, String requestHash, PreparedPurchaseOrderSnapshot preparedSnapshot) {
        RequestIdempotencyStore.BeginResult begin =
                idempotency.begin(SCOPE_CREATE, CREATE_RESOURCE_KEY, command.requestId(), requestHash);
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
                now));

        DraftRevision draft = draftRevisions.saveAndFlush(DraftRevision.open(invoiceCase.id().value(), 1, now));

        // Spring Data merges entities with an assigned id, so continue with the
        // returned managed instance; otherwise its optimistic version would lag
        // behind the committed row.
        invoiceCase.attachDraftRevision(draft.id(), now);
        invoiceCase = invoiceCases.saveAndFlush(invoiceCase);

        InvoiceCaseDetail detail = InvoiceCaseDetail.from(invoiceCase, draft, List.of());
        idempotency.recordResponse(SCOPE_CREATE, CREATE_RESOURCE_KEY, command.requestId(), 201, detail);
        return CommandResult.created(detail);
    }

    @Transactional
    public CommandResult<InvoiceCaseDetail> replaceDraft(ReplaceDraftLinesCommand command) {
        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.replaceDraft(command);
        RequestIdempotencyStore.BeginResult begin =
                idempotency.begin(SCOPE_REPLACE_DRAFT, resourceKey, command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return replay(replay.response(), InvoiceCaseDetail.class);
        }

        InvoiceCase invoiceCase = loadForUpdate(command.caseId());
        checkExpectedVersion(invoiceCase, command.expectedCaseVersion());
        DraftRevision draft = currentDraft(invoiceCase);
        validateLines(command.lines(), invoiceCase.purchaseOrder().value());

        List<InvoiceLine> existing = invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(draft.id());
        invoiceLines.deleteAll(existing);
        invoiceLines.flush();

        Instant now = clock.instant();
        List<InvoiceLine> replacement = replaceLines(command.lines(), invoiceCase, draft, now);
        invoiceLines.flush();

        invoiceCase.markModified(now);
        invoiceCase = invoiceCases.saveAndFlush(invoiceCase);

        InvoiceCaseDetail detail = InvoiceCaseDetail.from(invoiceCase, draft, replacement);
        idempotency.recordResponse(SCOPE_REPLACE_DRAFT, resourceKey, command.requestId(), 200, detail);
        return CommandResult.ok(detail);
    }

    @Transactional
    public CommandResult<SubmissionResult> submit(SubmitInvoiceCaseCommand command) {
        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.submit(command);
        RequestIdempotencyStore.BeginResult begin =
                idempotency.begin(SCOPE_SUBMIT, resourceKey, command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return replay(replay.response(), SubmissionResult.class);
        }

        InvoiceCase invoiceCase = loadForUpdate(command.caseId());
        checkExpectedVersion(invoiceCase, command.expectedCaseVersion());
        DraftRevision revision = currentDraft(invoiceCase);
        List<InvoiceLine> lines = invoiceLines.findByDraftRevisionIdOrderByLineNumberAsc(revision.id());
        if (lines.isEmpty()) {
            throw new DomainValidationException(
                    "At least one invoice line is required to submit case " + command.caseId());
        }

        Instant now = clock.instant();
        EvidenceBundlePayloadHasher.CanonicalPayload canonical =
                payloadHasher.canonicalize(invoiceCase, revision.revisionNumber(), lines);
        int nextVersion = evidenceBundles.maxVersionNumber(command.caseId()) + 1;

        revision.seal(now);
        draftRevisions.saveAndFlush(revision);

        EvidenceBundle bundle = EvidenceBundle.freeze(
                UUID.randomUUID(), command.caseId(), revision.id(), nextVersion, canonical.hash(), canonical.json(), now);
        evidenceBundles.saveAndFlush(bundle);

        invoiceCase.transitionTo(InvoiceCaseStatus.SUBMITTED, now);
        invoiceCase.transitionTo(InvoiceCaseStatus.REVIEW_PENDING, now);
        invoiceCase.clearDraftRevision(now);
        invoiceCase = invoiceCases.saveAndFlush(invoiceCase);

        SubmissionResult result = new SubmissionResult(
                command.caseId(), invoiceCase.status(), invoiceCase.version(), EvidenceBundleSummary.from(bundle));
        idempotency.recordResponse(SCOPE_SUBMIT, resourceKey, command.requestId(), 200, result);
        return CommandResult.ok(result);
    }

    @Transactional
    public CommandResult<InvoiceCaseDetail> openSupplementRevision(OpenSupplementRevisionCommand command) {
        String resourceKey = command.caseId().toString();
        String requestHash = fingerprint.openRevision(command);
        RequestIdempotencyStore.BeginResult begin =
                idempotency.begin(SCOPE_OPEN_REVISION, resourceKey, command.requestId(), requestHash);
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay replay) {
            return replay(replay.response(), InvoiceCaseDetail.class);
        }

        InvoiceCase invoiceCase = loadForUpdate(command.caseId());
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

        InvoiceCaseDetail detail = InvoiceCaseDetail.from(invoiceCase, draft, copies);
        idempotency.recordResponse(SCOPE_OPEN_REVISION, resourceKey, command.requestId(), 201, detail);
        return CommandResult.created(detail);
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
                .findById(pointer)
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
}
