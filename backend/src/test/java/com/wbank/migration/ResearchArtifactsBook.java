package com.wbank.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.information.AffordabilityCalculator;
import com.wbank.information.AffordabilityService;
import com.wbank.information.FactView;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.obligation.history.CreditPolicy;
import com.wbank.obligation.history.LendingEventType;
import com.wbank.obligation.history.LoanHistoryFold;
import com.wbank.research.CounterfactualDecision;
import com.wbank.research.CounterfactualEvaluator;
import com.wbank.research.SnapshotDecisionFacts;
import com.wbank.research.SourceDecision;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The research artefacts of phases 6 and 7 on top of the production-like lending book, written
 * exactly as the application writes them: a version-1 decision snapshot with a Phase 6
 * counterfactual of it (real hashes), and a version-2 snapshot capturing a verified income fact
 * with its affordability assessment. Shared by the migration regressions from V12 onwards, with
 * the checks that every one of their hashes still reproduces.
 */
final class ResearchArtifactsBook {

    static final Instant APPLIED = Instant.parse("2025-05-01T09:00:00Z");
    static final Instant DECIDED_V1 = Instant.parse("2025-05-01T10:00:00Z");
    static final Instant FACT_AT = Instant.parse("2025-05-02T08:00:00Z");
    static final Instant DECIDED_V2 = Instant.parse("2025-05-02T10:00:00Z");

    record Artifacts(ProductionLikeLendingBook.Book book, UUID d1, UUID s1, UUID cfId, UUID d2, UUID s2) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CounterfactualEvaluator evaluator;
    private final SnapshotDecisionFacts snapshotFacts;
    private final AffordabilityService affordability;
    private final ObjectMapper appJson;
    private final CreditPolicy lending;
    private final ObjectMapper json = new ObjectMapper();

    ResearchArtifactsBook(JdbcTemplate jdbc, TransactionTemplate tx, CounterfactualEvaluator evaluator,
                          SnapshotDecisionFacts snapshotFacts, AffordabilityService affordability, ObjectMapper appJson,
                          CreditPolicy lending) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.evaluator = evaluator;
        this.snapshotFacts = snapshotFacts;
        this.affordability = affordability;
        this.appJson = appJson;
        this.lending = lending;
    }

    /** Requires a schema at V12 holding {@code book}. */
    Artifacts buildAtV12(ProductionLikeLendingBook builder, ProductionLikeLendingBook.Book book) throws Exception {
        // a version-1 snapshot and a Phase 6 counterfactual of it, with its real hashes
        UUID d1 = UUID.randomUUID();
        UUID s1 = UUID.randomUUID();
        tx.executeWithoutResult(t -> decision(builder, book, "LVR-4", d1, s1, DECIDED_V1,
                baseSnapshot("credit-decision-snapshot/v1", book, DECIDED_V1)));
        CounterfactualDecision cf = evaluator.prepare(source(d1, s1, DECIDED_V1), lending, snapshotFacts, null);
        UUID cfId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO research_counterfactual_decision (id, source_decision_id, source_snapshot_id,
                    source_snapshot_sha256, actual_decision, actual_policy_code, actual_policy_version,
                    alternative_policy_code, alternative_policy_version, original_decided_at, evaluated_at,
                    evaluated_by, input, input_hash, rule_results, policy_result, control_policy_result,
                    hypothetical_decision, output_hash, context_check)
                VALUES (?, ?, ?, ?, 'APPROVED', 'LENDING_CREDIT_POLICY', 1, 'LENDING_CREDIT_POLICY', 1, ?, ?, 'test',
                    ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, 'CONSISTENT')""",
                cfId, d1, s1, cf.getSourceSnapshotSha256(), Timestamp.from(DECIDED_V1),
                Timestamp.from(DECIDED_V1.plusSeconds(60)), cf.getInput(), cf.getInputHash(), cf.getRuleResults(),
                cf.getPolicyResult(), cf.getControlPolicyResult(), cf.getHypotheticalDecision(), cf.getOutputHash());

        // Phase 7: a verified income fact, a version-2 snapshot capturing it, and an assessment
        UUID fact = UUID.randomUUID();
        UUID series = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO borrower_financial_fact (id, party_id, series_id, fact_kind, fact_type, status, amount_minor,
                    currency, frequency, provenance, source, evidence_reference, effective_at, recorded_at, recorded_by,
                    recorder_type)
                VALUES (?, ?, ?, 'INCOME', 'SALARY', 'ACTIVE', 400000, 'USD', 'MONTHLY', 'VERIFIED', 'payslip', 'ps-1',
                    ?, ?, 'officer-1', 'HUMAN')""", fact, book.party(), series, Timestamp.from(FACT_AT),
                Timestamp.from(FACT_AT));
        long seq = jdbc.queryForObject("SELECT seq FROM borrower_financial_fact WHERE id = ?", Long.class, fact);
        FactView view = new FactView(fact, seq, series, "INCOME", "SALARY", "ACTIVE", 400_000L, "USD", "MONTHLY", null,
                null, null, "VERIFIED", "payslip", "ps-1", null, FACT_AT, FACT_AT);
        AffordabilityCalculator.Input input = new AffordabilityCalculator.Input(AffordabilityCalculator.VERSION,
                DECIDED_V2, DECIDED_V2, "USD", 25_540, List.of(view), List.of());
        AffordabilityService.Computed info = affordability.run(new AffordabilityCalculator(), input, List.of(view));
        Map<String, Object> v2 = baseSnapshot("credit-decision-snapshot/v2", book, DECIDED_V2);
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("calculationVersion", AffordabilityCalculator.VERSION);
        section.put("asOf", DECIDED_V2.toString());
        section.put("knownAt", DECIDED_V2.toString());
        section.put("input", appJson.readTree(info.inputJson()));
        section.put("inputHash", info.inputHash());
        section.put("output", info.output());
        section.put("outputHash", info.outputHash());
        v2.put("financialInformation", json.valueToTree(section));
        UUID d2 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        tx.executeWithoutResult(t -> decision(builder, book, "LVR-5", d2, s2, DECIDED_V2, v2));
        jdbc.update("""
                INSERT INTO affordability_assessment (id, party_id, purpose, calculation_version, as_of, known_at, input,
                    input_hash, output, output_hash, completeness, computed_at)
                VALUES (?, ?, 'DECISION', ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?)""", UUID.randomUUID(), book.party(),
                AffordabilityCalculator.VERSION, Timestamp.from(DECIDED_V2), Timestamp.from(DECIDED_V2), info.inputJson(),
                info.inputHash(), info.outputJson(), info.outputHash(), info.completeness(), Timestamp.from(DECIDED_V2));
        return new Artifacts(book, d1, s1, cfId, d2, s2);
    }

    /** Both snapshots verify; the Phase 6 counterfactual and the Phase 7 information state reproduce. */
    void assertPhase5To7Reproduce(Artifacts a) throws Exception {
        for (UUID s : List.of(a.s1(), a.s2())) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT content::text AS c, content_sha256 FROM credit_decision_snapshot WHERE id = ?", s);
            assertThat(CanonicalJson.sha256(CanonicalJson.canonical(json, json.readTree((String) row.get("c")))))
                    .isEqualTo(row.get("content_sha256"));
        }
        CounterfactualDecision again = evaluator.prepare(source(a.d1(), a.s1(), DECIDED_V1), lending, snapshotFacts, null);
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT input_hash, output_hash FROM research_counterfactual_decision WHERE id = ?", a.cfId());
        assertThat(again.getInputHash()).isEqualTo(stored.get("input_hash"));
        assertThat(again.getOutputHash()).isEqualTo(stored.get("output_hash"));

        JsonNode section2 = json.readTree(jdbc.queryForObject(
                "SELECT content::text FROM credit_decision_snapshot WHERE id = ?", String.class, a.s2()))
                .get("financialInformation");
        AffordabilityCalculator.Input storedInput = appJson.treeToValue(section2.get("input"),
                AffordabilityCalculator.Input.class);
        AffordabilityService.Computed recomputed = affordability.run(new AffordabilityCalculator(), storedInput, List.of());
        assertThat(recomputed.inputHash()).isEqualTo(section2.get("inputHash").asText());
        assertThat(recomputed.outputHash()).isEqualTo(section2.get("outputHash").asText());
        assertThat(recomputed.outputHash()).isEqualTo(jdbc.queryForObject(
                "SELECT output_hash FROM affordability_assessment WHERE party_id = ?", String.class, a.book().party()));
    }

    /** A digest of every table except those listed. */
    static String fingerprint(JdbcTemplate jdbc, List<String> excluded) {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                   AND table_name <> 'flyway_schema_history' ORDER BY table_name""", String.class);
        StringBuilder sb = new StringBuilder();
        for (String t : tables) {
            if (excluded.contains(t)) {
                continue;
            }
            sb.append(t).append('=').append(jdbc.queryForObject(
                    "SELECT md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM " + t + " x", String.class))
                    .append(';');
        }
        return sb.toString();
    }

    /** A loan's lending history, read as the application reads it. */
    List<LoanHistoryFold.Event> history(UUID obligationId) {
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

    @SuppressWarnings("unchecked")
    private void decision(ProductionLikeLendingBook builder, ProductionLikeLendingBook.Book book, String number,
                          UUID decisionId, UUID snapshotId, Instant decided, Map<String, Object> snapshot) {
        UUID position = builder.account(book.customer(), book.party(), "TERM_LOAN", "LOAN", null, "A" + number);
        UUID obligation = builder.obligation(number, book.party(), position, book.deposit(), 150_000, 700, 6, APPLIED);
        ((Map<String, Object>) snapshot.get("application")).put("obligationId", obligation.toString());
        String canonical = CanonicalJson.canonical(json, json.valueToTree(snapshot));
        String hash = CanonicalJson.sha256(canonical);
        jdbc.update("""
                INSERT INTO lending_event (id, obligation_id, loan_seq, event_type, event_kind, effective_at, recorded_at,
                    payload, actor, actor_type, origin)
                VALUES (?, ?, 1, 'APPLICATION_RECEIVED', 'FACT', ?, ?, ?::jsonb, 'officer-1', 'HUMAN', 'LIVE')""",
                UUID.randomUUID(), obligation, Timestamp.from(APPLIED), Timestamp.from(APPLIED), """
                {"obligationNumber":"%s","debtorPartyId":"%s","productCode":"TERM_LOAN","currency":"USD",
                 "principalMinor":150000,"annualRateBps":700,"installmentCount":6}""".formatted(number, book.party()));
        jdbc.update("UPDATE obligation SET status = 'APPROVED', approved_at = ? WHERE id = ?", Timestamp.from(decided),
                obligation);
        jdbc.update("""
                INSERT INTO loan_decision (id, obligation_id, decision, decided_by, decider_type, correlation_id,
                    decided_at, rationale, evidence, policy_code, policy_version)
                VALUES (?, ?, 'APPROVED', 'officer-1', 'HUMAN', ?, ?, 'within policy', '{}', 'LENDING_CREDIT_POLICY', 1)""",
                decisionId, obligation, UUID.randomUUID(), Timestamp.from(decided));
        jdbc.update("""
                INSERT INTO credit_decision_snapshot (id, decision_id, obligation_id, policy_code, policy_version,
                    decided_at, content, content_sha256) VALUES (?, ?, ?, 'LENDING_CREDIT_POLICY', 1, ?, ?::jsonb, ?)""",
                snapshotId, decisionId, obligation, Timestamp.from(decided), canonical, hash);
        jdbc.update("""
                INSERT INTO lending_event (id, obligation_id, loan_seq, event_type, event_kind, effective_at, recorded_at,
                    payload, source_table, source_id, actor, actor_type, origin)
                VALUES (?, ?, 2, 'CREDIT_DECISION', 'DECISION', ?, ?, ?::jsonb, 'loan_decision', ?, 'officer-1', 'HUMAN', 'LIVE')""",
                UUID.randomUUID(), obligation, Timestamp.from(decided), Timestamp.from(decided), """
                {"decisionId":"%s","decision":"APPROVED","policyCode":"LENDING_CREDIT_POLICY","policyVersion":1,
                 "snapshotId":"%s","snapshotSha256":"%s","evidence":{}}""".formatted(decisionId, snapshotId, hash),
                decisionId);
    }

    private static Map<String, Object> baseSnapshot(String schemaVersion, ProductionLikeLendingBook.Book book,
                                                    Instant decided) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("schema", schemaVersion);
        s.put("decision", Map.of("kind", "APPROVED", "decidedAt", decided.toString(), "decidedBy", "officer-1"));
        s.put("subject", Map.of("partyId", book.party().toString(), "customerId", book.customer().toString(),
                "customerStatus", "ACTIVE"));
        s.put("application", new LinkedHashMap<>(Map.of("currency", "USD", "principalMinor", 150_000L,
                "installmentCount", 6, "annualRateBps", 700, "settlementAccountId", book.deposit().toString())));
        s.put("accounts", List.of(Map.of("accountId", book.deposit().toString(), "availableMinor", 500_000L)));
        s.put("existingObligations", List.of());
        return s;
    }

    SourceDecision source(UUID decisionId, UUID snapshotId, Instant decided) throws Exception {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT obligation_id, content::text AS c, content_sha256 FROM credit_decision_snapshot WHERE id = ?",
                snapshotId);
        return new SourceDecision(decisionId, (UUID) row.get("obligation_id"), "APPROVED", decided, 2,
                CreditPolicy.LENDING, 1, snapshotId, (String) row.get("content_sha256"), json.readTree((String) row.get("c")));
    }
}
