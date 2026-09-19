package com.wbank.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.account.funds.FundsService;
import com.wbank.customer.CustomerService;
import com.wbank.obligation.LoanService;
import com.wbank.obligation.history.CreditDecisionSnapshotRepository;
import com.wbank.obligation.history.CreditPolicy;
import com.wbank.obligation.history.CreditPolicyRules;
import com.wbank.obligation.history.CreditPolicyService;
import com.wbank.obligation.history.DecisionFacts;
import com.wbank.obligation.history.DecisionReconstructionService;
import com.wbank.obligation.history.HistoricalLoanState;
import com.wbank.obligation.history.PointInTimeService;
import com.wbank.obligation.history.PolicyEngine;
import com.wbank.obligation.persistence.ObligationRepository;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.MutableClock;
import com.wbank.support.PostgresIntegrationTest;
import com.wbank.support.SyntheticLendingHistory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Counterfactual credit-decision evaluation over the synthetic seven-loan history (base 2028-01-04).
 *
 * <pre>
 *   story  actual     principal  decision-time facts            observed outcome
 *   A      APPROVED   3,000.00   clean                          repaid on schedule
 *   B      APPROVED   6,000.00   clean                          settled early
 *   C      APPROVED   3,000.00   clean                          35 DPD, cured, repaid
 *   D      APPROVED   3,000.00   clean                          defaulted
 *   E      APPROVED   3,000.00   clean                          defaulted, then repaid in full
 *   F      DECLINED  50,000.00   clean (discretionary decline)  none: never granted
 *   G      DECLINED   1,000.00   borrower's loan D DEFAULTED    none: never granted
 * </pre>
 *
 * Research policies (never in force for live lending):
 * RESEARCH_PRINCIPAL_LIMIT v1 (max 5,000.00) and v2 (max 2,000.00); RESEARCH_MIN_AVAILABLE v1
 * (settlement account must have 5,000.00 available at decision time); RESEARCH_PERMISSIVE v1.
 */
@Import(MutableClock.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CounterfactualIntegrationTest extends PostgresIntegrationTest {

    static final LocalDate BASE = LocalDate.of(2028, 1, 4);
    static final Instant FROM = Instant.parse("2028-01-01T00:00:00Z");
    static final Instant TO = Instant.parse("2029-01-01T00:00:00Z");
    static final String LIMIT = "RESEARCH_PRINCIPAL_LIMIT";
    static final String MIN_AVAILABLE = "RESEARCH_MIN_AVAILABLE";
    static final String PERMISSIVE = "RESEARCH_PERMISSIVE";

    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;
    @Autowired LoanService loans;
    @Autowired MutableClock clock;
    @Autowired CreditDecisionSnapshotRepository snapshotRepo;
    @Autowired CreditPolicyService policies;
    @Autowired CounterfactualEvaluator evaluator;
    @Autowired SnapshotDecisionFacts snapshotFacts;
    @Autowired PolicyReplayService replays;
    @Autowired ReplayOutcomeEvaluator outcomes;
    @Autowired DecisionReconstructionService reconstruct;
    @Autowired PointInTimeService pit;
    @Autowired ObligationRepository obligations;
    @Autowired CustomerService customers;
    @Autowired FundsService funds;
    @Autowired ObjectMapper json;

    SyntheticLendingHistory.Run run;
    String bankingFingerprint;

    @BeforeAll
    void setUp() {
        run = new SyntheticLendingHistory(d, loans, clock, snapshotRepo).generate(BASE);
        clock.set(run.end().plus(Duration.ofDays(3)));
        CreditPolicyRules v1 = policies.rules(policies.inForceAt(run.get("A").decidedAt()));
        publishOnce(LIMIT, 1, withPrincipal(v1, 5_000, v1.blockIfAnyObligationDefaulted(), v1.maxExistingDaysPastDue(), null),
                "principal limit 5,000.00");
        publishOnce(LIMIT, 2, withPrincipal(v1, 2_000, v1.blockIfAnyObligationDefaulted(), v1.maxExistingDaysPastDue(), null),
                "principal limit 2,000.00");
        publishOnce(MIN_AVAILABLE, 1, withPrincipal(v1, v1.maxPrincipalMajor(), v1.blockIfAnyObligationDefaulted(),
                v1.maxExistingDaysPastDue(), 5_000L), "settlement account must hold 5,000.00 available");
        publishOnce(PERMISSIVE, 1, withPrincipal(v1, 100_000, false, 1_000, null), "permissive research policy");
        bankingFingerprint = bankingFingerprint();
    }

    private static CreditPolicyRules withPrincipal(CreditPolicyRules b, long maxPrincipal, boolean blockDefaulted,
                                                   long maxDpd, Long minAvailable) {
        return new CreditPolicyRules(b.requireActiveCustomer(), maxPrincipal, b.maxInstallments(), b.maxAnnualRateBps(),
                blockDefaulted, maxDpd, b.defaultDeclarationMinDaysPastDue(), minAvailable);
    }

    private void publishOnce(String code, int version, CreditPolicyRules rules, String description) {
        if (policies.versions(code).stream().noneMatch(p -> p.getVersion() == version)) {
            clock.set(clock.instant().plusSeconds(1)); // versions of one code take effect at distinct instants
            policies.publish(code, rules, null, description);
        }
    }

    private UUID decision(String story) {
        return run.get(story).approvalDecisionId();
    }

    private CreditPolicy policy(String code, int version) {
        return policies.require(code, version);
    }

    private CounterfactualDecision compute(String story, CreditPolicy p, DecisionFactsSource source) {
        return evaluator.prepare(evaluator.source(decision(story)), p, source, null);
    }

    /** Runs {@code measure} after {@code perturb} inside a transaction that is always rolled back. */
    private <T> T hermetic(Consumer<JdbcTemplate> perturb, Supplier<T> measure) {
        return f.tx.execute(s -> {
            f.jdbc.execute("SET LOCAL session_replication_role = replica"); // triggers off, only here
            perturb.accept(f.jdbc);
            T result = measure.get();
            s.setRollbackOnly();
            return result;
        });
    }

    private String bankingFingerprint() {
        StringBuilder sb = new StringBuilder();
        for (String table : List.of("loan_decision", "credit_decision_snapshot", "lending_event", "obligation",
                "obligation_repayment", "interest_accrual", "delinquency_event", "journal_entry", "posting",
                "ledger_account_balance", "credit_policy")) {
            sb.append(table).append('=').append(f.jdbc.queryForObject(
                    "SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), '')) FROM " + table + " t",
                    String.class)).append(';');
        }
        return sb.toString();
    }

    // =============================================================== experiments

    @Test
    void experiment1_aPolicyChangeChangesAHistoricalDecision() {
        var v = replays.replay(new PolicyReplayService.Population("synthetic 2028 book", FROM, TO), LIMIT, 1);
        Map<String, PolicyReplayService.Row> byStory = byStory(v);
        assertThat(byStory).hasSize(7);
        // Only B (6,000.00) exceeds 5,000.00 among the approvals: its decision would have changed.
        assertThat(byStory.get("B").actual().decision()).isEqualTo("APPROVED");
        assertThat(byStory.get("B").counterfactual().getHypotheticalDecision()).isEqualTo("DECLINED");
        assertThat(byStory.get("B").differsFromActualDecision()).isTrue();
        assertThat(byStory.get("B").counterfactual().getControlPolicyResult()).isEqualTo("PASS");
        for (String s : List.of("A", "C", "D", "E")) {
            assertThat(byStory.get(s).differsFromActualDecision()).as(s).isFalse();
        }
        // F was declined at the officer's discretion although v1 permitted it; under the research
        // policy the rules themselves decline it: the policy verdict changes, the decision does not.
        assertThat(byStory.get("F").differsFromActualDecision()).isFalse();
        assertThat(byStory.get("F").differsFromActualPolicyVerdict()).isTrue();
        assertThat(byStory.get("F").counterfactual().getControlPolicyResult()).isEqualTo("PASS");
        assertThat(v.rows()).filteredOn(PolicyReplayService.Row::differsFromActualDecision).hasSize(1);
        JsonNode bRules = read(byStory.get("B").counterfactual().getRuleResults());
        JsonNode principal = StreamSupport.stream(bRules.spliterator(), false)
                .filter(r -> r.get("rule").asText().equals("PRINCIPAL_WITHIN_LIMIT")).findFirst().orElseThrow();
        assertThat(principal.get("observed").asLong()).isEqualTo(600_000);
        assertThat(principal.get("threshold").asLong()).isEqualTo(500_000);
        assertThat(principal.get("operator").asText()).isEqualTo("LTE");
        assertThat(principal.get("status").asText()).isEqualTo("FAIL");
    }

    @Test
    void experiment2_changingAPostDecisionOutcomeCannotChangeTheCounterfactual() {
        CreditPolicy v1 = policy(CreditPolicy.LENDING, 1);
        String before = compute("D", v1, snapshotFacts).getOutputHash();
        String after = hermetic(jdbc -> {
            // D defaulted in May; pretend it had instead been repaid, and erase its delinquency history.
            jdbc.update("""
                    UPDATE lending_event SET event_type = 'LOAN_SETTLED', event_kind = 'FACT', payload = '{}'
                     WHERE obligation_id = ? AND event_type = 'DEFAULT_DECLARED'""", run.get("D").obligationId());
            jdbc.update("DELETE FROM lending_event WHERE obligation_id = ? AND event_type = 'DELINQUENCY_CHANGED'",
                    run.get("D").obligationId());
            jdbc.update("UPDATE obligation SET status = 'SETTLED', settled_at = now() WHERE id = ?",
                    run.get("D").obligationId());
        }, () -> compute("D", v1, snapshotFacts).getOutputHash());
        assertThat(after).isEqualTo(before);
    }

    @Test
    void experiment3_evaluatingTheSamePolicyTwiceIsIdentical() {
        var first = replays.replay(new PolicyReplayService.Population("synthetic 2028 book", FROM, TO), LIMIT, 2);
        var second = replays.replay(new PolicyReplayService.Population("synthetic 2028 book", FROM, TO), LIMIT, 2);
        assertThat(second.replay().getId()).isNotEqualTo(first.replay().getId()); // two experiments...
        assertThat(second.replay().getInputHash()).isEqualTo(first.replay().getInputHash()); // ...same input
        assertThat(second.replay().getOutputHash()).isEqualTo(first.replay().getOutputHash()); // ...same result
        for (int i = 0; i < first.rows().size(); i++) {
            assertThat(second.rows().get(i).counterfactual().getOutputHash())
                    .isEqualTo(first.rows().get(i).counterfactual().getOutputHash());
        }
    }

    // ============================================================== required 1-12

    @Test
    void t01_counterfactualFactsComeFromTheSnapshotNotCurrentState() {
        SourceDecision src = evaluator.source(decision("D"));
        DecisionFacts facts = snapshotFacts.facts(src);
        assertThat(facts.principalMinor()).isEqualTo(src.snapshot().at("/application/principalMinor").asLong());
        assertThat(facts.anyExistingObligationDefaulted()).isFalse(); // at decision time D had no other loans
        assertThat(facts.settlementAvailableMinor()).isEqualTo(1_000_000);
        // Today D is DEFAULTED and its borrower's account has moved; none of it reaches the facts.
        assertThat(obligations.findById(run.get("D").obligationId()).orElseThrow().getStatus().name()).isEqualTo("DEFAULTED");
        assertThat(compute("D", policy(CreditPolicy.LENDING, 1), snapshotFacts).getHypotheticalDecision()).isEqualTo("APPROVED");
        assertThat(Set.of(SnapshotDecisionFacts.class.getDeclaredConstructors()[0].getParameterTypes()))
                .containsExactly(com.wbank.platform.money.CurrencyRegistry.class);
    }

    @Test
    void t02_futureEventsCannotInfluenceTheCounterfactual() {
        CreditPolicy v1 = policy(CreditPolicy.LENDING, 1);
        Map<String, String> before = new LinkedHashMap<>();
        for (String s : run.stories().keySet()) {
            before.put(s, compute(s, v1, snapshotFacts).getOutputHash());
        }
        Map<String, String> after = hermetic(jdbc -> {
            // Remove every event recorded after 2028-01-05 (everything after the decisions of A-F).
            for (var story : run.stories().values()) {
                jdbc.update("DELETE FROM lending_event WHERE recorded_at > '2028-01-05' AND obligation_id = ?",
                        story.obligationId());
            }
        }, () -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (String s : List.of("A", "B", "C", "D", "E", "F")) {
                m.put(s, compute(s, v1, snapshotFacts).getOutputHash());
            }
            return m;
        });
        after.forEach((s, h) -> assertThat(h).as(s).isEqualTo(before.get(s)));
    }

    @Test
    void t03_actualHistoricalDecisionsRemainUnchanged() {
        replays.replay(new PolicyReplayService.Population("synthetic 2028 book", FROM, TO), LIMIT, 2);
        var v = replays.replay(new PolicyReplayService.Population("synthetic 2028 book", FROM, TO), MIN_AVAILABLE, 1);
        outcomes.evaluate(v.replay().getId(), Duration.ofDays(180), null);
        evaluator.evaluate(decision("A"), LIMIT, 2);
        assertThat(bankingFingerprint()).isEqualTo(bankingFingerprint);
        // The actual decision still cites the policy it was made under.
        ActualDecision actual = evaluator.actual(decision("A"));
        assertThat(actual.decision()).isEqualTo("APPROVED");
        assertThat(actual.policyCode()).isEqualTo(CreditPolicy.LENDING);
        assertThat(actual.policyVersion()).isEqualTo(1);
        assertThat(f.count("SELECT count(*) FROM lending_event WHERE actor LIKE 'research%' OR payload::text LIKE '%RESEARCH_%'"))
                .isZero();
    }

    @Test
    void t04_replayingTheSamePolicyProducesIdenticalResults() {
        experiment3_evaluatingTheSamePolicyTwiceIsIdentical();
    }

    @Test
    void t05_differentPolicyVersionsProduceDifferentResultsWhenTheirRulesDiffer() {
        var v1 = byStory(replays.replay(new PolicyReplayService.Population("synthetic 2028 book", FROM, TO), LIMIT, 1));
        var v2 = byStory(replays.replay(new PolicyReplayService.Population("synthetic 2028 book", FROM, TO), LIMIT, 2));
        assertThat(v1.get("A").counterfactual().getHypotheticalDecision()).isEqualTo("APPROVED");
        assertThat(v2.get("A").counterfactual().getHypotheticalDecision()).isEqualTo("DECLINED");
        assertThat(v2.values()).allSatisfy(r -> assertThat(r.counterfactual().getHypotheticalDecision()).isEqualTo("DECLINED"));
        assertThat(v1.get("A").counterfactual().getInputHash()).isNotEqualTo(v2.get("A").counterfactual().getInputHash());
        // A, C, D and E presented identical facts at decision time: no policy over these facts can
        // separate the loans that later defaulted from those that were repaid.
        List<String> factsOf = new ArrayList<>();
        for (String s : List.of("A", "C", "D", "E")) {
            factsOf.add(read(v1.get(s).counterfactual().getInput()).get("facts").toString());
        }
        assertThat(factsOf).containsOnly(factsOf.get(0));
    }

    @Test
    void t06_ruleLevelEvaluationIsDeterministicAndReproducesTheRecordedDecision() {
        CreditPolicy actualPolicy = policy(CreditPolicy.LENDING, 1);
        for (var story : run.stories().values()) {
            SourceDecision src = evaluator.source(story.approvalDecisionId());
            DecisionFacts facts = snapshotFacts.facts(src);
            var rules = policies.rules(actualPolicy).approvalRules(facts.currencyScale());
            PolicyEngine.Evaluation first = PolicyEngine.evaluate(rules, facts);
            for (int i = 0; i < 25; i++) {
                assertThat(PolicyEngine.evaluate(rules, facts)).isEqualTo(first);
            }
            // Replaying the policy actually used reproduces, rule by rule, what was recorded at decision time.
            JsonNode recorded = src.snapshot().get("ruleEvaluation");
            assertThat(first.results()).hasSize(recorded.size());
            for (int i = 0; i < recorded.size(); i++) {
                var r = first.results().get(i);
                assertThat(r.rule()).isEqualTo(recorded.get(i).get("rule").asText());
                assertThat(r.passed()).isEqualTo(recorded.get(i).get("passed").asBoolean());
                assertThat(String.valueOf(r.observed())).isEqualTo(recorded.get(i).get("observed").asText());
                assertThat(String.valueOf(r.threshold())).isEqualTo(recorded.get(i).get("threshold").asText());
            }
        }
    }

    @Test
    void t07_historicalPolicyVersionsCannotBeRetroactivelyModified() {
        assertThat(rejection(() -> f.jdbc.update("UPDATE credit_policy SET rules = '{}'::jsonb WHERE policy_code = ?", LIMIT)))
                .contains("append-only");
        assertThat(rejection(() -> f.jdbc.update("DELETE FROM credit_policy WHERE policy_code = ?", LIMIT)))
                .contains("append-only");
        CreditPolicyRules any = policies.rules(policy(LIMIT, 1));
        assertThatThrownBy(() -> policies.publish(LIMIT, any, clock.instant().minus(Duration.ofDays(1)), "backdated"))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("past");
        CounterfactualDecision c = evaluator.evaluate(decision("A"), LIMIT, 1);
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE research_counterfactual_decision SET hypothetical_decision = 'APPROVED' WHERE id = ?", c.getId())))
                .contains("append-only");
    }

    @Test
    void t08_theEventualOutcomeIsNotAnInput() {
        CounterfactualDecision c = evaluator.evaluate(decision("D"), LIMIT, 1);
        JsonNode input = read(c.getInput());
        assertThat(fieldNames(input)).containsExactlyInAnyOrder("schema", "source", "facts", "policy");
        assertThat(fieldNames(input.get("source"))).containsExactlyInAnyOrder("decisionId", "snapshotId",
                "snapshotSha256", "decidedAt", "actualDecision", "actualPolicyCode", "actualPolicyVersion");
        // Version-1 fields always; a version-2 snapshot (Phase 7) may add only the information fields,
        // and only when they are known (an absent fact is omitted, never defaulted).
        List<String> v1Fields = List.of("customerStatus", "currency", "currencyScale", "principalMinor",
                "installmentCount", "annualRateBps", "anyExistingObligationDefaulted", "maxExistingDaysPastDue",
                "settlementAvailableMinor", "subjectDaysPastDue");
        assertThat(fieldNames(input.get("facts"))).containsAll(v1Fields);
        assertThat(fieldNames(input.get("facts"))).filteredOn(fname -> !v1Fields.contains(fname))
                .isSubsetOf("verifiedMonthlyIncomeMinor", "debtServiceRatioBps", "informationCompleteness");
        // The subject's own later state never appears; subjectDaysPastDue is for default decisions only.
        assertThat(input.at("/facts/subjectDaysPastDue").isNull()).isTrue();
        // No outcome-bearing value appears among the facts (rule ids such as NO_DEFAULTED_OBLIGATIONS are policy text).
        assertThat(input.get("facts").toString()).doesNotContain("DEFAULTED", "SETTLED", "REPAYMENT");
    }

    @Test
    void t09_declinedApplicantsRemainOutcomeUnknown() {
        var v = replays.replay(new PolicyReplayService.Population("synthetic 2028 book", FROM, TO), PERMISSIVE, 1);
        JsonNode report = read(outcomes.evaluate(v.replay().getId(), Duration.ofDays(180), null).getReport());
        assertThat(report.at("/outcomeUnknowable/historicallyDeclined").asInt()).isEqualTo(2);
        assertThat(report.at("/outcomeUnknowable/ofWhichCounterfactualWouldApprove").asInt()).isEqualTo(2);
        for (JsonNode row : report.get("rows")) {
            if (row.get("actualDecision").asText().equals("DECLINED")) {
                assertThat(row.get("observedOutcome").asText()).isEqualTo("UNKNOWABLE");
                assertThat(row.has("observedMaxDaysPastDue")).isFalse();
                assertThat(row.get("causalConclusion").asText()).isEqualTo("NOT_ESTABLISHED");
            }
        }
        assertThat(report.at("/observedOutcomes/count").asInt()).isEqualTo(5); // approvals only
    }

    @Test
    void t10_censoredOutcomesRemainCensored() {
        var v = replays.replay(new PolicyReplayService.Population("synthetic 2028 book", FROM, TO), LIMIT, 2);
        Instant early = Instant.parse("2028-03-01T00:00:00Z"); // D had not yet defaulted
        JsonNode report = read(outcomes.evaluate(v.replay().getId(), Duration.ofDays(180), early).getReport());
        assertThat(report.at("/observedOutcomes/censored").asInt()).isEqualTo(5);
        assertThat(report.at("/observedOutcomes/observedDefaults").asInt()).isZero();
        for (JsonNode row : report.get("rows")) {
            if (row.get("actualDecision").asText().equals("APPROVED")) {
                assertThat(row.get("censored").asBoolean()).isTrue();
                assertThat(row.get("finding").asText()).contains("censored");
            }
        }
        // The same experiment, observed later, is no longer censored and now sees the defaults.
        JsonNode later = read(outcomes.evaluate(v.replay().getId(), Duration.ofDays(180), null).getReport());
        assertThat(later.at("/observedOutcomes/censored").asInt()).isZero();
        assertThat(later.at("/observedOutcomes/observedDefaults").asInt()).isEqualTo(2);
    }

    @Test
    void t11_aSnapshotAlteredAfterTheDecisionCannotChangeTheDecisionContext() {
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE credit_decision_snapshot SET content = '{}'::jsonb WHERE decision_id = ?", decision("A"))))
                .contains("append-only");
        // Even with the append-only trigger bypassed, an altered snapshot is detected and refused.
        Throwable refused = hermetic(jdbc -> jdbc.update("""
                UPDATE credit_decision_snapshot
                   SET content = jsonb_set(content, '{application,principalMinor}', '100')
                 WHERE decision_id = ?""", decision("A")),
                () -> catchThrowable(() -> evaluator.source(decision("A"))));
        assertThat(refused).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("no longer matches");
        Boolean verified = hermetic(jdbc -> jdbc.update("""
                UPDATE credit_decision_snapshot SET content = jsonb_set(content, '{application,principalMinor}', '100')
                 WHERE decision_id = ?""", decision("A")),
                () -> reconstruct.reconstruct(decision("A"), null).snapshotVerified());
        assertThat(verified).isFalse();
    }

    @Test
    void t12_aCounterfactualIsReproducibleFromItsRecordedInputs() {
        var v = replays.replay(new PolicyReplayService.Population("synthetic 2028 book", FROM, TO), MIN_AVAILABLE, 1);
        for (var row : v.rows()) {
            CounterfactualEvaluator.Reproduction r = evaluator.reproduce(row.counterfactual().getId());
            assertThat(r.identical()).as(row.actual().decisionId().toString()).isTrue();
        }
        // The hashes really protect the record: an altered stored input no longer reproduces.
        UUID id = v.rows().get(0).counterfactual().getId();
        Boolean identical = hermetic(jdbc -> jdbc.update("""
                UPDATE research_counterfactual_decision SET input = jsonb_set(input, '{facts,principalMinor}', '1')
                 WHERE id = ?""", id), () -> evaluator.reproduce(id).identical());
        assertThat(identical).isFalse();
    }

    // ======================================================= outcome evaluation report

    @Test
    void theOutcomeReportSeparatesObservationDecisionAndCausality() {
        var v = replays.replay(new PolicyReplayService.Population("synthetic 2028 book", FROM, TO), LIMIT, 2);
        JsonNode report = read(outcomes.evaluate(v.replay().getId(), Duration.ofDays(180), null).getReport());
        assertThat(report.at("/population/label").asText()).isEqualTo("synthetic 2028 book");
        assertThat(report.at("/population/decisions").asInt()).isEqualTo(7);
        assertThat(report.at("/decisions/actualApprovals").asInt()).isEqualTo(5);
        assertThat(report.at("/decisions/counterfactualApprovals").asInt()).isZero();
        assertThat(report.at("/decisions/changedFromActualDecision").asInt()).isEqualTo(5);
        assertThat(report.at("/observedOutcomes/observedDefaults").asInt()).isEqualTo(2);       // D, E
        assertThat(report.at("/observedOutcomes/observedSettlements").asInt()).isEqualTo(4);    // A, B, C, E
        assertThat(report.at("/observedOutcomes/observedEarlySettlements").asInt()).isEqualTo(1);// B
        assertThat(report.at("/observedOutcomes/delinquency/reached30DaysPastDue").asInt()).isEqualTo(3); // C, D, E
        assertThat(report.at("/observedOutcomes/delinquency/reached90DaysPastDue").asInt()).isEqualTo(2); // D, E
        assertThat(report.at("/interpretation/causalConclusion").asText()).isEqualTo("NOT_ESTABLISHED");
        assertThat(report.toString()).doesNotContain("\"score\"", "recommend\"", "bestPolicy");
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        report.get("rows").forEach(r -> rows.put(r.get("decisionId").asText(), r));
        JsonNode dRow = rows.get(decision("D").toString());
        assertThat(dRow.get("observedOutcome").asText()).isEqualTo("DEFAULTED");
        assertThat(dRow.get("counterfactualDecision").asText()).isEqualTo("DECLINED");
        assertThat(dRow.get("finding").asText()).contains("would have declined").contains("not a proof");
        assertThat(rows.get(decision("A").toString()).get("finding").asText()).contains("forgone");
        // The report is itself reproducible.
        JsonNode again = read(outcomes.evaluate(v.replay().getId(), Duration.ofDays(180), clock.instant()).getReport());
        assertThat(again.get("rows")).isEqualTo(report.get("rows"));
    }

    // ============================================================ leakage mutations

    /** A source that reads today's state instead of the snapshot ("replace the snapshot with current state"). */
    DecisionFactsSource currentState() {
        return src -> {
            DecisionFacts s = snapshotFacts.facts(src);
            UUID customer = UUID.fromString(src.snapshot().at("/subject/customerId").asText());
            UUID party = UUID.fromString(src.snapshot().at("/subject/partyId").asText());
            boolean anyDefaulted = false;
            long maxDpd = 0;
            for (var o : obligations.findByDebtorPartyIdOrderByProposedAtAsc(party)) {
                anyDefaulted |= o.getStatus().name().equals("DEFAULTED");
                var view = loans.view(o.getId());
                maxDpd = Math.max(maxDpd, view.delinquency() == null ? 0 : view.delinquency().daysPastDue());
            }
            UUID settlement = UUID.fromString(src.snapshot().at("/application/settlementAccountId").asText());
            return new DecisionFacts(customers.require(customer).getStatus().name(), s.currency(), s.currencyScale(),
                    s.principalMinor(), s.installmentCount(), s.annualRateBps(), anyDefaulted, maxDpd,
                    funds.balances(settlement).availableMinor(), null, s.verifiedMonthlyIncomeMinor(),
                    s.debtServiceRatioBps(), s.informationCompleteness());
        };
    }

    /** A source that uses the borrower's history as known now, including events after the decision. */
    DecisionFactsSource postDecisionHistory() {
        return src -> borrowerFacts(src, clock.instant(), clock.instant(), true);
    }

    /** A source that reconstructs at decision time but WITHOUT the recorded_at <= knownAt cut-off. */
    DecisionFactsSource hindsight() {
        return src -> borrowerFacts(src, src.decidedAt(), clock.instant(), false);
    }

    private DecisionFacts borrowerFacts(SourceDecision src, Instant asOf, Instant knownAt, boolean includeSubject) {
        DecisionFacts s = snapshotFacts.facts(src);
        UUID party = UUID.fromString(src.snapshot().at("/subject/partyId").asText());
        boolean anyDefaulted = false;
        long maxDpd = 0;
        for (HistoricalLoanState h : pit.borrowerAt(party, asOf, knownAt)) {
            if (!includeSubject && h.obligationId().equals(src.obligationId())) {
                continue;
            }
            anyDefaulted |= "DEFAULTED".equals(h.status());
            maxDpd = Math.max(maxDpd, h.derivedDelinquency() == null ? 0 : h.derivedDelinquency().daysPastDue());
        }
        return new DecisionFacts(s.customerStatus(), s.currency(), s.currencyScale(), s.principalMinor(),
                s.installmentCount(), s.annualRateBps(), anyDefaulted, maxDpd, s.settlementAvailableMinor(), null,
                s.verifiedMonthlyIncomeMinor(), s.debtServiceRatioBps(), s.informationCompleteness());
    }

    /** Scenario: a fact recorded long after G's decision, but back-dated to before it. */
    private void lateRecordedBackdatedSettlementOfD(JdbcTemplate jdbc) {
        int next = jdbc.queryForObject("SELECT max(loan_seq) + 1 FROM lending_event WHERE obligation_id = ?", Integer.class,
                run.get("D").obligationId());
        jdbc.update("""
                INSERT INTO lending_event (id, obligation_id, loan_seq, event_type, event_kind, effective_at, recorded_at,
                    payload, actor, actor_type, origin) VALUES (?, ?, ?, 'LOAN_SETTLED', 'FACT', ?, ?, '{}', 'late', 'SYSTEM', 'LIVE')""",
                UUID.randomUUID(), run.get("D").obligationId(), next,
                java.sql.Timestamp.from(run.get("G").decidedAt().minus(Duration.ofDays(1))),
                java.sql.Timestamp.from(clock.instant()));
    }

    @Test
    void leakageMutationsAreDetectedAndTheRealEngineIsImmune() {
        CreditPolicy v1 = policy(CreditPolicy.LENDING, 1);
        CreditPolicy minAvail = policy(MIN_AVAILABLE, 1);
        String honestD = compute("D", v1, snapshotFacts).getOutputHash();
        String honestA = compute("A", minAvail, snapshotFacts).getOutputHash();
        String honestG = compute("G", v1, snapshotFacts).getOutputHash();

        // Mutation "replace the historical snapshot with current state": D is DEFAULTED today.
        assertThat(compute("D", v1, currentState()).getHypotheticalDecision()).isEqualTo("DECLINED");
        assertThat(compute("D", v1, currentState()).getOutputHash()).isNotEqualTo(honestD);

        // Mutation "modify the current loan status": the leaky source follows it; the real engine does not.
        Map<String, String> status = hermetic(jdbc -> jdbc.update(
                "UPDATE obligation SET status = 'SETTLED', settled_at = now() WHERE id = ?", run.get("D").obligationId()),
                () -> Map.of("honest", compute("D", v1, snapshotFacts).getOutputHash(),
                        "leaky", compute("D", v1, currentState()).getOutputHash()));
        assertThat(status.get("honest")).isEqualTo(honestD);
        assertThat(status.get("leaky")).isNotEqualTo(compute("D", v1, currentState()).getOutputHash());

        // Mutation "modify today's account balance".
        UUID aLedger = f.jdbc.queryForObject("""
                SELECT a.ledger_account_id FROM account a JOIN obligation o ON o.settlement_account_id = a.id WHERE o.id = ?""",
                UUID.class, run.get("A").obligationId());
        Map<String, String> balance = hermetic(jdbc -> jdbc.update(
                "UPDATE ledger_account_balance SET balance_minor = 0 WHERE ledger_account_id = ?", aLedger),
                () -> Map.of("honest", compute("A", minAvail, snapshotFacts).getOutputHash(),
                        "leakyDecision", compute("A", minAvail, currentState()).getHypotheticalDecision()));
        assertThat(balance.get("honest")).isEqualTo(honestA);
        assertThat(balance.get("leakyDecision")).isEqualTo("DECLINED");
        assertThat(compute("A", minAvail, snapshotFacts).getHypotheticalDecision()).isEqualTo("APPROVED");

        // Mutation "modify a post-decision default event": a source reading later history sees it.
        String leakyHistoryBefore = compute("D", v1, postDecisionHistory()).getOutputHash();
        assertThat(leakyHistoryBefore).isNotEqualTo(honestD);
        Map<String, String> defaultEvent = hermetic(jdbc -> jdbc.update("""
                UPDATE lending_event SET event_type = 'LOAN_SETTLED', event_kind = 'FACT', payload = '{}'
                 WHERE obligation_id = ? AND event_type = 'DEFAULT_DECLARED'""", run.get("D").obligationId()),
                () -> Map.of("honest", compute("D", v1, snapshotFacts).getOutputHash(),
                        "leaky", compute("D", v1, postDecisionHistory()).getOutputHash()));
        assertThat(defaultEvent.get("honest")).isEqualTo(honestD);
        assertThat(defaultEvent.get("leaky")).isNotEqualTo(leakyHistoryBefore);

        // Mutation "remove the recorded_at <= knownAt condition": a late-recorded, back-dated fact.
        assertThat(compute("G", v1, hindsight()).getOutputHash()).isEqualTo(honestG); // no difference yet
        Map<String, String> late = hermetic(this::lateRecordedBackdatedSettlementOfD,
                () -> {
                    CounterfactualDecision honest = compute("G", v1, snapshotFacts);
                    return Map.of("honest", honest.getOutputHash(), "context", honest.getContextCheck(),
                            "leakyDecision", compute("G", v1, hindsight()).getHypotheticalDecision());
                });
        assertThat(late.get("honest")).isEqualTo(honestG);
        assertThat(late.get("leakyDecision")).isEqualTo("APPROVED"); // hindsight "forgets" D's default
        // The real engine's independent context check reconstructs with the cut-off, so the late fact
        // is invisible and the snapshot still agrees with history as known at decision time.
        assertThat(late.get("context")).isEqualTo("CONSISTENT");
    }

    @Test
    void theContextCheckAgreesWithHistoryForEveryDecision() {
        for (String s : run.stories().keySet()) {
            assertThat(evaluator.evaluate(decision(s), LIMIT, 1).getContextCheck()).as(s).isEqualTo("CONSISTENT");
        }
    }

    // ================================================================= helpers

    private Map<String, PolicyReplayService.Row> byStory(PolicyReplayService.ReplayView v) {
        Map<UUID, String> names = new LinkedHashMap<>();
        run.stories().forEach((name, st) -> names.put(st.approvalDecisionId(), name));
        Map<String, PolicyReplayService.Row> out = new LinkedHashMap<>();
        for (var row : v.rows()) {
            String name = names.get(row.actual().decisionId());
            if (name != null) {
                out.put(name, row);
            }
        }
        return out;
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String rejection(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).as("expected rejection").isNotNull();
        return LedgerFixtures.causeChain(t);
    }
}
