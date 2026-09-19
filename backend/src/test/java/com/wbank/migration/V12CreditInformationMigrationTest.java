package com.wbank.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.obligation.history.CreditPolicyRules;
import com.wbank.obligation.history.DecisionFacts;
import com.wbank.obligation.history.PolicyEngine;
import com.wbank.research.SnapshotDecisionFacts;
import com.wbank.research.SourceDecision;
import com.wbank.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
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
 * V12 against a production-like V11 database: a V9 book upgraded through V10 and V11, with a
 * version-1 decision snapshot and a Phase 6 counterfactual record. The upgrade must change no
 * existing row (banking or research), create empty information tables, and leave the version-1
 * snapshot evaluable exactly as before: it gains no information it never had.
 */
class V12CreditInformationMigrationTest extends PostgresIntegrationTest {

    static final Instant APPLIED = Instant.parse("2025-04-01T09:00:00Z");
    static final Instant DECIDED = Instant.parse("2025-04-01T10:00:00Z");

    @Autowired SnapshotDecisionFacts snapshotFacts;

    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;

    private String fingerprint() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                   AND table_name <> 'flyway_schema_history'
                   AND table_name NOT IN ('borrower_financial_fact', 'affordability_assessment')
                 ORDER BY table_name""", String.class);
        StringBuilder sb = new StringBuilder();
        for (String t : tables) {
            sb.append(t).append('=').append(jdbc.queryForObject(
                    "SELECT md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM " + t + " x", String.class))
                    .append(';');
        }
        return sb.toString();
    }

    private void migrate(DriverManagerDataSource ds, String schema, String target) {
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").target(target).load().migrate();
    }

    @Test
    void upgradingAV11DatabaseChangesNothingAndOldSnapshotsGainNoInformation() throws Exception {
        String schema = "v12_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        migrate(ds, schema, "9");
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ProductionLikeLendingBook builder = new ProductionLikeLendingBook(jdbc);
        ProductionLikeLendingBook.Book book = builder.buildAtV9(tx);
        migrate(ds, schema, "11");

        // A decision with a version-1 snapshot, and a Phase 6 counterfactual of it.
        UUID decisionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID cf = UUID.randomUUID();
        String[] written = tx.execute(s -> {
            UUID position = builder.account(book.customer(), book.party(), "TERM_LOAN", "LOAN", null, "AV12-L4");
            UUID obligation = builder.obligation("LV12-4", book.party(), position, book.deposit(), 150_000, 700, 6, APPLIED);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("schema", "credit-decision-snapshot/v1");
            snapshot.put("decision", Map.of("kind", "APPROVED", "decidedAt", DECIDED.toString(), "decidedBy", "officer-1"));
            snapshot.put("subject", Map.of("partyId", book.party().toString(), "customerId", book.customer().toString(),
                    "customerStatus", "ACTIVE"));
            snapshot.put("application", Map.of("obligationId", obligation.toString(), "currency", "USD",
                    "principalMinor", 150_000L, "installmentCount", 6, "annualRateBps", 700,
                    "settlementAccountId", book.deposit().toString()));
            snapshot.put("accounts", List.of(Map.of("accountId", book.deposit().toString(), "availableMinor", 500_000L)));
            snapshot.put("existingObligations", List.of());
            String canonical = CanonicalJson.canonical(json, json.valueToTree(snapshot));
            String hash = CanonicalJson.sha256(canonical);
            jdbc.update("""
                    INSERT INTO lending_event (id, obligation_id, loan_seq, event_type, event_kind, effective_at, recorded_at,
                        payload, actor, actor_type, origin)
                    VALUES (?, ?, 1, 'APPLICATION_RECEIVED', 'FACT', ?, ?, ?::jsonb, 'officer-1', 'HUMAN', 'LIVE')""",
                    UUID.randomUUID(), obligation, Timestamp.from(APPLIED), Timestamp.from(APPLIED), """
                    {"obligationNumber":"LV12-4","debtorPartyId":"%s","productCode":"TERM_LOAN","currency":"USD",
                     "principalMinor":150000,"annualRateBps":700,"installmentCount":6}""".formatted(book.party()));
            jdbc.update("UPDATE obligation SET status = 'APPROVED', approved_at = ? WHERE id = ?",
                    Timestamp.from(DECIDED), obligation);
            jdbc.update("""
                    INSERT INTO loan_decision (id, obligation_id, decision, decided_by, decider_type, correlation_id,
                        decided_at, rationale, evidence, policy_code, policy_version)
                    VALUES (?, ?, 'APPROVED', 'officer-1', 'HUMAN', ?, ?, 'within policy', '{}', 'LENDING_CREDIT_POLICY', 1)""",
                    decisionId, obligation, UUID.randomUUID(), Timestamp.from(DECIDED));
            jdbc.update("""
                    INSERT INTO credit_decision_snapshot (id, decision_id, obligation_id, policy_code, policy_version,
                        decided_at, content, content_sha256) VALUES (?, ?, ?, 'LENDING_CREDIT_POLICY', 1, ?, ?::jsonb, ?)""",
                    snapshotId, decisionId, obligation, Timestamp.from(DECIDED), canonical, hash);
            jdbc.update("""
                    INSERT INTO lending_event (id, obligation_id, loan_seq, event_type, event_kind, effective_at, recorded_at,
                        payload, source_table, source_id, actor, actor_type, origin)
                    VALUES (?, ?, 2, 'CREDIT_DECISION', 'DECISION', ?, ?, ?::jsonb, 'loan_decision', ?, 'officer-1', 'HUMAN', 'LIVE')""",
                    UUID.randomUUID(), obligation, Timestamp.from(DECIDED), Timestamp.from(DECIDED), """
                    {"decisionId":"%s","decision":"APPROVED","policyCode":"LENDING_CREDIT_POLICY","policyVersion":1,
                     "snapshotId":"%s","snapshotSha256":"%s","evidence":{}}""".formatted(decisionId, snapshotId, hash),
                    decisionId);
            String hex = "b".repeat(64);
            jdbc.update("""
                    INSERT INTO research_counterfactual_decision (id, source_decision_id, source_snapshot_id,
                        source_snapshot_sha256, actual_decision, actual_policy_code, actual_policy_version,
                        alternative_policy_code, alternative_policy_version, original_decided_at, evaluated_at,
                        evaluated_by, input, input_hash, rule_results, policy_result, control_policy_result,
                        hypothetical_decision, output_hash, context_check)
                    VALUES (?, ?, ?, ?, 'APPROVED', 'LENDING_CREDIT_POLICY', 1, 'LENDING_CREDIT_POLICY', 1, ?, ?, 'test',
                        '{}', ?, '[]', 'PASS', 'PASS', 'APPROVED', ?, 'CONSISTENT')""",
                    cf, decisionId, snapshotId, hash, Timestamp.from(DECIDED), Timestamp.from(DECIDED.plusSeconds(60)),
                    hex, hex);
            return new String[] {obligation.toString(), hash};
        });
        UUID obligation = UUID.fromString(written[0]);
        String sha = written[1];

        JsonNode content = json.readTree(jdbc.queryForObject(
                "SELECT content::text FROM credit_decision_snapshot WHERE id = ?", String.class, snapshotId));
        SourceDecision src = new SourceDecision(decisionId, obligation, "APPROVED", DECIDED, 2, "LENDING_CREDIT_POLICY",
                1, snapshotId, sha, content);
        CreditPolicyRules v1 = json.readValue(jdbc.queryForObject(
                "SELECT rules::text FROM credit_policy WHERE policy_code = 'LENDING_CREDIT_POLICY' AND version = 1",
                String.class), CreditPolicyRules.class);
        DecisionFacts factsBefore = snapshotFacts.facts(src);
        PolicyEngine.Evaluation evaluationBefore = PolicyEngine.evaluate(v1.approvalRules(2), factsBefore);

        String before = fingerprint();
        migrate(ds, schema, "12");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version = '12'",
                Integer.class)).isEqualTo(1);

        // 1. No existing row changed, banking or research; the new tables start empty.
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM borrower_financial_fact", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM affordability_assessment", Long.class)).isZero();

        // 2. The version-1 snapshot is intact and evaluates exactly as before; it has no information fields.
        JsonNode after = json.readTree(jdbc.queryForObject(
                "SELECT content::text FROM credit_decision_snapshot WHERE id = ?", String.class, snapshotId));
        assertThat(CanonicalJson.sha256(CanonicalJson.canonical(json, after))).isEqualTo(sha);
        DecisionFacts factsAfter = snapshotFacts.facts(new SourceDecision(decisionId, obligation, "APPROVED", DECIDED, 2,
                "LENDING_CREDIT_POLICY", 1, snapshotId, sha, after));
        assertThat(factsAfter).isEqualTo(factsBefore);
        assertThat(factsAfter.verifiedMonthlyIncomeMinor()).isNull();
        assertThat(factsAfter.debtServiceRatioBps()).isNull();
        assertThat(factsAfter.informationCompleteness()).isNull();
        assertThat(PolicyEngine.evaluate(v1.approvalRules(2), factsAfter)).isEqualTo(evaluationBefore);

        // 3. A research policy that requires information finds none in the old snapshot: never PASS.
        CreditPolicyRules needsInfo = new CreditPolicyRules(v1.requireActiveCustomer(), v1.maxPrincipalMajor(),
                v1.maxInstallments(), v1.maxAnnualRateBps(), v1.blockIfAnyObligationDefaulted(),
                v1.maxExistingDaysPastDue(), v1.defaultDeclarationMinDaysPastDue(), null, 4_000L, true);
        assertThat(PolicyEngine.evaluate(needsInfo.approvalRules(2), factsAfter).result())
                .isNotEqualTo(PolicyEngine.Status.PASS);

        // 4. The new tables enforce their invariants in the upgraded schema.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO borrower_financial_fact (id, party_id, series_id, fact_kind, fact_type, status, amount_minor,
                    currency, frequency, provenance, source, effective_at, recorded_at, recorded_by, recorder_type)
                VALUES (?, ?, ?, 'INCOME', 'SALARY', 'ACTIVE', 1, 'USD', 'MONTHLY', 'DECLARED', 't', ?, ?, 't', 'SYSTEM')""",
                UUID.randomUUID(), book.party(), UUID.randomUUID(), Timestamp.from(DECIDED.plusSeconds(1)),
                Timestamp.from(DECIDED))).hasStackTraceContaining("fact_not_future_dated");
        UUID fact = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO borrower_financial_fact (id, party_id, series_id, fact_kind, fact_type, status, amount_minor,
                    currency, frequency, provenance, source, effective_at, recorded_at, recorded_by, recorder_type)
                VALUES (?, ?, ?, 'INCOME', 'SALARY', 'ACTIVE', 1, 'USD', 'MONTHLY', 'DECLARED', 't', ?, ?, 't', 'SYSTEM')""",
                fact, book.party(), UUID.randomUUID(), Timestamp.from(DECIDED), Timestamp.from(DECIDED));
        assertThatThrownBy(() -> jdbc.update("UPDATE borrower_financial_fact SET amount_minor = 2 WHERE id = ?", fact))
                .hasStackTraceContaining("append-only");
    }
}
