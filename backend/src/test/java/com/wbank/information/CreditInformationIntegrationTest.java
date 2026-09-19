package com.wbank.information;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.information.FinancialFact.Frequency;
import com.wbank.information.FinancialFact.Kind;
import com.wbank.information.FinancialFact.Provenance;
import com.wbank.obligation.LoanService;
import com.wbank.obligation.domain.AnnuitySchedule;
import com.wbank.obligation.history.CreditDecisionSnapshotRepository;
import com.wbank.obligation.history.CreditPolicyRules;
import com.wbank.obligation.history.CreditPolicyService;
import com.wbank.obligation.history.DecisionSnapshots;
import com.wbank.obligation.history.LendingDatasetService;
import com.wbank.research.CounterfactualEvaluator;
import com.wbank.research.InformationResearchService;
import com.wbank.research.SnapshotDecisionFacts;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.MutableClock;
import com.wbank.support.PostgresIntegrationTest;
import com.wbank.support.SyntheticLendingHistory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Credit information and affordability over the synthetic seven-loan history (base 2027-07-06:
 * no other test decides in its window, and it precedes the live policy v2 of 2031-06-01), with a
 * borrower-information layer added. Decisions are at 10:00; "before" facts are recorded
 * at 09:30, "after" facts at 10:30.
 *
 * <pre>
 *   story  before the decision                            after the decision
 *   A      verified salary 5,000.00; verified disclosure;  card verified
 *          card 200.00 declared; employment declared
 *   B      verified salary 4,000.00 applying next month   -
 *   C      declared salary 3,500.00                       salary verified
 *   D      nothing                                        -
 *   E      nothing                                        salary 3,500.00 (true 30 days before the
 *                                                         decision) and rent 800.00, both recorded late
 *   F      declared salary 900.00 (declined)              -
 *   G      D's borrower, nothing (declined)               -
 * </pre>
 *
 * A and D have identical Phase 6 decision facts and different information.
 */
@Import(MutableClock.Config.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreditInformationIntegrationTest extends PostgresIntegrationTest {

    static final LocalDate BASE = LocalDate.of(2027, 7, 6);
    static final Instant FROM = Instant.parse("2027-07-01T00:00:00Z");
    static final Instant TO = Instant.parse("2028-01-01T00:00:00Z");
    static final String INFO_POLICY = "RESEARCH_INFORMATION";
    static final List<String> STORIES = List.of("A", "B", "C", "D", "E", "F", "G");

    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;
    @Autowired LoanService loans;
    @Autowired MutableClock clock;
    @Autowired CreditDecisionSnapshotRepository snapshotRepo;
    @Autowired DecisionSnapshots snapshotIntegrity;
    @Autowired FinancialInformationService information;
    @Autowired AffordabilityService affordability;
    @Autowired InformationResearchService research;
    @Autowired CreditPolicyService policies;
    @Autowired CounterfactualEvaluator evaluator;
    @Autowired SnapshotDecisionFacts snapshotFacts;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired LendingDatasetService datasets;

    SyntheticLendingHistory.Run run;
    final Map<String, UUID> ids = new LinkedHashMap<>();
    Instant decisionsAt;

    @BeforeAll
    void setUp() {
        run = new SyntheticLendingHistory(d, loans, clock, snapshotRepo).generate(BASE, new SyntheticLendingHistory.Information() {
            @Override
            public void beforeDecisions(Map<String, UUID> p, Instant at) {
                decisionsAt = at;
                ids.put("A.salary", income(p.get("A"), 500_000, Provenance.VERIFIED, "payslip-A", null, null));
                ids.put("A.disclosure", record(p.get("A"), new FinancialInformationService.Observation(
                        Kind.OBLIGATION_DISCLOSURE, "ALL_RECURRING_OBLIGATIONS", null, null, null, null, null, null,
                        null, null, Provenance.VERIFIED, "credit bureau report", "bureau-A", null, null)));
                ids.put("A.card", obligation(p.get("A"), "CREDIT_CARD", 20_000, Provenance.DECLARED, null, null));
                ids.put("A.employment", record(p.get("A"), new FinancialInformationService.Observation(Kind.EMPLOYMENT,
                        "EMPLOYED", null, null, null, null, null, null, BASE.minusMonths(26), null,
                        Provenance.DECLARED, "application form", null, null, null)));
                ids.put("B.salary", income(p.get("B"), 400_000, Provenance.VERIFIED, "contract-B", null,
                        BASE.plusMonths(1)));
                ids.put("C.salary", income(p.get("C"), 350_000, Provenance.DECLARED, null, null, null));
                ids.put("F.salary", income(p.get("F"), 90_000, Provenance.DECLARED, null, null, null));
            }

            @Override
            public void afterDecisions(Map<String, UUID> p, Instant at) {
                ids.put("A.card.verified", verify(p.get("A"), ids.get("A.card"), "statement-A"));
                ids.put("C.salary.verified", verify(p.get("C"), ids.get("C.salary"), "payslip-C"));
                ids.put("E.salary", income(p.get("E"), 350_000, Provenance.VERIFIED, "payslip-E",
                        at.minus(Duration.ofDays(30)), null));
                ids.put("E.rent", obligation(p.get("E"), "RENT", 80_000, Provenance.DECLARED, null,
                        at.minus(Duration.ofDays(60))));
            }
        });
        clock.set(run.end().plus(Duration.ofDays(3)));
        if (policies.versions(INFO_POLICY).isEmpty()) {
            CreditPolicyRules v1 = policies.rules(policies.inForceAt(run.get("A").decidedAt()));
            clock.set(clock.instant().plusSeconds(1));
            policies.publish(INFO_POLICY, new CreditPolicyRules(v1.requireActiveCustomer(), v1.maxPrincipalMajor(),
                    v1.maxInstallments(), v1.maxAnnualRateBps(), v1.blockIfAnyObligationDefaulted(),
                    v1.maxExistingDaysPastDue(), v1.defaultDeclarationMinDaysPastDue(), null, 4_000L, true),
                    null, "debt service <= 40% on verified income; complete information required");
        }
    }

    // ------------------------------------------------------------------ recording helpers

    private UUID record(UUID party, FinancialInformationService.Observation o) {
        return information.record(party, o).getId();
    }

    private UUID income(UUID party, long monthlyMinor, Provenance p, String evidence, Instant effective,
                        LocalDate appliesFrom) {
        return record(party, new FinancialInformationService.Observation(Kind.INCOME, "SALARY", null, null, monthlyMinor,
                "USD", Frequency.MONTHLY, null, null, appliesFrom, p, "application form", evidence, null, effective));
    }

    private UUID obligation(UUID party, String type, long monthlyMinor, Provenance p, String evidence, Instant effective) {
        return record(party, new FinancialInformationService.Observation(Kind.RECURRING_OBLIGATION, type, null, null,
                monthlyMinor, "USD", Frequency.MONTHLY, null, null, null, p, "application form", evidence, null,
                effective));
    }

    /** A verification: same amount, VERIFIED, true since the original observation became true. */
    private UUID verify(UUID party, UUID target, String evidence) {
        FactView t = information.provenance(target).stream().filter(v -> v.id().equals(target)).findFirst().orElseThrow();
        return record(party, new FinancialInformationService.Observation(Kind.valueOf(t.kind()), t.type(), null, null,
                t.amountMinor(), t.currency(), Frequency.valueOf(t.frequency()), null, null, t.appliesFrom(),
                Provenance.VERIFIED, "document check", evidence, target, t.effectiveAt()));
    }

    private UUID party(String story) {
        return run.get(story).partyId();
    }

    private JsonNode snapshot(String story) {
        try {
            return json.readTree(snapshotRepo.findByDecisionId(run.get(story).approvalDecisionId()).orElseThrow()
                    .getContent());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode captured(String story) {
        return snapshot(story).get("financialInformation");
    }

    private static long proposed() {
        return AnnuitySchedule.instalment(300_000, 1_200, 3);
    }

    // ------------------------------------------------------ reconstruction pipeline + invariants

    /** Which facts a reconstruction uses. The correct one is {@link FactSelection}; mutants are leaky. */
    interface Selector {
        List<FactView> select(UUID party, Instant asOf, Instant knownAt);
    }

    final Selector correct = (p, a, k) -> FactSelection.currentPerSeries(information.all(p), a, k);

    /** Recomputes a decision's affordability from the snapshot's own frame with {@code sel} and {@code calc}. */
    AffordabilityService.Computed reconstruct(String story, Selector sel, AffordabilityCalculator calc) {
        try {
            AffordabilityCalculator.Input stored = json.treeToValue(captured(story).get("input"),
                    AffordabilityCalculator.Input.class);
            return affordability.run(calc, new AffordabilityCalculator.Input(stored.calculationVersion(), stored.asOf(),
                    stored.knownAt(), stored.currency(), stored.proposedMonthlyPaymentMinor(),
                    sel.select(party(story), stored.asOf(), stored.knownAt()), stored.internalObligations()), List.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every invariant of the information layer; empty iff the implementation is correct. */
    List<String> violations(Selector sel, AffordabilityCalculator calc) {
        List<String> v = new ArrayList<>();
        for (String s : STORIES) {
            AffordabilityService.Computed c = reconstruct(s, sel, calc);
            if (!c.outputHash().equals(captured(s).get("outputHash").asText())) {
                v.add(s + ": reconstruction at decision time differs from the snapshot");
            }
            JsonNode o = affordability.tree(c.output());
            String income = o.at("/income/state").asText();
            String obligations = o.at("/obligations/state").asText();
            if ("NONE".equals(income) && !o.at("/income/verifiedMonthlyMinor").isNull()) {
                v.add(s + ": absent income was given a value");
            }
            if (!"KNOWN_COMPLETE".equals(obligations) && !o.at("/obligations/externalMonthlyMinor").isNull()) {
                v.add(s + ": obligations not known to be complete were given a value");
            }
            String expectedIncome = switch (s) {
                case "A" -> "VERIFIED";
                case "C", "F" -> "DECLARED_ONLY";
                default -> "NONE";
            };
            if (!expectedIncome.equals(income)) {
                v.add(s + ": income state " + income + ", bank knew " + expectedIncome);
            }
            String expectedObligations = "A".equals(s) ? "KNOWN_COMPLETE" : "UNKNOWN";
            if (!expectedObligations.equals(obligations)) {
                v.add(s + ": obligations state " + obligations + ", bank knew " + expectedObligations);
            }
        }
        if (exactRatio(calc) != 700) {
            v.add("ratio 7/100 is not exactly 700 bps");
        }
        return v;
    }

    private static FactView fv(String kind, String type, Long amount, String frequency, String provenance,
                               LocalDate appliesFrom) {
        Instant t = Instant.parse("2035-01-01T00:00:00Z");
        return new FactView(UUID.randomUUID(), 1, UUID.randomUUID(), kind, type, "ACTIVE", amount,
                amount == null ? null : "USD", frequency, null, null, appliesFrom, provenance, "test",
                "VERIFIED".equals(provenance) ? "e" : null, null, t, t);
    }

    private static AffordabilityCalculator.Input pure(long proposed, FactView... facts) {
        Instant t = Instant.parse("2035-01-02T00:00:00Z");
        return new AffordabilityCalculator.Input(AffordabilityCalculator.VERSION, t, t, "USD", proposed, List.of(facts),
                List.of());
    }

    private long exactRatio(AffordabilityCalculator calc) {
        Map<String, Object> out = calc.compute(pure(7, fv("INCOME", "SALARY", 100L, "MONTHLY", "VERIFIED", null),
                fv("OBLIGATION_DISCLOSURE", "ALL_RECURRING_OBLIGATIONS", null, null, "VERIFIED", null)));
        return affordability.tree(out).at("/bases/VERIFIED_INCOME/debtServiceRatioBps").asLong();
    }

    // ------------------------------------------------------------------------------ tests

    @Test
    void t01_visibilityIsBitemporal() {
        Instant now = clock.instant();
        // E: true before the decision, recorded after it
        assertThat(information.visibleAt(party("E"), decisionsAt, decisionsAt).observations()).isEmpty();
        assertThat(information.visibleAt(party("E"), decisionsAt, now).observations()).hasSize(2);
        assertThat(information.visibleAt(party("E"), decisionsAt, decisionsAt).notYetVisible()).isEqualTo(2);
        // B: recorded before the decision, applies after it: visible, but excluded as future
        assertThat(information.visibleAt(party("B"), decisionsAt, decisionsAt).observations()).hasSize(1);
        JsonNode b = captured("B").get("output");
        assertThat(b.at("/income/state").asText()).isEqualTo("NONE");
        assertThat(b.at("/income/futureIncomeExcluded")).hasSize(1);
        assertThat(b.at("/income/futureIncomeExcluded/0/treatment").asText()).isEqualTo("EXCLUDED_APPLIES_IN_FUTURE");
        // nothing is visible before it became true
        assertThat(information.visibleAt(party("A"), decisionsAt.minus(Duration.ofDays(1)), now).observations()).isEmpty();
    }

    @Test
    void t02_provenanceAndVerification() {
        List<FactView> series = information.provenance(ids.get("C.salary.verified"));
        assertThat(series).extracting(FactView::provenance).containsExactly("DECLARED", "VERIFIED");
        assertThat(series.get(1).verifiesFactId()).isEqualTo(ids.get("C.salary"));
        assertThat(series.get(1).evidenceReference()).isEqualTo("payslip-C");
        assertThat(series.get(0).seriesId()).isEqualTo(series.get(1).seriesId());

        assertThatThrownBy(() -> income(party("D"), 1, Provenance.VERIFIED, " ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> income(party("D"), 1, Provenance.DECLARED, null, clock.instant().plusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> record(party("D"), new FinancialInformationService.Observation(Kind.INCOME, "SALARY",
                null, null, 1L, "USD", Frequency.MONTHLY, null, null, null, Provenance.DECLARED, "x", null,
                ids.get("C.salary"), null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void t03_databaseRejectsInvalidFacts() {
        UUID p = party("D");
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp later = Timestamp.from(clock.instant().plusSeconds(60));
        String sql = "INSERT INTO borrower_financial_fact (id, party_id, series_id, fact_kind, fact_type, status, "
                + "amount_minor, currency, frequency, provenance, source, evidence_reference, verifies_fact_id, "
                + "effective_at, recorded_at, recorded_by, recorder_type) VALUES (?,?,?,?,?,'ACTIVE',?,?,?,?,'t',?,?,?,?,'t','SYSTEM')";
        // effective after recorded
        assertThatThrownBy(() -> f.jdbc.update(sql, UUID.randomUUID(), p, UUID.randomUUID(), "INCOME", "SALARY", 1L,
                "USD", "MONTHLY", "DECLARED", null, null, later, now)).isInstanceOf(DataAccessException.class);
        // VERIFIED without evidence
        assertThatThrownBy(() -> f.jdbc.update(sql, UUID.randomUUID(), p, UUID.randomUUID(), "INCOME", "SALARY", 1L,
                "USD", "MONTHLY", "VERIFIED", null, null, now, now)).isInstanceOf(DataAccessException.class);
        // income without an amount
        assertThatThrownBy(() -> f.jdbc.update(sql, UUID.randomUUID(), p, UUID.randomUUID(), "INCOME", "SALARY", null,
                null, null, "DECLARED", null, null, now, now)).isInstanceOf(DataAccessException.class);
        // a verification in another series
        assertThatThrownBy(() -> f.jdbc.update(sql, UUID.randomUUID(), p, UUID.randomUUID(), "INCOME", "SALARY", 1L,
                "USD", "MONTHLY", "VERIFIED", "e", ids.get("C.salary"), now, now)).isInstanceOf(DataAccessException.class);
        // a series that changes party
        UUID cSeries = information.provenance(ids.get("C.salary")).get(0).seriesId();
        assertThatThrownBy(() -> f.jdbc.update(sql, UUID.randomUUID(), p, cSeries, "INCOME", "SALARY", 1L, "USD",
                "MONTHLY", "DECLARED", null, null, now, now)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void t04_appendOnly() {
        assertThatThrownBy(() -> f.jdbc.update("UPDATE borrower_financial_fact SET amount_minor = 1 WHERE id = ?",
                ids.get("C.salary"))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> f.jdbc.update("DELETE FROM borrower_financial_fact WHERE id = ?", ids.get("C.salary")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> f.jdbc.update("UPDATE affordability_assessment SET completeness = 'COMPLETE'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> f.jdbc.update("DELETE FROM affordability_assessment"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void t05_incomeAndObligationsAtDecisionTime() {
        JsonNode a = captured("A").get("output");
        assertThat(a.at("/income/state").asText()).isEqualTo("VERIFIED");
        assertThat(a.at("/income/verifiedMonthlyMinor").asLong()).isEqualTo(500_000);
        assertThat(a.at("/obligations/state").asText()).isEqualTo("KNOWN_COMPLETE");
        assertThat(a.at("/obligations/externalMonthlyMinor").asLong()).isEqualTo(20_000);
        assertThat(a.at("/obligations/externalComponents/0/treatment").asText()).isEqualTo("DECLARED");
        assertThat(a.at("/employment/0/monthsSinceStartAtAsOf").asLong()).isEqualTo(26);
        assertThat(a.at("/employment/0/monthsSinceStartProvenance").asText()).isEqualTo("DERIVED");
        long total = 20_000 + proposed();
        assertThat(a.at("/bases/VERIFIED_INCOME/debtServiceRatioBps").asLong()).isEqualTo(
                BigDecimal.valueOf(total * 10_000).divide(BigDecimal.valueOf(500_000), 0, RoundingMode.CEILING).longValue());
        assertThat(a.at("/bases/VERIFIED_INCOME/residualMonthlyCapacityMinor").asLong()).isEqualTo(500_000 - total);

        JsonNode dd = captured("D").get("output");
        assertThat(dd.at("/income/state").asText()).isEqualTo("NONE");
        assertThat(dd.at("/income/verifiedMonthlyMinor").isNull()).isTrue();
        assertThat(dd.at("/obligations/state").asText()).isEqualTo("UNKNOWN");
        assertThat(dd.at("/obligations/externalMonthlyMinor").isNull()).isTrue();
        assertThat(dd.at("/bases/DECLARED_INCLUSIVE/status").asText()).isEqualTo("INDETERMINATE");
        assertThat(dd.at("/bases/DECLARED_INCLUSIVE/reasons")).extracting(JsonNode::asText)
                .containsExactly("NO_INCOME", "OBLIGATIONS_NOT_KNOWN_COMPLETE");

        JsonNode c = captured("C").get("output");
        assertThat(c.at("/income/state").asText()).isEqualTo("DECLARED_ONLY");
        assertThat(c.at("/income/declaredMonthlyMinor").asLong()).isEqualTo(350_000);
        assertThat(c.at("/bases/VERIFIED_INCOME/reasons/0").asText()).isEqualTo("NO_VERIFIED_INCOME");

        // E today: rent recorded, but with no disclosure the recorded amount is a lower bound, not a total
        JsonNode eNow = affordability.tree(affordability.compute(party("E"),
                new AffordabilityService.Proposal("USD", 300_000, 1_200, 3), clock.instant(), clock.instant(),
                run.get("E").obligationId()).output());
        assertThat(eNow.at("/obligations/state").asText()).isEqualTo("PARTIALLY_KNOWN");
        assertThat(eNow.at("/obligations/recordedExternalMonthlyMinor").asLong()).isEqualTo(80_000);
        assertThat(eNow.at("/obligations/recordedExternalIsLowerBound").asBoolean()).isTrue();
        assertThat(eNow.at("/obligations/externalMonthlyMinor").isNull()).isTrue();
    }

    @Test
    void t06_exactArithmeticAndExplicitRounding() {
        AffordabilityCalculator calc = new AffordabilityCalculator();
        assertThat(exactRatio(calc)).isEqualTo(700); // 7/100.0*10000 in doubles is 700.0000000000001
        JsonNode o = affordability.tree(calc.compute(pure(1,
                fv("INCOME", "SALARY", 100_000L, "ANNUAL", "VERIFIED", null),
                fv("RECURRING_OBLIGATION", "LOAN", 100_001L, "ANNUAL", "DECLARED", null),
                fv("OBLIGATION_DISCLOSURE", "ALL_RECURRING_OBLIGATIONS", null, null, "DECLARED", null))));
        assertThat(o.at("/income/verifiedMonthlyMinor").asLong()).isEqualTo(8_333);       // floor(100000/12)
        assertThat(o.at("/obligations/externalMonthlyMinor").asLong()).isEqualTo(8_334);  // ceil(100001/12)
        assertThat(o.at("/bases/VERIFIED_INCOME/debtServiceRatioBps").asLong()).isEqualTo(10_003); // ceil(8335e4/8333)
        assertThat(o.at("/bases/VERIFIED_INCOME/residualMonthlyCapacityMinor").asLong()).isEqualTo(-2);
        // same input, same output, same hash
        var in = pure(7, fv("INCOME", "SALARY", 100L, "MONTHLY", "VERIFIED", null));
        assertThat(affordability.run(calc, in, List.of()).outputHash())
                .isEqualTo(affordability.run(calc, in, List.of()).outputHash());
    }

    @Test
    void t07_completenessMakesMissingnessExplicit() {
        assertThat(captured("A").at("/output/completeness/status").asText()).isEqualTo("COMPLETE");
        assertThat(captured("C").at("/output/completeness/status").asText()).isEqualTo("PARTIAL");
        assertThat(captured("C").at("/output/completeness/missing")).extracting(JsonNode::asText)
                .containsExactly("VERIFIED_INCOME", "OBLIGATIONS_KNOWN_COMPLETE");
        assertThat(captured("D").at("/output/completeness/status").asText()).isEqualTo("INSUFFICIENT");
        assertThat(captured("B").at("/output/completeness/status").asText()).isEqualTo("INSUFFICIENT");
        assertThat(captured("F").at("/output/completeness/status").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void t08_assessmentsAreReproducible() {
        AffordabilityAssessment a = affordability.assess(party("A"),
                new AffordabilityService.Proposal("USD", 300_000, 1_200, 3), decisionsAt, decisionsAt, "RESEARCH",
                null, run.get("A").obligationId());
        assertThat(affordability.reproduce(a.getId()).identical()).isTrue();
        // recomputed at the decision's own frame, the recorded assessment equals the snapshot's
        assertThat(a.getOutputHash()).isEqualTo(captured("A").get("outputHash").asText());
        assertThat(a.getInputHash()).isEqualTo(captured("A").get("inputHash").asText());
        assertThatThrownBy(() -> affordability.assess(party("A"), new AffordabilityService.Proposal("USD", 1, 0, 1),
                null, clock.instant().plusSeconds(1), "ENQUIRY", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void t09_snapshotsAreVersion2AndIntact() {
        for (String s : STORIES) {
            var snap = snapshotRepo.findByDecisionId(run.get(s).approvalDecisionId()).orElseThrow();
            assertThat(snapshotIntegrity.verify(snap)).as(s).isTrue();
            assertThat(snapshot(s).get("schema").asText()).isEqualTo(DecisionSnapshots.SCHEMA);
            assertThat(captured(s).get("calculationVersion").asText()).isEqualTo(AffordabilityCalculator.VERSION);
            assertThat(snap.getContentSha256()).isEqualTo(run.get(s).approvalSnapshotSha256());
        }
    }

    @Test
    void t10_counterfactualsSeeInformationOnlyUnderPoliciesThatAskForIt() {
        UUID a = run.get("A").approvalDecisionId();
        UUID dd = run.get("D").approvalDecisionId();
        var lending = policies.require("LENDING_CREDIT_POLICY", 1);
        var info = policies.require(INFO_POLICY, 1);
        // identical Phase 6 facts, identical Phase 6 result
        assertThat(evaluator.prepare(evaluator.source(a), lending, snapshotFacts, null).getPolicyResult())
                .isEqualTo(evaluator.prepare(evaluator.source(dd), lending, snapshotFacts, null).getPolicyResult())
                .isEqualTo("PASS");
        // different information, different result
        assertThat(evaluator.prepare(evaluator.source(a), info, snapshotFacts, null).getPolicyResult()).isEqualTo("PASS");
        assertThat(evaluator.prepare(evaluator.source(dd), info, snapshotFacts, null).getPolicyResult()).isNotEqualTo("PASS");
    }

    @Test
    void t11_historicalReconstructionSixCases() {
        Instant now = clock.instant();
        // 1. income existed before the decision but was recorded after
        assertThat(captured("E").at("/output/income/state").asText()).isEqualTo("NONE");
        assertThat(FactSelection.currentPerSeries(information.all(party("E")), decisionsAt, now))
                .extracting(FactView::kind).contains("INCOME");
        // 2. income recorded before the decision but applying later
        assertThat(captured("B").at("/output/income/futureIncomeExcluded/0/factId").asText())
                .isEqualTo(ids.get("B.salary").toString());
        // 3. an obligation unknown at the time
        assertThat(captured("E").at("/output/obligations/state").asText()).isEqualTo("UNKNOWN");
        // 4. an obligation later verified: the decision still saw it as DECLARED
        assertThat(captured("A").at("/output/obligations/externalComponents/0/treatment").asText()).isEqualTo("DECLARED");
        assertThat(information.visibleAt(party("A"), decisionsAt, now).currentPerSeries().stream()
                .filter(v -> v.kind().equals("RECURRING_OBLIGATION")).findFirst().orElseThrow().provenance())
                .isEqualTo("VERIFIED");
        // 5. declared income later verified
        assertThat(captured("C").at("/output/income/state").asText()).isEqualTo("DECLARED_ONLY");
        // 6. current state differs from historical state, and the reconstruction still equals the snapshot
        String cNow = affordability.tree(affordability.compute(party("C"),
                new AffordabilityService.Proposal("USD", 300_000, 1_200, 3), now, now, run.get("C").obligationId())
                .output()).at("/income/state").asText();
        assertThat(cNow).isEqualTo("VERIFIED");
        assertThat(violations(correct, new AffordabilityCalculator())).isEmpty();
    }

    @Test
    void t12_researchReportAndLeakageExperiment() {
        Map<String, Object> r = research.report("phase7", FROM, TO, null);
        JsonNode j = json.valueToTree(r);
        assertThat(j.at("/population/decisions").asInt()).isEqualTo(7);
        assertThat(j.at("/snapshotSchemas/" + DecisionSnapshots.SCHEMA.replace("/", "~1")).asInt()).isEqualTo(7);
        assertThat(j.at("/informationNotCaptured").asInt()).isZero();
        assertThat(j.at("/incomeState/VERIFIED").asInt()).isEqualTo(1);
        assertThat(j.at("/incomeState/DECLARED_ONLY").asInt()).isEqualTo(2);
        assertThat(j.at("/incomeState/NONE").asInt()).isEqualTo(4);
        assertThat(j.at("/recurringObligationsState/KNOWN_COMPLETE").asInt()).isEqualTo(1);
        assertThat(j.at("/recurringObligationsState/UNKNOWN").asInt()).isEqualTo(6);
        assertThat(j.at("/completeness/COMPLETE").asInt()).isEqualTo(1);
        assertThat(j.at("/completeness/PARTIAL").asInt()).isEqualTo(2);
        assertThat(j.at("/completeness/INSUFFICIENT").asInt()).isEqualTo(4);
        assertThat(j.at("/affordabilityVerifiedBasis/DETERMINATE").asInt()).isEqualTo(1);
        assertThat(j.at("/leakageExperiment/validity").asText()).startsWith("INVALID_FOR_RESEARCH");
        assertThat(j.at("/leakageExperiment/decisionsWhoseStateWouldChange").asInt()).isEqualTo(2); // C and E
        // deterministic
        assertThat(research.report("phase7", FROM, TO, clock.instant()).get("sha256")).isEqualTo(r.get("sha256"));
    }

    @Test
    void t13_httpEndpoints() throws Exception {
        UUID p = d.activeCustomer().getPartyId();
        JsonNode fact = json.readTree(mvc.perform(post("/api/v1/parties/" + p + "/financial-facts")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"kind":"INCOME","type":"salary","amount":"2500.00","currency":"USD",
                                 "frequency":"MONTHLY","provenance":"DECLARED","source":"application form"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(fact.get("amountMinor").asLong()).isEqualTo(250_000);
        assertThat(fact.get("type").asText()).isEqualTo("SALARY");
        JsonNode visible = json.readTree(mvc.perform(get("/api/v1/parties/" + p + "/financial-facts")
                .param("asOf", clock.instant().toString())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertThat(visible.get("observations")).hasSize(1);
        mvc.perform(get("/api/v1/financial-facts/" + fact.get("id").asText() + "/provenance")).andExpect(status().isOk());
        JsonNode assessment = json.readTree(mvc.perform(post("/api/v1/parties/" + p + "/affordability-assessments")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"currency":"USD","principal":"1000.00","annualRateBps":1200,"installmentCount":3}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(assessment.get("completeness").asText()).isEqualTo("PARTIAL");
        JsonNode rep = json.readTree(mvc.perform(get("/api/v1/affordability-assessments/" + assessment.get("id").asText()
                + "/reproduction")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(rep.get("identical").asBoolean()).isTrue();
        JsonNode decisionInfo = json.readTree(mvc.perform(get("/api/v1/history/decisions/"
                + run.get("A").approvalDecisionId() + "/financial-information")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(decisionInfo.get("snapshotVerified").asBoolean()).isTrue();
        assertThat(decisionInfo.at("/financialInformation/outputHash").asText())
                .isEqualTo(captured("A").get("outputHash").asText());
        mvc.perform(get("/api/v1/research/information-report").param("label", "http")
                .param("decidedFrom", FROM.toString()).param("decidedTo", TO.toString())).andExpect(status().isOk());
    }

    @Test
    void t14_datasetRowsCarryTheInformationState() {
        Map<String, String> byDecision = new LinkedHashMap<>();
        for (var row : datasets.creditDecisions(Duration.ofDays(180), clock.instant())) {
            if (!row.decidedAt().isBefore(FROM) && row.decidedAt().isBefore(TO)) {
                assertThat(row.informationState().get("captured")).isEqualTo(true);
                byDecision.put(row.decisionId(), (String) row.informationState().get("completeness"));
            }
        }
        assertThat(byDecision).hasSize(7);
        assertThat(byDecision.get(run.get("A").approvalDecisionId().toString())).isEqualTo("COMPLETE");
        assertThat(byDecision.get(run.get("D").approvalDecisionId().toString())).isEqualTo("INSUFFICIENT");
    }

    // --------------------------------------------------------------------------- mutations

    /** Current state for everything: the bank's information as of today. */
    @Test
    void m01_currentIncomeIsCaught() {
        Instant now = clock.instant();
        assertThat(violations((p, a, k) -> FactSelection.currentPerSeries(information.all(p), now, now),
                new AffordabilityCalculator())).isNotEmpty();
    }

    /** Facts recorded after the decision allowed in (knowledge cut-off ignored). */
    @Test
    void m02_recordedAfterDecisionIsCaught() {
        Instant now = clock.instant();
        assertThat(violations((p, a, k) -> FactSelection.currentPerSeries(information.all(p), a, now),
                new AffordabilityCalculator())).anyMatch(v -> v.startsWith("E:"));
    }

    @Test
    void m03_declaredTreatedAsVerifiedIsCaught() {
        assertThat(violations(correct, new AffordabilityCalculator() {
            @Override
            protected boolean isVerified(FactView f) {
                return true;
            }
        })).anyMatch(v -> v.startsWith("C: income state VERIFIED"));
    }

    /** Obligations taken from today's knowledge while income is taken correctly. */
    @Test
    void m04_obligationNotKnownAtTheTimeIsCaught() {
        Instant now = clock.instant();
        Selector leaky = (p, a, k) -> {
            List<FactView> out = new ArrayList<>(correct.select(p, a, k).stream()
                    .filter(v -> !v.kind().equals("RECURRING_OBLIGATION")).toList());
            out.addAll(FactSelection.currentPerSeries(information.all(p), a, now).stream()
                    .filter(v -> v.kind().equals("RECURRING_OBLIGATION")).toList());
            return out;
        };
        assertThat(violations(leaky, new AffordabilityCalculator()))
                .anyMatch(v -> v.startsWith("E: obligations state PARTIALLY_KNOWN"));
    }

    @Test
    void m05_futureIncomeIsCaught() {
        assertThat(violations(correct, new AffordabilityCalculator() {
            @Override
            protected boolean appliesAt(FactView f, LocalDate asOfDate) {
                return true;
            }
        })).anyMatch(v -> v.startsWith("B: income state VERIFIED"));
    }

    @Test
    void m06_missingIncomeAsZeroIsCaught() {
        assertThat(violations(correct, new AffordabilityCalculator() {
            @Override
            protected Long incomeWhenAbsent() {
                return 0L;
            }
        })).anyMatch(v -> v.equals("D: absent income was given a value"));
    }

    @Test
    void m07_zeroForUnknownObligationsIsCaught() {
        assertThat(violations(correct, new AffordabilityCalculator() {
            @Override
            protected Long externalWhenNotComplete(long recordedExternal) {
                return recordedExternal;
            }
        })).anyMatch(v -> v.equals("D: obligations not known to be complete were given a value"));
    }

    @Test
    void m08_floatingPointRatioIsCaught() {
        assertThat(violations(correct, new AffordabilityCalculator() {
            @Override
            protected long ratioBps(long total, long income) {
                return (long) Math.ceil((double) total / income * 10_000);
            }
        })).contains("ratio 7/100 is not exactly 700 bps");
    }

    /** An old snapshot rewritten in place (triggers disabled, rolled back) no longer verifies. */
    @Test
    void m09_snapshotModifiedInPlaceIsCaught() {
        UUID decision = run.get("C").approvalDecisionId();
        Boolean verified = f.tx.execute(s -> {
            f.jdbc.execute("SET LOCAL session_replication_role = replica");
            f.jdbc.update("UPDATE credit_decision_snapshot SET content = jsonb_set(content, "
                    + "'{financialInformation,output,income,state}', '\"VERIFIED\"') WHERE decision_id = ?", decision);
            boolean ok = snapshotIntegrity.verify(snapshotRepo.findByDecisionId(decision).orElseThrow());
            s.setRollbackOnly();
            return ok;
        });
        assertThat(verified).isFalse();
        assertThat(snapshotIntegrity.verify(snapshotRepo.findByDecisionId(decision).orElseThrow())).isTrue();
    }

    /** Series seen at the decision, but each replaced by its latest (later-verified) version. */
    @Test
    void m10_laterVerificationAlteringDecisionContextIsCaught() {
        Instant now = clock.instant();
        Selector leaky = (p, a, k) -> {
            Set<UUID> seen = correct.select(p, a, k).stream().map(FactView::seriesId).collect(Collectors.toSet());
            return FactSelection.currentPerSeries(information.all(p), a, now).stream()
                    .filter(v -> seen.contains(v.seriesId())).toList();
        };
        assertThat(violations(leaky, new AffordabilityCalculator()))
                .contains("C: reconstruction at decision time differs from the snapshot");
    }
}
