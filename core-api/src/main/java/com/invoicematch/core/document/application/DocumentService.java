package com.invoicematch.core.document.application;

import com.invoicematch.core.document.persistence.DocumentStore;
import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.shared.domain.DomainValidationException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentService.class);
    private final DocumentCommands commands;
    private final DocumentStorage storage;
    private final DocumentStore documents;
    private final AuthorizationService authorization;
    public DocumentService(DocumentCommands commands, DocumentStorage storage, DocumentStore documents,
            AuthorizationService authorization) {
        this.commands = commands; this.storage = storage; this.documents = documents; this.authorization = authorization;
    }
    public CommandResult<PresignView> presign(PresignCommand c) { return commands.reserve(c); }

    @Transactional(propagation = Propagation.NEVER)
    public CommandResult<DocumentView> complete(CompleteDocumentCommand c) {
        var replay = commands.replay(c);
        if (replay.isPresent()) return replay.get();
        var intent = commands.prepare(c);
        if (commands.existing(c).isPresent()) return commands.complete(c, null).result();
        String key = "originals/" + c.caseId() + "/" + c.documentId() + "/" + UUID.randomUUID();
        boolean retained = false;
        try {
            byte[] bytes = storage.readVerified(intent);
            storage.writeOriginal(key, bytes, intent.mediaType());
            var commit = commands.complete(c, key);
            retained = commit.retainedObject();
            return commit.result();
        } finally {
            if (!retained) {
                try { storage.removeOriginal(key); }
                catch (DocumentFailure e) { LOG.warn("Document original cleanup deferred to orphan maintenance"); }
            }
        }
    }
    public record Page(List<DocumentView> items, int page, int size, boolean hasNext) { }
    public Page list(UUID caseId, int page, int size) {
        authorization.requireCaseRead(caseId);
        if (page < 0 || page > 100000 || size < 1 || size > 50) {
            throw new DomainValidationException("page must be 0..100000 and size 1..50");
        }
        var rows = documents.list(caseId, size + 1, (long) page * size);
        return new Page(rows.stream().limit(size).map(DocumentView::from).toList(), page, size, rows.size() > size);
    }
}
