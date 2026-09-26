package com.invoicematch.core.support;

import org.springframework.boot.test.context.SpringBootTest;
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
public abstract class AbstractPostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine").withCommand("postgres", "-c", "max_connections=400");

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
}
