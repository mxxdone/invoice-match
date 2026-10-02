package com.invoicematch.core.document.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.audit.application.AuditEvent;
import com.invoicematch.core.audit.application.AuditRecorder;
import com.invoicematch.core.audit.domain.AuditAction;
import com.invoicematch.core.audit.domain.AuditTargetType;
import com.invoicematch.core.document.domain.RegisteredDocument;
import com.invoicematch.core.document.domain.UploadIntent;
import com.invoicematch.core.document.persistence.DocumentStore;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.IdempotencyConflictException;
import com.invoicematch.core.invoicecase.application.RequestIdempotencyStore;
import com.invoicematch.core.invoicecase.domain.*;
import com.invoicematch.core.invoicecase.persistence.DraftRevisionRepository;
import com.invoicematch.core.invoicecase.persistence.InvoiceCaseRepository;
import com.invoicematch.core.security.AuthorizationService;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short DB transactions; no remote storage reads, writes or deletes here. */
@Service
public class DocumentCommands {
    private static final String PRESIGN = "document:presign";
    private static final String COMPLETE = "document:complete";
    private final InvoiceCaseRepository cases;
    private final DraftRevisionRepository drafts;
    private final DocumentStore documents;
    private final AuthorizationService authorization;
    private final RequestIdempotencyStore idempotency;
    private final AuditRecorder audit;
    private final DocumentStorage storage;
    private final Clock clock;
    private final ObjectMapper mapper;

    public DocumentCommands(InvoiceCaseRepository cases, DraftRevisionRepository drafts, DocumentStore documents,
            AuthorizationService authorization, RequestIdempotencyStore idempotency, AuditRecorder audit,
            DocumentStorage storage, Clock clock, ObjectMapper mapper) {
        this.cases = cases; this.drafts = drafts; this.documents = documents; this.authorization = authorization;
        this.idempotency = idempotency; this.audit = audit; this.storage = storage; this.clock = clock; this.mapper = mapper;
    }
    @Transactional
    public CommandResult<PresignView> reserve(PresignCommand c) {
        c.validate();
        InvoiceCase invoice = lock(c.caseId());
        String actor = authorization.actor().username();
        var begin = idempotency.begin(PRESIGN, c.caseId().toString(), actor, c.requestId(), hash(c));
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay r) {
            return new CommandResult<>(r.response().status(), idempotency.decode(r.response(), PresignView.class));
        }
        editable(invoice, c.expectedCaseVersion(), c.draftRevisionId(), true);
        var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (documents.occupiedSlots(c.draftRevisionId(), now) >= DocumentPolicy.MAX_FILES) {
            throw new DocumentFailure(409, "DOCUMENT_LIMIT_REACHED", "At most 10 documents or active uploads per draft");
        }
        UUID id = UUID.randomUUID();
        var intent = new UploadIntent(id, c.caseId(), c.draftRevisionId(), c.fileName(), c.mediaType(), c.sizeBytes(),
                c.checksum(), "uploads/" + c.caseId() + "/" + id, now.plusSeconds(DocumentPolicy.TTL_SECONDS), now);
        // Presigning is local cryptography with a configured region, not remote I/O.
        String url = storage.presign(intent);
        documents.reserve(intent);
        var view = new PresignView(id, c.draftRevisionId(), invoice.version(), url, "PUT",
                Map.of("Content-Type", c.mediaType()), intent.expiresAt());
        audit.record(new AuditEvent(c.caseId(), authorization.actor(), AuditAction.DOCUMENT_UPLOAD_RESERVED,
                AuditTargetType.DRAFT_REVISION, c.draftRevisionId().toString(), invoice.version(), null,
                Map.of("documentId", id, "fileName", c.fileName(), "mediaType", c.mediaType(),
                        "sizeBytes", c.sizeBytes(), "checksum", c.checksum()), c.requestId(), now));
        idempotency.recordResponse(PRESIGN, c.caseId().toString(), actor, c.requestId(), 201, view);
        return CommandResult.created(view);
    }

    public Optional<CommandResult<DocumentView>> replay(CompleteDocumentCommand c) {
        c.validate();
        authorization.requireSubmitterOwner(c.caseId());
        var stored = idempotency.find(COMPLETE, resource(c), authorization.actor().username(), c.requestId());
        return stored.map(r -> {
            if (!r.requestHash().equals(hash(c))) throw new IdempotencyConflictException(COMPLETE, resource(c), c.requestId());
            return new CommandResult<>(r.status(), idempotency.decode(r, DocumentView.class));
        });
    }
    public UploadIntent prepare(CompleteDocumentCommand c) {
        c.validate();
        authorization.requireSubmitterOwner(c.caseId());
        UploadIntent intent = upload(c);
        verifySubject(intent, c);
        if (documents.document(c.caseId(), c.documentId()).isPresent()) return intent;
        InvoiceCase invoice = cases.findById(c.caseId()).orElseThrow(() -> new InvoiceCaseNotFoundException(c.caseId()));
        editable(invoice, c.expectedCaseVersion(), c.draftRevisionId(), false);
        unexpired(intent);
        return intent;
    }
    public Optional<RegisteredDocument> existing(CompleteDocumentCommand c) {
        return documents.document(c.caseId(), c.documentId());
    }
    public record Commit(CommandResult<DocumentView> result, boolean retainedObject) { }

    @Transactional
    public Commit complete(CompleteDocumentCommand c, String originalKey) {
        c.validate();
        InvoiceCase invoice = lock(c.caseId());
        String actor = authorization.actor().username();
        var begin = idempotency.begin(COMPLETE, resource(c), actor, c.requestId(), hash(c));
        if (begin instanceof RequestIdempotencyStore.BeginResult.Replay r) {
            return new Commit(new CommandResult<>(r.response().status(), idempotency.decode(r.response(), DocumentView.class)), false);
        }
        var intent = upload(c);
        verifySubject(intent, c);
        var existing = documents.document(c.caseId(), c.documentId());
        DocumentView view;
        boolean retained = false;
        if (existing.isPresent()) {
            view = DocumentView.from(existing.get());
        } else {
            editable(invoice, c.expectedCaseVersion(), c.draftRevisionId(), true);
            unexpired(intent);
            if (originalKey == null) throw new IllegalStateException("Verified original is required");
            invoice.markModified(clock.instant());
            cases.saveAndFlush(invoice);
            var doc = new RegisteredDocument(intent, originalKey, invoice.version(),
                    clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            documents.register(doc);
            // The revision-scoped reference is written in the same transaction
            // as the immutable document, before submission can seal the draft.
            documents.reference(c.draftRevisionId(), c.caseId(), intent.id(), doc.registeredAt());
            view = DocumentView.from(doc);
            audit.record(new AuditEvent(c.caseId(), authorization.actor(), AuditAction.DOCUMENT_REGISTERED,
                    AuditTargetType.DRAFT_REVISION, c.draftRevisionId().toString(), invoice.version(), null,
                    Map.of("documentId", intent.id(), "fileName", intent.fileName(), "mediaType", intent.mediaType(),
                            "sizeBytes", intent.sizeBytes(), "checksum", intent.checksum(),
                            "registeredAt", doc.registeredAt().toString()), c.requestId(), doc.registeredAt()));
            retained = true;
        }
        idempotency.recordResponse(COMPLETE, resource(c), actor, c.requestId(), 201, view);
        return new Commit(CommandResult.created(view), retained);
    }
    private InvoiceCase lock(UUID id) {
        var invoice = cases.findByIdForUpdate(id).orElseThrow(() -> new InvoiceCaseNotFoundException(id));
        authorization.requireSubmitterOwner(invoice);
        return invoice;
    }
    private void editable(InvoiceCase invoice, long version, UUID draftId, boolean locked) {
        if (invoice.version() != version) throw new StaleCaseVersionException(invoice.id().value(), version, invoice.version());
        if ((invoice.status() != InvoiceCaseStatus.DRAFT && invoice.status() != InvoiceCaseStatus.SUPPLEMENT_REQUIRED)
                || !draftId.equals(invoice.currentDraftRevisionId())) {
            throw new DraftNotEditableException(invoice.id().value(), "not the current draft");
        }
        var revision = (locked ? drafts.findByIdForUpdate(draftId) : drafts.findById(draftId))
                .orElseThrow(() -> new DraftNotEditableException(invoice.id().value(), "missing revision"));
        if (!revision.isEditable() || !revision.invoiceCaseId().equals(invoice.id().value())) {
            throw new DraftNotEditableException(invoice.id().value(), "revision is sealed or belongs to another case");
        }
    }
    private UploadIntent upload(CompleteDocumentCommand c) {
        return documents.upload(c.caseId(), c.documentId()).orElseThrow(() ->
                new DocumentFailure(404, "DOCUMENT_NOT_FOUND", "Document upload was not found in this case"));
    }
    private void verifySubject(UploadIntent u, CompleteDocumentCommand c) {
        if (!u.draftRevisionId().equals(c.draftRevisionId()) || !u.checksum().equals(c.checksum())) {
            throw new DocumentFailure(409, "DOCUMENT_SUBJECT_CONFLICT", "Draft or checksum differs from the upload reservation");
        }
    }
    private void unexpired(UploadIntent u) {
        if (!clock.instant().isBefore(u.expiresAt())) {
            throw new DocumentFailure(409, "DOCUMENT_UPLOAD_EXPIRED", "Upload reservation expired; request a new URL");
        }
    }
    private String hash(Object c) {
        try { return DocumentPolicy.hash(mapper.writeValueAsBytes(c)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Document request serialization failed", e); }
    }
    private static String resource(CompleteDocumentCommand c) { return c.caseId().toString(); }
}
