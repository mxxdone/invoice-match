package com.invoicematch.core.analysis.application;

import com.invoicematch.core.analysis.domain.AnalysisRequestOutbox;
import com.invoicematch.core.analysis.domain.AnalysisRun;
import com.invoicematch.core.analysis.domain.AnalysisRunStatus;
import com.invoicematch.core.analysis.domain.AnalysisWorkflow;
import com.invoicematch.core.analysis.persistence.AnalysisOutboxStore;
import com.invoicematch.core.analysis.persistence.AnalysisRequestOutboxRepository;
import com.invoicematch.core.analysis.persistence.AnalysisRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserves and supersedes the analysis work of a submission. It joins the
 * caller's submission transaction ({@code MANDATORY}), so the reservation, the
 * cancellation of the superseded requests, the frozen bundle, the audit record
 * and the idempotency response all commit or roll back together.
 *
 * <p>Every new frozen bundle stales the lower evidence-version reservations of
 * the same case and cancels their ready requests regardless of the enable flag
 * or whether the new bundle carries documents, so a correction can never leave
 * a stale request publishable. A new run and request are only reserved when
 * analysis is enabled and the bundle actually froze documents; a document-less
 * legacy bundle is never analyzed. Replays never reach this service because the
 * submission returns its stored response first.
 */
@Service
@EnableConfigurationProperties(AnalysisRequestProperties.class)
public class AnalysisRequestService {

    private final AnalysisRunRepository runs;
    private final AnalysisRequestOutboxRepository outboxes;
    private final AnalysisOutboxStore outboxStore;
    private final AnalysisRequestPayloadFactory payloads;
    private final AnalysisRequestProperties properties;
    private final Clock clock;

    public AnalysisRequestService(
            AnalysisRunRepository runs,
            AnalysisRequestOutboxRepository outboxes,
            AnalysisOutboxStore outboxStore,
            AnalysisRequestPayloadFactory payloads,
            AnalysisRequestProperties properties,
            Clock clock) {
        this.runs = runs;
        this.outboxes = outboxes;
        this.outboxStore = outboxStore;
        this.payloads = payloads;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onEvidenceSubmitted(AnalysisInput input) {
        Instant now = clock.instant();

        List<AnalysisRun> superseded = runs
                .findByInvoiceCaseIdAndStatusAndInputVersionLessThanOrderByInputVersionAsc(
                        input.invoiceCaseId(), AnalysisRunStatus.QUEUED, input.inputVersion());
        for (AnalysisRun run : superseded) {
            run.markStale(now);
            runs.save(run);
            // Conditional cancel, never entity dirty checking: this can only
            // move READY/CLAIMED to CANCELLED and can never overwrite a claim
            // or a terminal PUBLISHED row the relay owns.
            outboxStore.cancelSuperseded(run.id());
        }
        if (!superseded.isEmpty()) {
            runs.flush();
        }

        if (!properties.enabled() || input.documents().isEmpty()) {
            return;
        }

        AnalysisRun run = AnalysisRun.queue(
                UUID.randomUUID(),
                input.invoiceCaseId(),
                input.evidenceBundleId(),
                input.inputVersion(),
                input.evidencePayloadHash(),
                now);
        runs.saveAndFlush(run);

        UUID eventId = UUID.randomUUID();
        String payload = payloads.canonicalPayload(eventId, run, input.documents());
        outboxes.saveAndFlush(AnalysisRequestOutbox.requested(
                eventId, run.id(), AnalysisWorkflow.REQUEST_SCHEMA_VERSION, payload, now));
    }
}
