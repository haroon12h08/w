package com.wbank.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for every test that needs the database.
 *
 * <p>Runs against a real, disposable PostgreSQL 16 (the production engine) with the real
 * Flyway migrations, so triggers, deferred constraints, row locks and advisory locks are
 * exercised exactly as they would be in production. One container per JVM
 * (singleton pattern): the ledger is append-only, so tests isolate themselves by opening
 * fresh accounts rather than by truncating tables.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("wbank")
                    .withUsername("wbank")
                    .withPassword("wbank")
                    .withEnv("TZ", "UTC")
                    .withEnv("PGTZ", "UTC")
                    .withCommand("postgres", "-c", "timezone=UTC", "-c", "max_connections=200");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "24");
    }
}
