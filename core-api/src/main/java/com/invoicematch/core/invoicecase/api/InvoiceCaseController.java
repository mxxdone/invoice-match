package com.invoicematch.core.invoicecase.api;

import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.invoicecase.application.CreateInvoiceCaseCommand;
import com.invoicematch.core.invoicecase.application.EvidenceBundleDetail;
import com.invoicematch.core.invoicecase.application.EvidenceBundleSummary;
import com.invoicematch.core.invoicecase.application.InvoiceCaseApplicationService;
import com.invoicematch.core.invoicecase.application.InvoiceCaseDetail;
import com.invoicematch.core.invoicecase.application.InvoiceCaseQueryService;
import com.invoicematch.core.invoicecase.application.InvoiceLineInput;
import com.invoicematch.core.invoicecase.application.OpenSupplementRevisionCommand;
import com.invoicematch.core.invoicecase.application.ReplaceDraftLinesCommand;
import com.invoicematch.core.invoicecase.application.SubmissionResult;
import com.invoicematch.core.invoicecase.application.SubmitInvoiceCaseCommand;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual invoice case API. The confirmed Spec paths
 * {@code POST /api/invoice-cases} and {@code POST /api/invoice-cases/{id}/submit}
 * are preserved; draft editing, opening a supplement revision and reading past
 * evidence bundle versions use consistent REST paths under the same resource.
 */
@RestController
@RequestMapping("/api/invoice-cases")
public class InvoiceCaseController {

    private final InvoiceCaseApplicationService commands;
    private final InvoiceCaseQueryService queries;
    private final AuthorizationService authorization;

    public InvoiceCaseController(
            InvoiceCaseApplicationService commands, InvoiceCaseQueryService queries,
            AuthorizationService authorization) {
        this.commands = commands;
        this.queries = queries;
        this.authorization = authorization;
    }

    @PostMapping
    public ResponseEntity<InvoiceCaseDetail> create(@Valid @RequestBody CreateInvoiceCaseRequest request) {
        authorization.requireRole(Role.SUBMITTER);
        CommandResult<InvoiceCaseDetail> result = commands.create(new CreateInvoiceCaseCommand(
                request.requestId(), request.supplierId(), request.purchaseOrderId(), request.invoiceNumber()));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/{id}")
    public InvoiceCaseDetail get(@PathVariable UUID id) {
        authorization.requireCaseRead(id);
        return queries.get(id);
    }

    @PutMapping("/{id}/draft")
    public ResponseEntity<InvoiceCaseDetail> replaceDraft(
            @PathVariable UUID id, @Valid @RequestBody ReplaceDraftLinesRequest request) {
        authorization.requireSubmitterOwner(id);
        List<InvoiceLineInput> lines = request.lines().stream()
                .map(line -> new InvoiceLineInput(
                        line.lineNumber(),
                        line.rawItemName(),
                        line.quantity(),
                        line.unitPrice(),
                        blankToNull(line.confirmedItemId())))
                .toList();
        CommandResult<InvoiceCaseDetail> result = commands.replaceDraft(
                new ReplaceDraftLinesCommand(id, request.requestId(), request.expectedCaseVersion(), lines));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<SubmissionResult> submit(
            @PathVariable UUID id, @Valid @RequestBody SubmitInvoiceCaseRequest request) {
        authorization.requireSubmitterOwner(id);
        CommandResult<SubmissionResult> result = commands.submit(
                new SubmitInvoiceCaseCommand(id, request.requestId(), request.expectedCaseVersion()));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/{id}/revisions")
    public ResponseEntity<InvoiceCaseDetail> openSupplementRevision(
            @PathVariable UUID id, @Valid @RequestBody OpenSupplementRevisionRequest request) {
        authorization.requireSubmitterOwner(id);
        CommandResult<InvoiceCaseDetail> result = commands.openSupplementRevision(
                new OpenSupplementRevisionCommand(id, request.requestId(), request.expectedCaseVersion()));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/{id}/evidence-bundles")
    public List<EvidenceBundleSummary> listEvidenceBundles(@PathVariable UUID id) {
        authorization.requireCaseRead(id);
        return queries.listEvidenceBundles(id);
    }

    @GetMapping("/{id}/evidence-bundles/{version}")
    public EvidenceBundleDetail getEvidenceBundle(@PathVariable UUID id, @PathVariable int version) {
        authorization.requireCaseRead(id);
        return queries.getEvidenceBundle(id, version);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
