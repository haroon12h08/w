package com.wbank.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.information.AffordabilityService;
import com.wbank.information.FinancialFact.Frequency;
import com.wbank.information.FinancialFact.Kind;
import com.wbank.information.FinancialFact.Provenance;
import com.wbank.information.FinancialInformationService;
import com.wbank.obligation.LoanService;
import com.wbank.obligation.domain.Obligation;
import com.wbank.obligation.history.CorrectionService;
import com.wbank.obligation.history.CreditDecisionSnapshotRepository;
import com.wbank.obligation.history.DecisionSnapshots;
import com.wbank.obligation.history.LendingEventType;
import com.wbank.obligation.history.LoanHistoryFold;
import com.wbank.obligation.history.PointInTimeService;
import com.wbank.research.OutcomeEvaluator.Event;
import com.wbank.research.OutcomeResearchService.KnowledgeBasis;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.MutableClock;
import com.wbank.support.PostgresIntegrationTest;
import com.wbank.support.SyntheticLendingHistory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Outcome association over the synthetic seven-loan history (base 2024-03-05; decisions at T =
 * 10:00; no other test decides in this window), extended without changing its stories:
 *
 * <pre>
 *   story  decision   information at T              observed afterwards
 *   A      APPROVED   COMPLETE (verified)           repaid on schedule, settled ~T+92d
 *   B      APPROVED   INSUFFICIENT                  settled early ~T+36d
 *   C      APPROVED   PARTIAL (declared; verified   35 DPD, cured, settled ~T+92d
 *                     only after T)
 *   D      APPROVED   INSUFFICIENT                  default declared ~T+121d; its rationale is
 *                                                   CORRECTED later (appended, not rewritten)
 *   E      APPROVED   INSUFFICIENT                  default ~T+121d, then repaid in full ~T+153d
 *   F, G   DECLINED   -                             nothing: no loan, no outcome
 *   H      APPROVED   INSUFFICIENT                  (decided 2024-09-06) no repayment; servicing ran
 *                                                   late, so the bank's 1+ DPD observation (effective
 *                                                   ~H+40d) was only RECORDED ~H+50d; 90/180-day
 *                                                   windows still open: CENSORED
 * </pre>
 */
@Import(MutableClock.Config.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutcomeResearchIntegrationTest extends PostgresIntegrationTest {

    static final LocalDate BASE = LocalDate.of(2024, 3, 5);
    static final Instant FROM = Instant.parse("2024-03-01T00:00:00Z");
    static final Instant TO = Instant.parse("2025-03-01T00:00:00Z");
    static final Instant FAR_FUTURE = Instant.parse("9999-01-01T00:00:00Z");

    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;
    @Autowired LoanService loans;
    @Autowired MutableClock clock;
    @Autowired CreditDecisionSnapshotRepository snapshotRepo;
    @Autowired DecisionSnapshots snapshotIntegrity;
    @Autowired FinancialInformationService information;
    @Autowired AffordabilityService affordability;
    @Autowired OutcomeResearchService research;
    @Autowired PolicyReplayService replays;
    @Autowired CorrectionService corrections;
    @Autowired PointInTimeService pit;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    SyntheticLendingHistory.Run run;
    final Map<String, UUID> decisions = new LinkedHashMap<>();
    Instant hDecided;
    Instant kH1;          // after H's 45-day window closed, before the late observation was recorded
    Instant kThen;        // after D's default, before its correction was recorded
    Instant kNow;         // research-current for this class
    OutcomeResearchService.CohortDefinition cohort;
    OutcomeEvaluator.Definition def90;
    OutcomeEvaluator.Definition def180;
    OutcomeEvaluator.Definition set180;
    OutcomeEvaluator.Definition dpd30;
    OutcomeEvaluator.Definition obs1;
    List<OutcomeEvaluator.Definition> defs;
    UUID replayId;

    @BeforeAll
    void setUp() {
        run = new SyntheticLendingHistory(d, loans, clock, snapshotRepo).generate(BASE, new SyntheticLendingHistory.Information() {
            @Override
            public void beforeDecisions(Map<String, UUID> p, Instant at) {
                income(p.get("A"), Provenance.VERIFIED, "payslip-A", null, null);
                information.record(p.get("A"), new FinancialInformationService.Observation(Kind.OBLIGATION_DISCLOSURE,
                        "ALL_RECURRING_OBLIGATIONS", null, null, null, null, null, null, null, null,
                        Provenance.VERIFIED, "bureau", "bureau-A", null, null));
                income(p.get("C"), Provenance.DECLARED, null, null, null);
            }

            @Override
            public void afterDecisions(Map<String, UUID> p, Instant at) {
                var declared = information.all(p.get("C")).get(0);  // verified later: true since it was declared
                income(p.get("C"), Provenance.VERIFIED, "payslip-C", declared.id(), declared.effectiveAt());
            }
        });
        run.stories().forEach((k, s) -> decisions.put(k, s.approvalDecisionId()));

        // D's default rationale corrected after the fact (appended; the original stays).
        kThen = run.end().plus(Duration.ofDays(1));
        clock.set(run.end().plus(Duration.ofDays(2)));
        UUID defaultEvent = pit.history(run.get("D").obligationId()).stream()
                .filter(e -> e.type() == LendingEventType.DEFAULT_DECLARED).findFirst().orElseThrow().id();
        corrections.correct(defaultEvent, "rationale", "90 DPD; borrower unreachable at two addresses", "clerical");

        // H: approved after the seven stories; servicing runs late.
        LocalDate hDay = BASE.plusMonths(6).plusDays(1);
        clock.set(hDay.atTime(9, 0).toInstant(ZoneOffset.UTC));
        var customer = d.activeCustomer();
        var account = d.activeAccount(customer.getId(), "USD");
        d.fund(account.getId(), 1_000_000, "USD");
        Obligation h = loans.propose(customer.getId(), account.getId(), d.money(300_000, "USD"), 1_200, 3);
        clock.set(hDay.atTime(10, 0).toInstant(ZoneOffset.UTC));
        loans.approve(h.getId(), "within policy; story H");
        hDecided = clock.instant();
        clock.set(hDay.atTime(11, 0).toInstant(ZoneOffset.UTC));
        loans.disburse(h.getId());
        LocalDate firstDue = loans.view(h.getId()).schedule().get(0).dueDate();
        clock.set(firstDue.plusDays(20).atTime(9, 0).toInstant(ZoneOffset.UTC));
        loans.service(h.getId(), firstDue.plusDays(10)); // observation effective firstDue+10, recorded firstDue+20
        decisions.put("H", loans.view(h.getId()).decisions().stream()
                .filter(x -> x.getDecision().name().equals("APPROVED")).findFirst().orElseThrow().getId());
        kH1 = hDecided.plus(Duration.ofDays(46));

        clock.set(firstDue.plusDays(30).atTime(12, 0).toInstant(ZoneOffset.UTC));
        kNow = clock.instant();
        String sfx = "_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        def90 = define("DEFAULT_90D" + sfx, Event.DEFAULT, null, 90);
        def180 = define("DEFAULT_180D" + sfx, Event.DEFAULT, null, 180);
        set180 = define("SETTLED_180D" + sfx, Event.SETTLEMENT, null, 180);
        dpd30 = define("DPD30_DERIVED_90D" + sfx, Event.DELINQUENCY_DERIVED, 30, 90);
        obs1 = define("DPD1_BANK_OBSERVED_45D" + sfx, Event.DELINQUENCY_BANK_OBSERVED, 1, 45);
        defs = List.of(def90, def180, set180, dpd30, obs1);
        cohort = research.defineCohort("PHASE8" + sfx, FROM, TO, kNow, null, false, "synthetic eight-decision cohort");
        replayId = replays.replay(new PolicyReplayService.Population("phase8" + sfx, FROM, TO),
                "LENDING_CREDIT_POLICY", 1).replay().getId();
    }

    private void income(UUID party, Provenance p, String evidence, UUID verifies, Instant effective) {
        information.record(party, new FinancialInformationService.Observation(Kind.INCOME, "SALARY", null, null, 500_000L,
                "USD", Frequency.MONTHLY, null, null, null, p, "application form", evidence, verifies, effective));
    }

    private OutcomeEvaluator.Definition define(String code, Event e, Integer threshold, int days) {
        research.defineOutcome(code, e, threshold, days, null);
        return research.outcomeDefinition(code, 1);
    }

    // ------------------------------------------------------------------ pipeline + invariants

    OutcomeResearchService.Membership membership(OutcomeResearchService.InformationSource info) {
        return research.membership(cohort, info);
    }

    Map<String, Object> build(OutcomeEvaluator eval, OutcomeResearchService.InformationSource info, KnowledgeBasis b,
                              Instant k) {
        return research.build(membership(info), defs, b, k, eval);
    }

    OutcomeEvaluator.Result result(Map<String, Object> report, String story, OutcomeEvaluator.Definition def) {
        for (Object o : (List<?>) report.get("rows")) {
            Map<?, ?> row = (Map<?, ?>) o;
            if (row.get("decisionId").equals(decisions.get(story).toString())) {
                return (OutcomeEvaluator.Result) ((Map<?, ?>) row.get("outcomes")).get(def.key());
            }
        }
        throw new IllegalStateException(story);
    }

    static String v(OutcomeEvaluator.Result r) {
        return r.status() + (r.value() == null ? "" : ":" + r.value());
    }

    /** What the synthetic history says, as known at kNow (story -> [DEF90, DEF180, SET180, DPD30, OBS1]). */
    static final Map<String, List<String>> EXPECTED_NOW = Map.of(
            "A", List.of("OBSERVED:NOT_OCCURRED", "OBSERVED:NOT_OCCURRED", "OBSERVED:OCCURRED", "OBSERVED:NOT_OCCURRED", "OBSERVED:NOT_OCCURRED"),
            "B", List.of("OBSERVED:NOT_OCCURRED", "OBSERVED:NOT_OCCURRED", "OBSERVED:OCCURRED", "OBSERVED:NOT_OCCURRED", "OBSERVED:OCCURRED"),
            "C", List.of("OBSERVED:NOT_OCCURRED", "OBSERVED:NOT_OCCURRED", "OBSERVED:OCCURRED", "OBSERVED:OCCURRED", "OBSERVED:OCCURRED"),
            "D", List.of("OBSERVED:NOT_OCCURRED", "OBSERVED:OCCURRED", "OBSERVED:NOT_OCCURRED", "OBSERVED:OCCURRED", "OBSERVED:OCCURRED"),
            "E", List.of("OBSERVED:NOT_OCCURRED", "OBSERVED:OCCURRED", "OBSERVED:OCCURRED", "OBSERVED:OCCURRED", "OBSERVED:OCCURRED"),
            "F", List.of("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN"),
            "G", List.of("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN"),
            "H", List.of("CENSORED", "CENSORED", "CENSORED", "CENSORED", "OBSERVED:OCCURRED"));

    /** Every invariant of the outcome layer; empty iff the implementation is correct. */
    List<String> violations(OutcomeEvaluator eval, OutcomeResearchService.InformationSource info) {
        List<String> v = new ArrayList<>();
        Map<String, Object> now = build(eval, info, KnowledgeBasis.RESEARCH_CURRENT, kNow);
        for (var e : EXPECTED_NOW.entrySet()) {
            for (int i = 0; i < defs.size(); i++) {
                String actual = v(result(now, e.getKey(), defs.get(i)));
                if (!actual.equals(e.getValue().get(i))) {
                    v.add(e.getKey() + " " + defs.get(i).code().replaceAll("_[A-Z0-9]{6}$", "") + ": " + actual
                            + " expected " + e.getValue().get(i));
                }
            }
        }
        // later-recorded observation: not known at kH1
        String h1 = v(result(build(eval, info, KnowledgeBasis.AS_KNOWN_AT, kH1), "H", obs1));
        if (!h1.equals("OBSERVED:NOT_OCCURRED")) {
            v.add("H bank-observed at kH1: " + h1 + " expected OBSERVED:NOT_OCCURRED");
        }
        // as known at decision: every approved outcome is censored, every declined unknown
        Map<String, Object> atDecision = build(eval, info, KnowledgeBasis.AS_KNOWN_AT_DECISION, null);
        for (String s : decisions.keySet()) {
            String expect = s.equals("F") || s.equals("G") ? "UNKNOWN" : "CENSORED";
            if (!v(result(atDecision, s, def90)).equals(expect)) {
                v.add(s + " as known at decision: " + v(result(atDecision, s, def90)));
            }
        }
        // information state exactly as captured in the snapshot
        for (var m : membership(info).members()) {
            JsonNode snap = read(snapshotRepo.findByDecisionId(m.subject().decisionId()).orElseThrow().getContent());
            if (!m.informationState().equals(OutcomeResearchService.snapshotState(snap))) {
                v.add(m.subject().decisionId() + ": information state differs from the decision snapshot");
            }
        }
        // corrections: as known then, none; as known now, D's
        if (!result(build(eval, info, KnowledgeBasis.AS_KNOWN_AT, kThen), "D", def180).corrections().isEmpty()) {
            v.add("D: a correction recorded later appears in the as-known-then outcome");
        }
        if (result(now, "D", def180).corrections().size() != 1) {
            v.add("D: the correction is missing from the as-known-now outcome");
        }
        // counterfactual boundary: never an outcome for a hypothetical decision
        for (var row : replays.get(replayId).rows()) {
            OutcomeEvaluator.Result r = eval.evaluate(obs1, research.subject(row.counterfactual().getSourceDecisionId()),
                    kNow);
            String b = eval.boundary(row.actual().decision(), row.counterfactual().getHypotheticalDecision(), r);
            if (!"UNOBSERVED".equals(b)) {
                v.add(row.actual().decision() + "->" + row.counterfactual().getHypotheticalDecision()
                        + ": counterfactual outcome " + b);
            }
        }
        return v;
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T hermetic(Consumer<JdbcTemplate> perturb, Supplier<T> measure) {
        return f.tx.execute(s -> {
            f.jdbc.execute("SET LOCAL session_replication_role = replica");
            perturb.accept(f.jdbc);
            T result = measure.get();
            s.setRollbackOnly();
            return result;
        });
    }

    private String hash(Map<String, Object> report) {
        return com.wbank.obligation.history.CanonicalJson.sha256(
                com.wbank.obligation.history.CanonicalJson.canonical(json, json.valueToTree(report)));
    }

    // ------------------------------------------------------------------------------ tests

    @Test
    void t01_theCorrectImplementationSatisfiesEveryInvariant() {
        assertThat(violations(new OutcomeEvaluator(), OutcomeResearchService.FROM_SNAPSHOT)).isEmpty();
    }

    @Test
    void t02_cohortMembershipIsReproducibleAndPointInTime() {
        var a = research.membership(cohort.code(), 1);
        var b = research.membership(cohort.code(), 1);
        assertThat(a.membershipHash()).isEqualTo(b.membershipHash());
        assertThat(a.members()).hasSize(8);
        // a cohort defined with knowledge before H's decision never contains H, whatever happens later
        var early = research.defineCohort(cohort.code() + "_EARLY", FROM, TO, hDecided.minusSeconds(1), null, false, null);
        assertThat(research.membership(early.code(), 1).members()).hasSize(7);
        assertThatThrownBy(() -> research.defineCohort(cohort.code() + "_FUTURE", FROM, TO, clock.instant().plusSeconds(1),
                null, false, null)).isInstanceOf(IllegalArgumentException.class);
        var approvedOnly = research.defineCohort(cohort.code() + "_APPROVED", FROM, TO, kNow, List.of("APPROVED"), false, null);
        var m = research.membership(approvedOnly.code(), 1);
        assertThat(m.members()).hasSize(6);
        assertThat(m.excluded()).hasSize(2).allSatisfy(x -> assertThat(x.get("reason")).isEqualTo("DECISION_KIND_NOT_INCLUDED"));
    }

    @Test
    void t03_reportStatisticsAndDenominators() {
        Map<String, Object> r = research.build(research.membership(cohort.code(), 1), defs, KnowledgeBasis.AS_KNOWN_AT,
                kNow, new OutcomeEvaluator());
        JsonNode j = json.valueToTree(r);
        JsonNode t180 = j.at("/totals/" + def180.key().replace("/", "~1"));
        assertThat(t180.get("decisions").asInt()).isEqualTo(8);
        assertThat(t180.get("approved").asInt()).isEqualTo(6);
        assertThat(t180.get("declined").asInt()).isEqualTo(2);
        assertThat(t180.get("observed").asInt()).isEqualTo(5);
        assertThat(t180.get("censored").asInt()).isEqualTo(1);
        assertThat(t180.get("unknown").asInt()).isEqualTo(2);
        assertThat(t180.get("occurred").asInt()).isEqualTo(2);
        assertThat(t180.at("/observedProportion/fraction").asText()).isEqualTo("2/5");
        assertThat(t180.at("/observedProportion/value").asText()).isEqualTo("0.4000");
        // by information state, alphabetical, one category per state, no ranking fields
        JsonNode completeness = j.at("/byInformationState/completeness");
        List<String> cats = new ArrayList<>();
        completeness.fieldNames().forEachRemaining(cats::add);
        assertThat(cats).containsExactly("COMPLETE", "INSUFFICIENT", "PARTIAL");
        assertThat(completeness.at("/INSUFFICIENT/cohortSize").asInt()).isEqualTo(6);
        assertThat(completeness.at("/INSUFFICIENT/declined").asInt()).isEqualTo(2);
        JsonNode ins180 = completeness.at("/INSUFFICIENT/outcomes/" + def180.key());
        assertThat(ins180.get("observed").asInt()).isEqualTo(3);  // B, D, E (H censored, F/G unknown)
        assertThat(ins180.get("occurred").asInt()).isEqualTo(2);
        JsonNode complete90 = completeness.at("/COMPLETE/outcomes/" + def90.key());
        assertThat(complete90.at("/observedProportion/fraction").asText()).isEqualTo("0/1");
        assertThat(j.at("/interpretation/notProvided").toString()).contains("ranking of information states",
                "risk score", "policy recommendation");
        assertThat(j.at("/interpretation/causalConclusion").asText()).isEqualTo("NOT_ESTABLISHED");
        assertThat(j.at("/populationBoundary/declined/outcome").asText()).startsWith("UNKNOWN");
        assertThat(j.at("/warnings").toString()).contains("SELECTION", "CENSORING", "ASSOCIATION IS NOT CAUSATION",
                "COUNTERFACTUALS");
        // a group whose outcomes are all censored or unknown has no proportion
        JsonNode none = j.at("/totals/" + obs1.key());
        assertThat(none.get("observed").asInt()).isEqualTo(6);
    }

    @Test
    void t04_reportsAreRecordedAndReproducible() {
        Map<String, Object> stored = research.report(cohort.code(), 1, defs.stream()
                .map(x -> Map.entry(x.code(), x.version())).toList(), KnowledgeBasis.AS_KNOWN_AT, kH1);
        UUID id = UUID.fromString((String) stored.get("id"));
        assertThat(research.reproduce(id).identical()).isTrue();
        Map<String, Object> again = research.report(cohort.code(), 1, defs.stream()
                .map(x -> Map.entry(x.code(), x.version())).toList(), KnowledgeBasis.AS_KNOWN_AT, kH1);
        assertThat(again.get("outputHash")).isEqualTo(stored.get("outputHash"));
        assertThatThrownBy(() -> f.jdbc.update("UPDATE research_outcome_report SET output_hash = ? WHERE id = ?",
                "0".repeat(64), id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> research.report(cohort.code(), 1, List.of(Map.entry(def90.code(), 1)),
                KnowledgeBasis.AS_KNOWN_AT, clock.instant().plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    /** Regression: a real clock has nanoseconds, PostgreSQL keeps microseconds; reports must still reproduce. */
    @Test
    void t04b_researchCurrentReproducesWithASubMicrosecondClock() {
        Instant saved = clock.instant();
        try {
            clock.set(saved.plusNanos(123_456_789));
            Map<String, Object> stored = research.report(cohort.code(), 1, List.of(Map.entry(def90.code(), 1)),
                    KnowledgeBasis.RESEARCH_CURRENT, null);
            assertThat(research.reproduce(UUID.fromString((String) stored.get("id"))).identical()).isTrue();
            assertThat(research.counterfactualBoundary(replayId, def90.code(), 1, null)).hasSize(8);
        } finally {
            clock.set(saved);
        }
    }

    @Test
    void t05_changesInsideTheWindowChangeTheResultAndOutsideDoNot() {
        OutcomeEvaluator eval = new OutcomeEvaluator();
        List<OutcomeEvaluator.Definition> only90 = List.of(def90);
        String base = hash(research.build(research.membership(cohort.code(), 1), only90, KnowledgeBasis.AS_KNOWN_AT, kNow, eval));
        Instant decided = run.get("D").decidedAt();
        UUID dDefault = pit.history(run.get("D").obligationId()).stream()
                .filter(e -> e.type() == LendingEventType.DEFAULT_DECLARED).findFirst().orElseThrow().id();
        UUID eSettled = pit.history(run.get("E").obligationId()).stream()
                .filter(e -> e.type() == LendingEventType.LOAN_SETTLED).findFirst().orElseThrow().id();
        String inside = hermetic(j -> j.update("UPDATE lending_event SET effective_at = ? WHERE id = ?",
                        Timestamp.from(decided.plus(Duration.ofDays(80))), dDefault),
                () -> hash(research.build(research.membership(cohort.code(), 1), only90, KnowledgeBasis.AS_KNOWN_AT, kNow, eval)));
        String outside = hermetic(j -> j.update("UPDATE lending_event SET effective_at = ? WHERE id = ?",
                        Timestamp.from(decided.plus(Duration.ofDays(100))), eSettled),
                () -> hash(research.build(research.membership(cohort.code(), 1), only90, KnowledgeBasis.AS_KNOWN_AT, kNow, eval)));
        assertThat(inside).isNotEqualTo(base);
        assertThat(outside).isEqualTo(base);
    }

    @Test
    void t06_knowledgeCutoffsAreSeparateAndNeverTouchSnapshots() {
        Map<String, Object> c = research.knowledgeComparison(cohort.code(), 1, obs1.code(), 1, kH1);
        JsonNode j = json.valueToTree(c);
        assertThat(j.at("/bases/AS_KNOWN_AT_DECISION/totals/observed").asInt()).isZero();
        assertThat(j.at("/bases/AS_KNOWN_AT_DECISION/totals/censored").asInt()).isEqualTo(6);
        assertThat(j.at("/bases/AS_KNOWN_AT/totals/occurred").asInt()).isEqualTo(4);   // B, C, D, E
        assertThat(j.at("/bases/RESEARCH_CURRENT/totals/occurred").asInt()).isEqualTo(5); // + H, recorded late
        assertThat(j.at("/rowsDifferingLaterVsCurrent").asInt()).isEqualTo(1);
        assertThat(j.at("/decisionSnapshotsUnchanged").asBoolean()).isTrue();
        for (var s : run.stories().values()) {
            assertThat(snapshotRepo.findByDecisionId(s.approvalDecisionId()).orElseThrow().getContentSha256())
                    .isEqualTo(s.approvalSnapshotSha256());
        }
    }

    @Test
    void t07_timelineFromImmutableHistory() {
        Map<String, Object> then = research.timeline(decisions.get("D"), kThen, 180);
        Map<String, Object> now = research.timeline(decisions.get("D"), kNow, 180);
        List<String> typesThen = ((List<?>) then.get("events")).stream().map(e -> (String) ((Map<?, ?>) e).get("type")).toList();
        List<String> typesNow = ((List<?>) now.get("events")).stream().map(e -> (String) ((Map<?, ?>) e).get("type")).toList();
        assertThat(typesThen).startsWith("CREDIT_DECISION").contains("LOAN_DISBURSED", "SCHEDULE_ESTABLISHED",
                "DELINQUENCY_CHANGED", "DEFAULT_DECLARED").doesNotContain("EVENT_CORRECTED");
        assertThat(typesNow).contains("EVENT_CORRECTED");
        assertThat((Integer) then.get("eventsRecordedAfterKnownAt")).isPositive();
        assertThat((List<?>) then.get("scheduledObligations")).hasSize(3);
        assertThat(research.timeline(decisions.get("F"), kNow, null).get("outcome").toString()).startsWith("UNKNOWN");
        // H as known at kH1: the late observation is invisible, and counted as not yet recorded
        Map<String, Object> h = research.timeline(decisions.get("H"), kH1, 45);
        assertThat(((List<?>) h.get("events")).stream().map(e -> (String) ((Map<?, ?>) e).get("type")).toList())
                .doesNotContain("DELINQUENCY_CHANGED");
    }

    @Test
    void t08_counterfactualBoundaryIsInTheDataModel() {
        List<Map<String, Object>> rows = research.counterfactualBoundary(replayId, obs1.code(), 1, kNow);
        assertThat(rows).hasSize(8).allSatisfy(r -> assertThat(r.get("counterfactualOutcome")).isEqualTo("UNOBSERVED"));
        Map<String, Object> declinedF = rows.stream()
                .filter(r -> r.get("sourceDecisionId").equals(decisions.get("F").toString())).findFirst().orElseThrow();
        assertThat(declinedF.get("actualDecision")).isEqualTo("DECLINED");
        assertThat(declinedF.get("counterfactualDecision")).isEqualTo("APPROVED");
        assertThat(((Map<?, ?>) declinedF.get("actualOutcome")).get("status")).isEqualTo("UNKNOWN");
        UUID cfId = replays.get(replayId).rows().get(0).counterfactual().getId();
        UUID src = replays.get(replayId).rows().get(0).counterfactual().getSourceDecisionId();
        String sql = """
                INSERT INTO research_counterfactual_outcome (id, counterfactual_id, source_decision_id,
                    outcome_definition_code, outcome_definition_version, known_at, actual_decision, counterfactual_decision,
                    actual_outcome_status, actual_outcome_value, counterfactual_outcome, evaluated_at)
                VALUES (?, ?, ?, ?, 1, now() - interval '1 day', ?, 'APPROVED', ?, ?, ?, now())""";
        assertThatThrownBy(() -> f.jdbc.update(sql, UUID.randomUUID(), cfId, src, obs1.code(), "APPROVED", "OBSERVED",
                "OCCURRED", "OCCURRED")).hasStackTraceContaining("cfo_counterfactual_unobserved");
        assertThatThrownBy(() -> f.jdbc.update(sql, UUID.randomUUID(), cfId, src, obs1.code(), "DECLINED", "OBSERVED",
                "NOT_OCCURRED", "UNOBSERVED")).hasStackTraceContaining("cfo_declined_unknown");
    }

    @Test
    void t09_definitionsAreValidatedVersionedAndImmutable() {
        assertThatThrownBy(() -> research.defineOutcome("BAD_DPD", Event.DELINQUENCY_DERIVED, null, 90, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> research.defineOutcome("BAD_DEFAULT", Event.DEFAULT, 30, 90, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> research.defineOutcome("BAD_HORIZON", Event.DEFAULT, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.jdbc.update("UPDATE research_outcome_definition SET definition = definition WHERE code = ?",
                def90.code())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> f.jdbc.update("DELETE FROM research_cohort_definition WHERE code = ?", cohort.code()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> f.jdbc.update("""
                INSERT INTO research_outcome_definition (code, version, definition, definition_hash, created_at, created_by)
                VALUES ('RAW', 1, '{"event":"DEFAULT","horizonDays":90,"thresholdDaysPastDue":5}', ?, now(), 't')""",
                "a".repeat(64))).hasStackTraceContaining("outcome_def_threshold");
    }

    @Test
    void t10_httpEndpoints() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        mvc.perform(post("/api/v1/research/outcome-definitions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"HTTP_DEFAULT_" + sfx + "\",\"event\":\"DEFAULT\",\"horizonDays\":180}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/research/cohorts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"HTTP_COHORT_" + sfx + "\",\"decidedFrom\":\"" + FROM + "\",\"decidedTo\":\"" + TO
                        + "\",\"knownAt\":\"" + kNow + "\"}")).andExpect(status().isCreated());
        JsonNode members = json.readTree(mvc.perform(get("/api/v1/research/cohorts/HTTP_COHORT_" + sfx + "/1/membership"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(members.get("members")).hasSize(8);
        JsonNode o = json.readTree(mvc.perform(get("/api/v1/research/decisions/" + decisions.get("D") + "/outcome")
                .param("definitionCode", "HTTP_DEFAULT_" + sfx).param("definitionVersion", "1")
                .param("knownAt", kNow.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(o.get("status").asText()).isEqualTo("OBSERVED");
        assertThat(o.get("value").asText()).isEqualTo("OCCURRED");
        mvc.perform(get("/api/v1/research/decisions/" + decisions.get("D") + "/outcome-timeline")
                .param("knownAt", kNow.toString())).andExpect(status().isOk());
        JsonNode report = json.readTree(mvc.perform(post("/api/v1/research/outcome-reports")
                .contentType(MediaType.APPLICATION_JSON).content("{\"cohortCode\":\"HTTP_COHORT_" + sfx
                        + "\",\"cohortVersion\":1,\"definitions\":[{\"code\":\"HTTP_DEFAULT_" + sfx
                        + "\",\"version\":1}],\"knowledgeBasis\":\"RESEARCH_CURRENT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = report.get("id").asText();
        mvc.perform(get("/api/v1/research/outcome-reports/" + id)).andExpect(status().isOk());
        JsonNode rep = json.readTree(mvc.perform(get("/api/v1/research/outcome-reports/" + id + "/reproduction"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(rep.get("identical").asBoolean()).isTrue();
        mvc.perform(get("/api/v1/research/cohorts/HTTP_COHORT_" + sfx + "/1/knowledge-comparison")
                .param("definitionCode", "HTTP_DEFAULT_" + sfx).param("definitionVersion", "1")
                .param("laterKnownAt", kH1.toString())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/research/policy-replays/" + replayId + "/outcome-boundary")
                .contentType(MediaType.APPLICATION_JSON).content("{\"definitionCode\":\"HTTP_DEFAULT_" + sfx
                        + "\",\"definitionVersion\":1}")).andExpect(status().isCreated());
    }

    // --------------------------------------------------------------------------- mutations

    @Test
    void m01_fabricatedOutcomeForADeclinedApplicantIsCaught() {
        OutcomeEvaluator mutant = new OutcomeEvaluator() {
            @Override
            protected Result declined(Definition def, Instant cutoff, Instant knownAt) {
                return new Result(def.key(), "OBSERVED", "NOT_OCCURRED", cutoff, knownAt, null, null, null, "", List.of());
            }
        };
        assertThat(violations(mutant, OutcomeResearchService.FROM_SNAPSHOT)).anyMatch(v -> v.startsWith("F "));
    }

    @Test
    void m02_currentLoanStatusIsCaught() {
        OutcomeEvaluator mutant = new OutcomeEvaluator() {
            @Override
            protected LoanHistoryFold.Event firstQualifying(Definition def, Subject s, Instant cutoff, Instant knownAt) {
                if (def.event() == Event.DEFAULT) {
                    String current = LoanHistoryFold.fold(s.obligationId(), s.history(), FAR_FUTURE, FAR_FUTURE).status();
                    return "DEFAULTED".equals(current) ? s.history().get(s.history().size() - 1) : null;
                }
                return super.firstQualifying(def, s, cutoff, knownAt);
            }
        };
        assertThat(violations(mutant, OutcomeResearchService.FROM_SNAPSHOT)).anyMatch(v -> v.startsWith("E DEFAULT_180D"));
    }

    @Test
    void m03_ignoredObservationHorizonIsCaught() {
        OutcomeEvaluator mutant = new OutcomeEvaluator() {
            @Override
            protected Instant outcomeCutoff(Instant decidedAt, Definition def, Instant knownAt) {
                return knownAt;
            }
        };
        assertThat(violations(mutant, OutcomeResearchService.FROM_SNAPSHOT)).anyMatch(v -> v.startsWith("D DEFAULT_90D"));
    }

    @Test
    void m04_eventsRecordedAfterTheKnowledgeCutoffAreCaught() {
        OutcomeEvaluator mutant = new OutcomeEvaluator() {
            @Override
            protected boolean counts(LoanHistoryFold.Event e, Subject s, Instant cutoff, Instant knownAt) {
                return super.counts(e, s, cutoff, Instant.MAX);
            }
        };
        assertThat(violations(mutant, OutcomeResearchService.FROM_SNAPSHOT)).anyMatch(v -> v.startsWith("H bank-observed at kH1"));
    }

    @Test
    void m05_eventsEffectiveAfterTheOutcomeCutoffAreCaught() {
        OutcomeEvaluator mutant = new OutcomeEvaluator() {
            @Override
            protected boolean counts(LoanHistoryFold.Event e, Subject s, Instant cutoff, Instant knownAt) {
                return super.counts(e, s, knownAt, knownAt);
            }
        };
        assertThat(violations(mutant, OutcomeResearchService.FROM_SNAPSHOT)).anyMatch(v -> v.startsWith("D DEFAULT_90D"));
    }

    @Test
    void m06_postDecisionInformationAsDecisionTimeInformationIsCaught() {
        OutcomeResearchService.InformationSource leaky = (decisionId, party, decidedAt, snapshot) -> {
            Map<String, Object> m = new LinkedHashMap<>(OutcomeResearchService.snapshotState(snapshot));
            if (Boolean.TRUE.equals(m.get("captured"))) {
                var app = snapshot.get("application");
                var later = affordability.compute(party, new AffordabilityService.Proposal("USD",
                        app.get("principalMinor").asLong(), app.get("annualRateBps").asInt(),
                        app.get("installmentCount").asInt()), decidedAt, kNow, null);
                JsonNode out = affordability.tree(later.output());
                m.put("completeness", out.at("/completeness/status").asText());
                m.put("incomeState", out.at("/income/state").asText());
            }
            return m;
        };
        assertThat(violations(new OutcomeEvaluator(), leaky)).anyMatch(v -> v.endsWith("differs from the decision snapshot"));
    }

    @Test
    void m07_censoredAsNonDefaultIsCaught() {
        OutcomeEvaluator mutant = new OutcomeEvaluator() {
            @Override
            protected Result censored(Definition def, Subject s, Instant cutoff, Instant knownAt) {
                return new Result(def.key(), "OBSERVED", "NOT_OCCURRED", cutoff, knownAt, null, null, null, "", List.of());
            }
        };
        assertThat(violations(mutant, OutcomeResearchService.FROM_SNAPSHOT)).anyMatch(v -> v.startsWith("H DEFAULT_90D"));
    }

    @Test
    void m08_censoredAsDefaultIsCaught() {
        OutcomeEvaluator mutant = new OutcomeEvaluator() {
            @Override
            protected Result censored(Definition def, Subject s, Instant cutoff, Instant knownAt) {
                return new Result(def.key(), "OBSERVED", "OCCURRED", cutoff, knownAt, null, null, null, "", List.of());
            }
        };
        assertThat(violations(mutant, OutcomeResearchService.FROM_SNAPSHOT)).anyMatch(v -> v.startsWith("H DEFAULT_180D"));
    }

    @Test
    void m09_futureCorrectionsInAnAsKnownThenReconstructionAreCaught() {
        OutcomeEvaluator mutant = new OutcomeEvaluator() {
            @Override
            protected boolean correctionKnown(LoanHistoryFold.Event correction, Instant knownAt) {
                return true;
            }
        };
        assertThat(violations(mutant, OutcomeResearchService.FROM_SNAPSHOT)).contains(
                "D: a correction recorded later appears in the as-known-then outcome");
    }

    @Test
    void m10_hypotheticalOutcomeForACounterfactualApprovalIsCaught() {
        OutcomeEvaluator mutant = new OutcomeEvaluator() {
            @Override
            protected String counterfactualOutcome(String actual, String counterfactual, Result actualOutcome) {
                return "APPROVED".equals(counterfactual) ? "NOT_OCCURRED" : "UNOBSERVED";
            }
        };
        assertThat(violations(mutant, OutcomeResearchService.FROM_SNAPSHOT)).anyMatch(v -> v.startsWith("DECLINED->APPROVED"));
        // and the database refuses to store it
        assertThatThrownBy(() -> research.counterfactualBoundary(replayId, obs1.code(), 1, kNow, mutant))
                .hasStackTraceContaining("cfo_counterfactual_unobserved");
    }
}
