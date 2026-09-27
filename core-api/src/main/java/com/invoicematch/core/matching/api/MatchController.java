package com.invoicematch.core.matching.api;

import com.invoicematch.core.invoicecase.application.CommandResult;
import com.invoicematch.core.matching.application.MatchResultView;
import com.invoicematch.core.matching.application.MatchingService;
import com.invoicematch.core.matching.application.RunMatchCommand;
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
 * Deterministic matching API. Running a match is a write (it appends an
 * immutable result) so it uses {@code POST}; reading the latest result and the
 * append-only history use {@code GET} under the same case resource.
 */
@RestController
@RequestMapping("/api/invoice-cases")
public class MatchController {

    private final MatchingService matching;
    private final AuthorizationService authorization;

    public MatchController(MatchingService matching, AuthorizationService authorization) {
        this.matching = matching;
        this.authorization = authorization;
    }

    @PostMapping("/{id}/match")
    public ResponseEntity<MatchResultView> run(
            @PathVariable UUID id, @Valid @RequestBody RunMatchRequest request) {
        authorization.requireRole(Role.OPERATOR);
        CommandResult<MatchResultView> result =
                matching.run(new RunMatchCommand(id, request.requestId()));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/{id}/match")
    public MatchResultView latest(@PathVariable UUID id) {
        authorization.requireRole(Role.APPROVER, Role.OPERATOR);
        return matching.latest(id);
    }

    @GetMapping("/{id}/matches")
    public List<MatchResultView> list(@PathVariable UUID id) {
        authorization.requireRole(Role.APPROVER, Role.OPERATOR);
        return matching.list(id);
    }
}
