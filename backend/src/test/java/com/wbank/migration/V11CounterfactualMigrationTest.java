package com.wbank.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.obligation.history.CreditPolicyRules;
import com.wbank.obligation.history.DecisionFacts;
import com.wbank.obligation.history.HistoricalLoanState;
import com.wbank.obligation.history.LendingEventType;
import com.wbank.obligation.history.LoanHistoryFold;
import com.wbank.obligation.history.PolicyEngine;
import com.wbank.research.SnapshotDecisionFacts;
import com.wbank.research.SourceDecision;
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
 * V11 against a production-like V10 lending database: a V9 book upgraded to V10 (backfilled
 * history), plus a decision made under V10 (policy version + hashed snapshot). The upgrade
 * must leave every banking row untouched, and the pre-existing snapshot must be evaluable by
 * the new counterfactual engine and consistent with the backfilled history.
 */
class V11CounterfactualMigrationTest extends PostgresIntegrationTest {

    static final Instant APPLIED = Instant.parse("2025-03-01T09:00:00Z");
    static final Instant DECIDED = Instant.parse("2025-03-01T10:00:00Z");

    @Autowired SnapshotDecisionFacts snapshotFacts;

    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;

    private String fingerprint() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                   AND table_name NOT LIKE 'research_%' AND table_name <> 'flyway_schema_history'
                 ORDER BY table_name""", String.class);
        StringBuilder sb = new StringBuilder();
        for (String t : tables) {
            sb.append(t).append('=').append(jdbc.queryForObject(
                    "SELECT md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM " + t + " x", String.class))
                    .append(';');
        }
        return sb.toString();
    }

    private List<LoanHistoryFold.Event> history(UUID obligationId) {
        return jdbc.query("""
                SELECT id, loan_seq, global_seq, event_type, effective_at, recorded_at, payload::text, corrects_event_id, actor
                  FROM lending_event WHERE obligation_id = ? ORDER BY loan_seq""", (rs, i) -> {
            try {
                return new LoanHistoryFold.Event(rs.getObject(1, UUID.class), rs.getInt(2), rs.getLong(3),
                        LendingEventType.valueOf(rs.getString(4)), rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6).toInstant(), json.readTree(rs.getString(7)), rs.getObject(8, UUID.class),
                        rs.getString(9));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, obligationId);
    }

    @Test
    void upgradingAV10LendingDatabaseLeavesHistoryIntactAndItsSnapshotsEvaluable() throws Exception {
        String schema = "v11_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").target("9").load().migrate();
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ProductionLikeLendingBook builder = new ProductionLikeLendingBook(jdbc);
        ProductionLikeLendingBook.Book book = builder.buildAtV9(tx);
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").target("10").load().migrate();

        // A decision taken under V10, written in one transaction exactly as the application writes it:
        // application + its event, policy v1 approval, hashed snapshot, decision event.
        List<Map<String, Object>> existing = new ArrayList<>();
        for (UUID other : book.loans()) {
            HistoricalLoanState h = LoanHistoryFold.fold(other, history(other), DECIDED, DECIDED);
            existing.add(Map.of("obligationId", other.toString(), "status", h.status(),
                    "outstandingPrincipalMinor", h.outstandingPrincipalMinor(),
                    "daysPastDue", h.derivedDelinquency() == null ? 0L : h.derivedDelinquency().daysPastDue()));
        }
        long depositBalance = jdbc.queryForObject("""
                SELECT b.balance_minor FROM ledger_account_balance b JOIN account a ON a.ledger_account_id = b.ledger_account_id
                 WHERE a.id = ?""", Long.class, book.deposit());
        UUID decisionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        String[] written = tx.execute(s -> {
            UUID position = builder.account(book.customer(), book.party(), "TERM_LOAN", "LOAN", null, "AV11-L4");
            UUID obligation = builder.obligation("LV11-4", book.party(), position, book.deposit(), 150_000, 700, 6, APPLIED);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("schema", "credit-decision-snapshot/v1");
            snapshot.put("decision", Map.of("kind", "APPROVED", "decidedAt", DECIDED.toString(), "decidedBy", "officer-1"));
            snapshot.put("subject", Map.of("partyId", book.party().toString(), "customerId", book.customer().toString(),
                    "customerStatus", "ACTIVE"));
            snapshot.put("application", Map.of("obligationId", obligation.toString(), "currency", "USD",
                    "principalMinor", 150_000L, "installmentCount", 6, "annualRateBps", 700,
                    "settlementAccountId", book.deposit().toString()));
            snapshot.put("accounts", List.of(Map.of("accountId", book.deposit().toString(),
                    "ledgerBalanceMinor", depositBalance, "reservedMinor", 0L, "availableMinor", depositBalance)));
            snapshot.put("existingObligations", existing);
            String canonical = CanonicalJson.canonical(json, json.valueToTree(snapshot));
            String hash = CanonicalJson.sha256(canonical);
            jdbc.update("""
                    INSERT INTO lending_event (id, obligation_id, loan_seq, event_type, event_kind, effective_at, recorded_at,
                        payload, actor, actor_type, origin)
                    VALUES (?, ?, 1, 'APPLICATION_RECEIVED', 'FACT', ?, ?, ?::jsonb, 'officer-1', 'HUMAN', 'LIVE')""",
                    UUID.randomUUID(), obligation, Timestamp.from(APPLIED), Timestamp.from(APPLIED), """
                    {"obligationNumber":"LV11-4","debtorPartyId":"%s","productCode":"TERM_LOAN","currency":"USD",
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
            return new String[] {obligation.toString(), hash};
        });
        UUID obligation = UUID.fromString(written[0]);
        String sha = written[1];

        String before = fingerprint();
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").target("11").load().migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version = '11'",
                Integer.class)).isEqualTo(1);
        // 1. Nothing in the banking record changed.
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM research_counterfactual_decision", Long.class)).isZero();

        // 2. The V10 snapshot is intact and evaluable by the new engine, using only its own content.
        String stored = jdbc.queryForObject("SELECT content::text FROM credit_decision_snapshot WHERE id = ?", String.class,
                snapshotId);
        JsonNode content = json.readTree(stored);
        assertThat(CanonicalJson.sha256(CanonicalJson.canonical(json, content))).isEqualTo(sha);
        SourceDecision src = new SourceDecision(decisionId, obligation, "APPROVED", DECIDED, 2, "LENDING_CREDIT_POLICY", 1,
                snapshotId, sha, content);
        DecisionFacts facts = snapshotFacts.facts(src);
        CreditPolicyRules v1 = json.readValue(jdbc.queryForObject(
                "SELECT rules::text FROM credit_policy WHERE policy_code = 'LENDING_CREDIT_POLICY' AND version = 1",
                String.class), CreditPolicyRules.class);
        PolicyEngine.Evaluation actual = PolicyEngine.evaluate(v1.approvalRules(2), facts);
        assertThat(actual.result()).isEqualTo(PolicyEngine.Status.PASS); // replay of the policy actually used
        CreditPolicyRules tighter = new CreditPolicyRules(true, 1_000, 360, 3_000, true, 29, 90, null);
        assertThat(PolicyEngine.evaluate(tighter.approvalRules(2), facts).result()).isEqualTo(PolicyEngine.Status.FAIL);
        assertThat(PolicyEngine.evaluate(v1.approvalRules(2), facts)).isEqualTo(actual); // deterministic

        // 3. The snapshot agrees with the backfilled history as known at decision time.
        for (JsonNode o : content.get("existingObligations")) {
            UUID other = UUID.fromString(o.get("obligationId").asText());
            HistoricalLoanState h = LoanHistoryFold.fold(other, history(other), DECIDED, DECIDED);
            assertThat(h.status()).isEqualTo(o.get("status").asText());
            assertThat(h.outstandingPrincipalMinor()).isEqualTo(o.get("outstandingPrincipalMinor").asLong());
        }

        // 4. The pre-V10 decision (backfilled, no snapshot) stays not evaluable; nothing is fabricated.
        UUID legacyDecision = jdbc.queryForObject("SELECT id FROM loan_decision WHERE obligation_id = ?", UUID.class,
                book.loans()[0]);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_decision_snapshot WHERE decision_id = ?", Long.class,
                legacyDecision)).isZero();

        // 5. Research records can reference the historical decision, and are immutable.
        UUID cf = UUID.randomUUID();
        String hex = "a".repeat(64);
        jdbc.update("""
                INSERT INTO research_counterfactual_decision (id, source_decision_id, source_snapshot_id, source_snapshot_sha256,
                    actual_decision, actual_policy_code, actual_policy_version, alternative_policy_code, alternative_policy_version,
                    original_decided_at, evaluated_at, evaluated_by, input, input_hash, rule_results, policy_result,
                    control_policy_result, hypothetical_decision, output_hash, context_check)
                VALUES (?, ?, ?, ?, 'APPROVED', 'LENDING_CREDIT_POLICY', 1, 'LENDING_CREDIT_POLICY', 1, ?, now(), 'test',
                    '{}', ?, '[]', 'PASS', 'PASS', 'APPROVED', ?, 'CONSISTENT')""",
                cf, decisionId, snapshotId, sha, Timestamp.from(DECIDED), hex, hex);
        assertThatThrownBy(() -> jdbc.update("UPDATE research_counterfactual_decision SET hypothetical_decision = 'DECLINED' WHERE id = ?", cf))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO research_counterfactual_decision (id, source_decision_id, source_snapshot_id, actual_decision,
                    alternative_policy_code, alternative_policy_version, original_decided_at, evaluated_at, evaluated_by, input,
                    input_hash, rule_results, policy_result, hypothetical_decision, output_hash, context_check)
                VALUES (?, ?, ?, 'APPROVED', 'LENDING_CREDIT_POLICY', 1, ?, now(), 't', '{}', ?, '[]', 'FAIL', 'APPROVED', ?, 'CONSISTENT')""",
                UUID.randomUUID(), decisionId, snapshotId, Timestamp.from(DECIDED), hex, hex))
                .hasStackTraceContaining("cf_hypothetical_follows_result");
    }

}
