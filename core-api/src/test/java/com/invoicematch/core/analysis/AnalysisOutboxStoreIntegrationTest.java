package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.analysis.persistence.AnalysisOutboxStore;
import com.invoicematch.core.analysis.persistence.ClaimedAnalysisRequest;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * P2-06 claim/lease/finalize store against real PostgreSQL: one due READY row
 * per claim for a QUEUED run, unique tokens across concurrent workers, DB-time
 * lease recovery, old-token rejection, bounded backoff release, stale-run
 * cancellation and conditional cancel that preserves a terminal PUBLISHED row.
 */
class AnalysisOutboxStoreIntegrationTest extends AbstractAnalysisRelayIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(60);

    @Autowired
    AnalysisOutboxStore store;

    @Autowired
    DataSource dataSource;

    @Test
    void claimOneClaimsDueReadyRequestForQueuedRunExactlyOnce() {
        UUID caseId = createDraftCase("INV-S1");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-s1");
        UUID eventId = outboxId(caseId, 1);

        ClaimedAnalysisRequest claimed = store.claimOne("w1", LEASE).orElseThrow();
        assertThat(claimed.eventId()).isEqualTo(eventId);
        assertThat(claimed.claimToken()).isNotNull();
        assertThat(claimed.payload()).isEqualTo(outboxRow(caseId, 1).get("payload"));

        Map<String, Object> row = outboxRow(caseId, 1);
        assertThat(row.get("status")).isEqualTo("CLAIMED");
        assertThat(row.get("claim_token")).isEqualTo(claimed.claimToken());
        assertThat(row.get("lease_until")).isNotNull();
        assertThat((Integer) row.get("attempt_count")).isEqualTo(1);

        // No longer READY: a second claim from another worker gets nothing.
        assertThat(store.claimOne("w2", LEASE)).isEmpty();
    }

    @Test
    void finalizeRequiresTheCurrentTokenAndAnUnexpiredLease() {
        UUID caseId = createDraftCase("INV-S2");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-s2");
        UUID eventId = outboxId(caseId, 1);
        ClaimedAnalysisRequest claimed = store.claimOne("w1", LEASE).orElseThrow();

        assertThat(store.finalizePublished(eventId, UUID.randomUUID())).isFalse();
        assertThat(outboxRow(caseId, 1).get("status")).isEqualTo("CLAIMED");

        assertThat(store.finalizePublished(eventId, claimed.claimToken())).isTrue();
        Map<String, Object> published = outboxRow(caseId, 1);
        assertThat(published.get("status")).isEqualTo("PUBLISHED");
        assertThat(published.get("published_at")).isNotNull();
        assertThat(published.get("claim_token")).isNull();
        assertThat(published.get("lease_until")).isNull();

        // Terminal: a repeat finalize cannot change it.
        assertThat(store.finalizePublished(eventId, claimed.claimToken())).isFalse();
    }

    @Test
    void releaseReturnsToReadyWithBackoffAndClassificationCode() {
        UUID caseId = createDraftCase("INV-S3");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-s3");
        UUID eventId = outboxId(caseId, 1);
        ClaimedAnalysisRequest claimed = store.claimOne("w1", LEASE).orElseThrow();

        assertThat(store.releaseForRetry(eventId, claimed.claimToken(), "CONNECT_FAILED", Duration.ofSeconds(30)))
                .isTrue();
        Map<String, Object> row = outboxRow(caseId, 1);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("last_error_code")).isEqualTo("CONNECT_FAILED");
        assertThat((Integer) row.get("attempt_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select next_attempt_at > clock_timestamp() from analysis_request_outbox where id = ?",
                        Boolean.class,
                        eventId))
                .isTrue();
        // A stale token can no longer release this row.
        assertThat(store.releaseForRetry(eventId, claimed.claimToken(), "X", Duration.ofSeconds(1))).isFalse();
    }

    @Test
    void recoverExpiredReturnsLeaseToReadyAndOldTokenCannotFinalize() {
        UUID caseId = createDraftCase("INV-S4");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-s4");
        UUID eventId = outboxId(caseId, 1);
        ClaimedAnalysisRequest stale = store.claimOne("w1", Duration.ofSeconds(1)).orElseThrow();
        // The deadline is immutable outside a transition; wait for the real lease
        // to lapse, then recover.
        awaitLeaseExpiry(eventId);
        assertThat(store.recoverExpired()).isEqualTo(1);
        Map<String, Object> recovered = outboxRow(caseId, 1);
        assertThat(recovered.get("status")).isEqualTo("READY");
        assertThat(recovered.get("claim_token")).isNull();
        assertThat(recovered.get("lease_until")).isNull();

        ClaimedAnalysisRequest current = store.claimOne("w2", LEASE).orElseThrow();
        assertThat(current.claimToken()).isNotEqualTo(stale.claimToken());
        assertThat(store.finalizePublished(eventId, stale.claimToken())).isFalse();
        assertThat(store.finalizePublished(eventId, current.claimToken())).isTrue();
    }

    @Test
    void recoverExpiredCancelsRequestsOfAStaleRun() {
        UUID caseId = createDraftCase("INV-S5");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-s5");
        UUID eventId = outboxId(caseId, 1);
        UUID runId = (UUID) outboxRow(caseId, 1).get("analysis_run_id");
        store.claimOne("w1", LEASE).orElseThrow();

        // Business supersedes the run while the relay holds the claim.
        jdbc.update("update analysis_run set status = 'STALE', updated_at = now() where id = ?", runId);
        store.recoverExpired();

        Map<String, Object> row = outboxRow(caseId, 1);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("lease_until")).isNull();
        // Once cancelled it can never be claimed or finalized.
        assertThat(store.claimOne("w3", LEASE)).isEmpty();
        assertThat(store.finalizePublished(eventId, UUID.randomUUID())).isFalse();
    }

    @Test
    void conditionalCancelPreservesPublishedButCancelsReadyAndClaimed() {
        UUID readyCase = createDraftCase("INV-S6A");
        seedCompletedDocument(readyCase);
        submit(readyCase, "submit-s6a");
        UUID readyRun = (UUID) outboxRow(readyCase, 1).get("analysis_run_id");
        assertThat(store.cancelSuperseded(readyRun)).isEqualTo(1);
        assertThat(outboxRow(readyCase, 1).get("status")).isEqualTo("CANCELLED");

        UUID claimedCase = createDraftCase("INV-S6B");
        seedCompletedDocument(claimedCase);
        submit(claimedCase, "submit-s6b");
        UUID claimedRun = (UUID) outboxRow(claimedCase, 1).get("analysis_run_id");
        store.claimOne("w1", LEASE).orElseThrow();
        assertThat(store.cancelSuperseded(claimedRun)).isEqualTo(1);
        assertThat(outboxRow(claimedCase, 1).get("status")).isEqualTo("CANCELLED");

        UUID publishedCase = createDraftCase("INV-S6C");
        seedCompletedDocument(publishedCase);
        submit(publishedCase, "submit-s6c");
        UUID publishedRun = (UUID) outboxRow(publishedCase, 1).get("analysis_run_id");
        ClaimedAnalysisRequest claimed = store.claimOne("w1", LEASE).orElseThrow();
        assertThat(store.finalizePublished(claimed.eventId(), claimed.claimToken())).isTrue();
        assertThat(store.cancelSuperseded(publishedRun)).isZero();
        assertThat(outboxRow(publishedCase, 1).get("status")).isEqualTo("PUBLISHED");
    }

    @Test
    void twoWorkersClaimDistinctRequestsWithoutDuplication() throws Exception {
        UUID firstCase = createDraftCase("INV-S7A");
        seedCompletedDocument(firstCase);
        submit(firstCase, "submit-s7a");
        UUID secondCase = createDraftCase("INV-S7B");
        seedCompletedDocument(secondCase);
        submit(secondCase, "submit-s7b");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<UUID> claimed = new CopyOnWriteArrayList<>();
        try {
            Future<?> a = pool.submit(() -> claimAfter(start, "worker-a", claimed));
            Future<?> b = pool.submit(() -> claimAfter(start, "worker-b", claimed));
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(claimed).hasSize(2).doesNotHaveDuplicates();
        assertThat(claimed).containsExactlyInAnyOrder(outboxId(firstCase, 1), outboxId(secondCase, 1));
    }

    @Test
    void skipLockedClaimsAnotherDueRowWhileTheOldestIsLocked() throws Exception {
        UUID firstCase = createDraftCase("INV-S8A");
        seedCompletedDocument(firstCase);
        submit(firstCase, "submit-s8a");
        UUID secondCase = createDraftCase("INV-S8B");
        seedCompletedDocument(secondCase);
        submit(secondCase, "submit-s8b");
        UUID lockedId = outboxId(firstCase, 1);
        UUID otherId = outboxId(secondCase, 1);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("select id from analysis_request_outbox where id = '" + lockedId
                        + "' for update");
            }
            long start = System.nanoTime();
            Optional<ClaimedAnalysisRequest> claimed = store.claimOne("skip-worker", LEASE);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            assertThat(claimed).as("SKIP LOCKED must not block on the locked row").isPresent();
            assertThat(claimed.get().eventId()).isEqualTo(otherId);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
            holder.rollback();
        }
        assertThat(outboxRow(secondCase, 1).get("status")).isEqualTo("CLAIMED");
        assertThat(outboxRow(firstCase, 1).get("status")).isEqualTo("READY");
    }

    @Test
    void supplementSubmitConditionallyCancelsAClaimedReservation() {
        UUID caseId = createDraftCase("INV-S9");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-s9-v1");
        ClaimedAnalysisRequest claimed = store.claimOne("w1", LEASE).orElseThrow();
        assertThat(outboxRow(caseId, 1).get("status")).isEqualTo("CLAIMED");

        advanceToSupplementDraft(caseId, "s9");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-s9-v2");

        assertThat(run(caseId, 1).get("status")).isEqualTo("STALE");
        Map<String, Object> superseded = outboxRow(caseId, 1);
        assertThat(superseded.get("status")).isEqualTo("CANCELLED");
        assertThat(superseded.get("claim_token")).isNull();
        assertThat(superseded.get("lease_until")).isNull();
        assertThat(claimed.eventId()).isNotNull();
        assertThat(outboxRow(caseId, 2).get("status")).isEqualTo("READY");
    }

    @Test
    void supplementSubmitPreservesAPublishedReservation() {
        UUID caseId = createDraftCase("INV-S10");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-s10-v1");
        ClaimedAnalysisRequest claimed = store.claimOne("w1", LEASE).orElseThrow();
        assertThat(store.finalizePublished(claimed.eventId(), claimed.claimToken())).isTrue();

        advanceToSupplementDraft(caseId, "s10");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-s10-v2");

        assertThat(run(caseId, 1).get("status")).isEqualTo("STALE");
        Map<String, Object> published = outboxRow(caseId, 1);
        assertThat(published.get("status")).isEqualTo("PUBLISHED");
        assertThat(published.get("published_at")).isNotNull();
        assertThat(outboxRow(caseId, 2).get("status")).isEqualTo("READY");
    }

    @Test
    void supplementAuditFailureRollsBackTheClaimedCancellationAndRunStale() {
        UUID caseId = createDraftCase("INV-S11");
        seedCompletedDocument(caseId);
        submit(caseId, "submit-s11-v1");
        ClaimedAnalysisRequest claimed = store.claimOne("w1", LEASE).orElseThrow();
        Map<String, Object> before = outboxRow(caseId, 1);
        Object tokenBefore = before.get("claim_token");
        Object leaseBefore = before.get("lease_until");

        advanceToSupplementDraft(caseId, "s11");
        seedCompletedDocument(caseId);
        jdbc.execute("alter table audit_entry add constraint test_s11_audit_failure"
                + " check (action <> 'CASE_SUBMITTED') not valid");
        try {
            assertThatThrownBy(() -> submit(caseId, "submit-s11-v2")).isInstanceOf(RuntimeException.class);
            Map<String, Object> after = outboxRow(caseId, 1);
            assertThat(after.get("status")).isEqualTo("CLAIMED");
            assertThat(after.get("claim_token")).isEqualTo(tokenBefore);
            assertThat(after.get("lease_until")).isEqualTo(leaseBefore);
            assertThat(run(caseId, 1).get("status")).isEqualTo("QUEUED");
        } finally {
            jdbc.execute("alter table audit_entry drop constraint test_s11_audit_failure");
        }

        submit(caseId, "submit-s11-v2");
        assertThat(run(caseId, 1).get("status")).isEqualTo("STALE");
        assertThat(outboxRow(caseId, 1).get("status")).isEqualTo("CANCELLED");
        assertThat(outboxRow(caseId, 2).get("status")).isEqualTo("READY");
        assertThat(claimed.eventId()).isNotNull();
    }

    private void claimAfter(CountDownLatch start, String workerId, List<UUID> claimed) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        store.claimOne(workerId, LEASE).ifPresent(entry -> claimed.add(entry.eventId()));
    }
}
