package com.invoicematch.core.invoicecase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Store-level idempotency invariants against real PostgreSQL: a committed
 * record must carry a response, and a loser waiting on a conflicting
 * reservation proceeds once the winner rolls back.
 */
class RequestIdempotencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SCOPE = "test:scope";
    private static final String RESOURCE = "resource";
    private static final String REQUEST = "req";
    private static final String HASH = "hash";
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    private RequestIdempotencyStore idempotency;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate table idempotency_record");
        jdbc.execute("alter table idempotency_record enable trigger trg_idempotency_record_complete");
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void findRejectsCommittedIncompleteRecord() {
        jdbc.execute("alter table idempotency_record disable trigger trg_idempotency_record_complete");
        try {
            jdbc.update(
                    "insert into idempotency_record (id, scope, resource_key, request_id, request_hash, created_at)"
                            + " values (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    SCOPE,
                    RESOURCE,
                    REQUEST,
                    HASH,
                    Timestamp.from(T0));

            assertThatThrownBy(() -> idempotency.find(SCOPE, RESOURCE, REQUEST))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            jdbc.update("delete from idempotency_record");
            jdbc.execute("alter table idempotency_record enable trigger trg_idempotency_record_complete");
        }
    }

    @Test
    void loserCanReserveAfterWinnerRollsBack() throws Exception {
        CountDownLatch winnerReserved = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = pool.submit(() -> {
                try {
                    transactions.executeWithoutResult(status -> {
                        idempotency.begin(SCOPE, RESOURCE, REQUEST, HASH);
                        winnerReserved.countDown();
                        awaitQuietly(releaseWinner);
                        throw new IllegalStateException("forced rollback");
                    });
                } catch (IllegalStateException expected) {
                    // The winner transaction is rolled back on purpose.
                }
            });
            assertThat(winnerReserved.await(5, TimeUnit.SECONDS)).isTrue();

            Future<RequestIdempotencyStore.BeginResult> loser = pool.submit(() -> transactions.execute(status -> {
                RequestIdempotencyStore.BeginResult result = idempotency.begin(SCOPE, RESOURCE, REQUEST, HASH);
                if (result instanceof RequestIdempotencyStore.BeginResult.Started) {
                    idempotency.recordResponse(SCOPE, RESOURCE, REQUEST, 200, "{}");
                }
                return result;
            }));

            Thread.sleep(300);
            releaseWinner.countDown();
            winner.get(10, TimeUnit.SECONDS);

            assertThat(loser.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(RequestIdempotencyStore.BeginResult.Started.class);
            assertThat(idempotency.find(SCOPE, RESOURCE, REQUEST)).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
