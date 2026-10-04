package com.invoicematch.core.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the application against a disposable real PostgreSQL instance. The
 * container is a JVM-wide singleton so that Spring's context cache always sees
 * the same mapped port, and migrations run through Flyway exactly as they do in
 * production.
 *
 * <p>The container raises {@code max_connections} and the pools keep a small
 * {@code minimum-idle}, because the suite caches several Spring contexts that
 * each own a connection pool; without this the idle pools exhaust PostgreSQL's
 * default connection limit. The pool still scales up to {@code 8} on demand for
 * the real concurrency tests.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName.parse(
                    System.getenv().getOrDefault("INVOICE_MATCH_TEST_POSTGRES_IMAGE","postgres:18-alpine"))
                    .asCompatibleSubstituteFor("postgres")).withCommand("postgres", "-c", "max_connections=400");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    /**
     * Asserts a raw JDBC statement is rejected by the database with the exact
     * root SQLSTATE. This prevents a different CHECK/FK/unique failure from
     * standing in for the guard under test: foreign key is {@code 23503},
     * unique is {@code 23505}, a raised integrity guard is {@code 23000} and a
     * raised check_transition is {@code 23514}. No PostgreSQL compile
     * dependency is needed; the SQLSTATE comes from {@code java.sql.SQLException}.
     */
    protected static void assertDatabaseRejects(String expectedSqlState, Runnable statement) {
        Throwable thrown = catchThrowable(statement::run);
        assertThat(thrown)
                .as("statement must be rejected by the database")
                .isInstanceOf(DataAccessException.class);
        SQLException sql = rootSqlException(thrown);
        assertThat((Throwable) sql).as("a root SQLException carrying a SQLSTATE must be present").isNotNull();
        assertThat(sql.getSQLState()).as("root SQLSTATE").isEqualTo(expectedSqlState);
    }

    private static SQLException rootSqlException(Throwable thrown) {
        SQLException found = null;
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                found = sql;
            }
        }
        return found;
    }
}
