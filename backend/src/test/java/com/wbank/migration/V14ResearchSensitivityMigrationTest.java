package com.wbank.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.information.AffordabilityService;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.obligation.history.CreditPolicy;
import com.wbank.obligation.history.CreditPolicyService;
import com.wbank.research.CounterfactualEvaluator;
import com.wbank.research.OutcomeEvaluator;
import com.wbank.research.OutcomeResearchService;
import com.wbank.research.OutcomeResearchService.KnowledgeBasis;
import com.wbank.research.SensitivityAnalysis;
import com.wbank.research.SnapshotDecisionFacts;
import com.wbank.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V14 against a production-like V13 database: the phase 6 and 7 artefacts of
 * {@link ResearchArtifactsBook} plus Phase 8 research artefacts (an outcome definition, a cohort
 * definition and an outcome report with its real hashes). The upgrade must change no existing
 * row, create an empty sensitivity table, and leave every Phase 6, 7 and 8 hash reproducible;
 * the new sensitivity analysis must then run deterministically over the upgraded data.
 */
class V14ResearchSensitivityMigrationTest extends PostgresIntegrationTest {

    static final List<String> NEW_TABLES = List.of("research_sensitivity_report");
    static final Instant COHORT_KNOWN_AT = Instant.parse("2025-06-01T00:00:00Z");
    static final Instant REPORT_KNOWN_AT = Instant.parse("2025-12-01T00:00:00Z");

    @Autowired SnapshotDecisionFacts snapshotFacts;
    @Autowired CounterfactualEvaluator evaluator;
    @Autowired CreditPolicyService policies;
    @Autowired AffordabilityService affordability;
    @Autowired OutcomeResearchService outcomeResearch;
    @Autowired ObjectMapper appJson;

    private void migrate(DriverManagerDataSource ds, String schema, String target) {
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").target(target).load().migrate();
    }

    private static OutcomeResearchService.CohortDefinition cohort() {
        return new OutcomeResearchService.CohortDefinition("MIG_COHORT", 1, Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), COHORT_KNOWN_AT, List.of("APPROVED", "DECLINED"), false, "migration");
    }

    private static OutcomeEvaluator.Definition default90() {
        return new OutcomeEvaluator.Definition("DEFAULT_90D", 1, OutcomeEvaluator.Event.DEFAULT, null, 90);
    }

    /** The cohort's membership, read from the schema as the service reads it (decision events and snapshots). */
    private OutcomeResearchService.Membership membership(JdbcTemplate jdbc, ResearchArtifactsBook research,
                                                         ResearchArtifactsBook.Artifacts a) throws Exception {
        List<OutcomeResearchService.Member> members = new ArrayList<>();
        for (UUID decision : List.of(a.d1(), a.d2())) {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT e.obligation_id, e.loan_seq, e.effective_at, s.content::text AS c, s.content_sha256
                      FROM lending_event e JOIN credit_decision_snapshot s ON s.decision_id = e.source_id
                     WHERE e.event_type = 'CREDIT_DECISION' AND e.source_id = ?""", decision);
            UUID obligation = (UUID) row.get("obligation_id");
            JsonNode content = appJson.readTree((String) row.get("c"));
            members.add(new OutcomeResearchService.Member(new OutcomeEvaluator.Subject(decision, obligation, "APPROVED",
                    ((Timestamp) row.get("effective_at")).toInstant(), (Integer) row.get("loan_seq"),
                    research.history(obligation)), (String) row.get("content_sha256"),
                    OutcomeResearchService.snapshotState(content)));
        }
        return OutcomeResearchService.membershipOf(cohort(), members, List.of(), appJson);
    }

    private String hash(Object o) {
        return CanonicalJson.sha256(CanonicalJson.canonical(appJson, appJson.valueToTree(o)));
    }

    @Test
    void upgradingAV13DatabaseChangesNothingAndEveryEarlierResearchHashReproduces() throws Exception {
        String schema = "v14_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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
        migrate(ds, schema, "13");

        // Phase 8: definitions and a report, written as the application writes them
        Map<String, Object> outcomeDef = new LinkedHashMap<>();
        outcomeDef.put("code", "DEFAULT_90D");
        outcomeDef.put("version", 1);
        outcomeDef.put("event", "DEFAULT");
        outcomeDef.put("thresholdDaysPastDue", null);
        outcomeDef.put("horizonDays", 90);
        outcomeDef.put("evaluatorVersion", OutcomeEvaluator.VERSION);
        for (var def : List.of(Map.entry("research_outcome_definition", (Object) outcomeDef),
                Map.entry("research_cohort_definition", (Object) OutcomeResearchService.cohortJson(cohort())))) {
            String canonical = CanonicalJson.canonical(appJson, appJson.valueToTree(def.getValue()));
            jdbc.update("INSERT INTO " + def.getKey() + " (code, version, definition, definition_hash, created_at, created_by)"
                    + " VALUES (?, 1, ?::jsonb, ?, ?, 'researcher')", def.getKey().contains("outcome") ? "DEFAULT_90D"
                    : "MIG_COHORT", canonical, CanonicalJson.sha256(canonical), Timestamp.from(COHORT_KNOWN_AT));
        }
        OutcomeResearchService.Membership m = membership(jdbc, research, a);
        Map<String, Object> report = outcomeResearch.build(m, List.of(default90()), KnowledgeBasis.AS_KNOWN_AT,
                REPORT_KNOWN_AT, new OutcomeEvaluator());
        UUID reportId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO research_outcome_report (id, cohort_code, cohort_version, outcome_definitions, knowledge_basis,
                    known_at, evaluated_at, evaluated_by, population_hash, report, output_hash)
                VALUES (?, 'MIG_COHORT', 1, '[{"code":"DEFAULT_90D","version":1}]', 'AS_KNOWN_AT', ?, ?, 'researcher', ?,
                    ?::jsonb, ?)""", reportId, Timestamp.from(REPORT_KNOWN_AT), Timestamp.from(REPORT_KNOWN_AT),
                m.membershipHash(), CanonicalJson.canonical(appJson, appJson.valueToTree(report)), hash(report));

        String before = ResearchArtifactsBook.fingerprint(jdbc, NEW_TABLES);
        migrate(ds, schema, "14");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version = '14'",
                Integer.class)).isEqualTo(1);

        // no banking row, lending event, snapshot, or Phase 5-8 research row changed; the new table is empty
        assertThat(ResearchArtifactsBook.fingerprint(jdbc, NEW_TABLES)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM research_sensitivity_report", Long.class)).isZero();

        // Phase 6 and 7 hashes reproduce
        research.assertPhase5To7Reproduce(a);

        // Phase 8: definitions still match their creation hashes, and the report reproduces exactly
        for (String table : List.of("research_outcome_definition", "research_cohort_definition")) {
            Map<String, Object> row = jdbc.queryForMap("SELECT definition::text AS d, definition_hash FROM " + table);
            assertThat(CanonicalJson.sha256(CanonicalJson.canonical(appJson, appJson.readTree((String) row.get("d")))))
                    .as(table).isEqualTo(row.get("definition_hash"));
        }
        OutcomeResearchService.Membership after = membership(jdbc, research, a);
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT population_hash, output_hash FROM research_outcome_report WHERE id = ?", reportId);
        assertThat(after.membershipHash()).isEqualTo(stored.get("population_hash"));
        assertThat(hash(outcomeResearch.build(after, List.of(default90()), KnowledgeBasis.AS_KNOWN_AT, REPORT_KNOWN_AT,
                new OutcomeEvaluator()))).isEqualTo(stored.get("output_hash"));

        // the sensitivity analysis runs over the upgraded data, deterministically
        var resolved = new SensitivityAnalysis.Resolved(after, default90(), KnowledgeBasis.AS_KNOWN_AT, REPORT_KNOWN_AT,
                "completeness");
        Map<String, Object> first = new SensitivityAnalysis(new OutcomeEvaluator()).horizons(resolved, List.of(30, 90, 365));
        assertThat(hash(new SensitivityAnalysis(new OutcomeEvaluator()).horizons(resolved, List.of(30, 90, 365))))
                .isEqualTo(hash(first));
        JsonNode steps = appJson.valueToTree(first).get("steps");
        assertThat(steps.at("/0/selection/accountingHolds").asBoolean()).isTrue();
        assertThat(steps.at("/0/totals/notApplicable").asInt()).isEqualTo(2);   // approved, never disbursed
        assertThat(steps.at("/2/totals/censored").asInt()).isEqualTo(2);        // 365 days not yet elapsed

        // the new table is append-only in the upgraded schema
        UUID id = UUID.randomUUID();
        String hex = "d".repeat(64);
        jdbc.update("""
                INSERT INTO research_sensitivity_report (id, kind, configuration, configuration_hash, input_hash,
                    calculation_version, report, output_hash, evaluated_at, evaluated_by)
                VALUES (?, 'HORIZON', '{}', ?, ?, 'v', '{}', ?, now(), 't')""", id, hex, hex, hex);
        assertThatThrownBy(() -> jdbc.update("UPDATE research_sensitivity_report SET kind = 'KNOWLEDGE' WHERE id = ?", id))
                .hasStackTraceContaining("append-only");
    }
}
