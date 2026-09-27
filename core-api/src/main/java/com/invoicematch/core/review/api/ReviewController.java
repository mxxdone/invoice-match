package com.invoicematch.core.review.api;

import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.review.application.FreezeReviewSnapshotCommand;
import com.invoicematch.core.review.application.MappingDecisionResult;
import com.invoicematch.core.review.application.RecordMappingDecisionCommand;
import com.invoicematch.core.review.application.RejectReviewCommand;
import com.invoicematch.core.review.application.RequestSupplementCommand;
import com.invoicematch.core.review.application.ReviewDecisionView;
import com.invoicematch.core.review.application.ReviewFreshness;
import com.invoicematch.core.review.application.ReviewQueryService;
import com.invoicematch.core.review.application.ReviewService;
import com.invoicematch.core.review.application.ReviewSnapshotView;
import com.invoicematch.core.security.AuthorizationService;
import com.invoicematch.core.security.Role;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Human review workflow API. It freezes the approval subject, exposes snapshot
 * history/detail/freshness and records the three Phase 1 human actions (item
 * mapping, supplement request and rejection). Approval/allocation belongs to
 * P1-07 and is not exposed here.
 *
 * <p><strong>Authentication debt (P1-06):</strong> the optional
 * {@code decidedBy} field is an unauthenticated client placeholder, not a
 * verified identity. It must not be used for authorization, self-approval
 * checks or audit. P1-01's {@code ReviewDecision.decidedBy} non-null constraint
 * is satisfied with a placeholder actor until P1-06 binds the authenticated
 * principal server-side and removes this field from the request contract.
 */
@RestController
@RequestMapping("/api/invoice-cases")
public class ReviewController {

    private final ReviewService commands;
    private final ReviewQueryService queries;
    private final AuthorizationService authorization;

    public ReviewController(
            ReviewService commands, ReviewQueryService queries, AuthorizationService authorization) {
        this.commands = commands;
        this.queries = queries;
        this.authorization = authorization;
    }

    @PostMapping("/{id}/review-snapshots")
    public ResponseEntity<ReviewSnapshotView> freezeSnapshot(
            @PathVariable UUID id, @Valid @RequestBody FreezeReviewSnapshotRequest request) {
        authorization.requireRole(Role.APPROVER);
        CommandResult<ReviewSnapshotView> result = commands.freezeSnapshot(
                new FreezeReviewSnapshotCommand(id, request.requestId(), request.expectedCaseVersion()));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/{id}/review-snapshots")
    public List<ReviewSnapshotView> history(@PathVariable UUID id) {
        authorization.requireRole(Role.APPROVER, Role.OPERATOR);
        return queries.history(id);
    }

    @GetMapping("/{id}/review-snapshots/latest")
    public ReviewSnapshotView latest(@PathVariable UUID id) {
        authorization.requireRole(Role.APPROVER, Role.OPERATOR);
        return queries.latest(id);
    }

    @GetMapping("/{id}/review-snapshots/{number}")
    public ReviewSnapshotView get(@PathVariable UUID id, @PathVariable int number) {
        authorization.requireRole(Role.APPROVER, Role.OPERATOR);
        return queries.get(id, number);
    }

    @GetMapping("/{id}/review-snapshots/{number}/freshness")
    public ReviewFreshness freshness(@PathVariable UUID id, @PathVariable int number) {
        authorization.requireRole(Role.APPROVER, Role.OPERATOR);
        return queries.freshness(id, number);
    }

    @GetMapping("/{id}/review-decisions")
    public List<ReviewDecisionView> decisions(@PathVariable UUID id) {
        authorization.requireRole(Role.APPROVER, Role.OPERATOR);
        return queries.decisions(id);
    }

    @PostMapping("/{id}/mapping-decisions")
    public ResponseEntity<MappingDecisionResult> recordMapping(
            @PathVariable UUID id, @Valid @RequestBody RecordMappingDecisionRequest request) {
        authorization.requireRole(Role.APPROVER);
        CommandResult<MappingDecisionResult> result = commands.recordMapping(new RecordMappingDecisionCommand(
                id,
                request.requestId(),
                request.expectedCaseVersion(),
                request.reviewSnapshotId(),
                request.reviewPayloadHash(),
                request.lineNumber(),
                request.itemId()));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/{id}/supplement-requests")
    public ResponseEntity<ReviewDecisionView> requestSupplement(
            @PathVariable UUID id, @Valid @RequestBody SupplementRequestRequest request) {
        authorization.requireRole(Role.APPROVER);
        CommandResult<ReviewDecisionView> result = commands.requestSupplement(new RequestSupplementCommand(
                id,
                request.requestId(),
                request.expectedCaseVersion(),
                request.reviewSnapshotId(),
                request.reviewPayloadHash(),
                request.reason()));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ReviewDecisionView> reject(
            @PathVariable UUID id, @Valid @RequestBody RejectReviewRequest request) {
        authorization.requireRole(Role.APPROVER);
        CommandResult<ReviewDecisionView> result = commands.reject(new RejectReviewCommand(
                id,
                request.requestId(),
                request.expectedCaseVersion(),
                request.reviewSnapshotId(),
                request.reviewPayloadHash(),
                request.reason()));
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
