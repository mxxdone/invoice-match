package com.invoicematch.core.document.api;

import com.invoicematch.core.document.application.*;
import com.invoicematch.core.invoicecase.api.ApiError;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invoice-cases/{caseId}/documents")
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service) { this.service = service; }
    public record PresignRequest(String requestId, Long expectedCaseVersion, UUID draftRevisionId,
            String fileName, String mediaType, Long sizeBytes, String checksum) { }
    public record CompleteRequest(String requestId, Long expectedCaseVersion, UUID draftRevisionId,
            UUID documentId, String checksum) { }
    @PostMapping("/presign")
    public ResponseEntity<PresignView> presign(@PathVariable UUID caseId, @RequestBody PresignRequest r) {
        var result = service.presign(new PresignCommand(caseId, r.requestId(), r.expectedCaseVersion(), r.draftRevisionId(),
                r.fileName(), r.mediaType(), r.sizeBytes(), r.checksum()));
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @PostMapping("/complete")
    public ResponseEntity<DocumentView> complete(@PathVariable UUID caseId, @RequestBody CompleteRequest r) {
        var result = service.complete(new CompleteDocumentCommand(caseId, r.requestId(), r.expectedCaseVersion(),
                r.draftRevisionId(), r.documentId(), r.checksum()));
        return ResponseEntity.status(result.status()).body(result.body());
    }
    @GetMapping
    public DocumentService.Page list(@PathVariable UUID caseId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) { return service.list(caseId, page, size); }
    @ExceptionHandler(DocumentFailure.class)
    public ResponseEntity<ApiError> documentFailure(DocumentFailure e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage()));
    }
}
