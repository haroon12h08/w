package com.wbank.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.information.AffordabilityService;
import com.wbank.obligation.history.CreditPolicy;
import com.wbank.obligation.history.CreditPolicyService;
import com.wbank.research.CounterfactualEvaluator;
import com.wbank.research.SnapshotDecisionFacts;
import com.wbank.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V13 against a production-like V12 database: the V9 lending book upgraded through V10–V12 with
 * the phase 6 and 7 artefacts of {@link ResearchArtifactsBook}. The upgrade must change no
 * existing row, create empty research tables, and leave every Phase 6 and Phase 7 hash
 * reproducible.
 */
class V13OutcomeResearchMigrationTest extends PostgresIntegrationTest {

    static final List<String> NEW_TABLES = List.of("research_outcome_definition", "research_cohort_definition",
            "research_outcome_report", "research_counterfactual_outcome");

    @Autowired SnapshotDecisionFacts snapshotFacts;
    @Autowired CounterfactualEvaluator evaluator;
    @Autowired CreditPolicyService policies;
    @Autowired AffordabilityService affordability;
    @Autowired ObjectMapper appJson;

    private void migrate(DriverManagerDataSource ds, String schema, String target) {
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").target(target).load().migrate();
    }

    @Test
    void upgradingAV12DatabaseChangesNothingAndEveryEarlierHashReproduces() throws Exception {
        String schema = "v13_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        migrate(ds, schema, "9");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ProductionLikeLendingBook builder = new ProductionLikeLendingBook(jdbc);
        ProductionLikeLendingBook.Book book = builder.buildAtV9(tx);
        migrate(ds, schema, "12");
        ResearchArtifactsBook research = new ResearchArtifactsBook(jdbc, tx, evaluator, snapshotFacts, affordability,
                appJson, policies.require(CreditPolicy.LENDING, 1));
        ResearchArtifactsBook.Artifacts a = research.buildAtV12(builder, book);

        String before = ResearchArtifactsBook.fingerprint(jdbc, NEW_TABLES);
        migrate(ds, schema, "13");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version = '13'",
                Integer.class)).isEqualTo(1);

        // no banking row and no Phase 5/6/7 row changed; the new research tables start empty
        assertThat(ResearchArtifactsBook.fingerprint(jdbc, NEW_TABLES)).isEqualTo(before);
        for (String t : NEW_TABLES) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + t, Long.class)).as(t).isZero();
        }
        research.assertPhase5To7Reproduce(a);

        // the new tables enforce the intervention boundary in the upgraded schema
        jdbc.update("""
                INSERT INTO research_outcome_definition (code, version, definition, definition_hash, created_at, created_by)
                VALUES ('DEFAULT_90D', 1, '{"event":"DEFAULT","horizonDays":90}', ?, now(), 't')""", "c".repeat(64));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO research_counterfactual_outcome (id, counterfactual_id, source_decision_id,
                    outcome_definition_code, outcome_definition_version, known_at, actual_decision, counterfactual_decision,
                    actual_outcome_status, actual_outcome_value, counterfactual_outcome, evaluated_at)
                VALUES (?, ?, ?, 'DEFAULT_90D', 1, now(), 'APPROVED', 'DECLINED', 'OBSERVED', 'OCCURRED', 'OCCURRED', now())""",
                UUID.randomUUID(), a.cfId(), a.d1())).hasStackTraceContaining("cfo_counterfactual_unobserved");
    }
}
