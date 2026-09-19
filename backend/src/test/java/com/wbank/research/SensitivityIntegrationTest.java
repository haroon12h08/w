package com.wbank.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.information.FinancialInformationService;
import com.wbank.obligation.LoanService;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.obligation.history.CorrectionService;
import com.wbank.obligation.history.CreditDecisionSnapshotRepository;
import com.wbank.obligation.history.LendingEventType;
import com.wbank.obligation.history.LoanHistoryFold;
import com.wbank.obligation.history.PointInTimeService;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.time.DatabaseTime;
import com.wbank.research.OutcomeEvaluator.Event;
import com.wbank.research.OutcomeResearchService.KnowledgeBasis;
import com.wbank.research.SensitivityService.Configuration;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.MutableClock;
import com.wbank.support.OutcomeResearchHistory;
import com.wbank.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Constructor;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;

/**
 * Research sensitivity over the outcome-research history of {@link OutcomeResearchHistory}
 * (base 2023-03-07; decisions at T = 10:00; H decided 2023-09-08; no other test decides in this
 * window). Cutoffs: kThen 2023-09-08 09:00 (before H's decision), kH1 = H+46d (H's 45-day window
 * closed, its late observation not yet recorded), kNow 2023-11-07 12:00.
 */
@Import(MutableClock.Config.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SensitivityIntegrationTest extends PostgresIntegrationTest {

    static final LocalDate BASE = LocalDate.of(2023, 3, 7);
    static final Instant FROM = Instant.parse("2023-03-01T00:00:00Z");
    static final Instant TO = Instant.parse("2024-03-01T00:00:00Z");
    static final List<Integer> HORIZONS = List.of(30, 60, 90, 180, 365);

    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;
    @Autowired LoanService loans;
    @Autowired MutableClock clock;
    @Autowired CreditDecisionSnapshotRepository snapshotRepo;
    @Autowired FinancialInformationService information;
    @Autowired CorrectionService corrections;
    @Autowired PointInTimeService pit;
    @Autowired OutcomeResearchService research;
    @Autowired SensitivityService sensitivity;
    @Autowired PolicyReplayService replays;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @PersistenceContext EntityManager entityManager;

    OutcomeResearchHistory.Built h;
    String cohort;
    String cohortApproved;
    String cohortEarly;
    String default180;
    String settled180;
    String observed45;

    @BeforeAll
    void setUp() {
        h = OutcomeResearchHistory.build(BASE, d, loans, clock, snapshotRepo, information, corrections, pit);
        String sfx = "_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        default180 = "S_DEFAULT_180D" + sfx;
        settled180 = "S_SETTLED_180D" + sfx;
        observed45 = "S_DPD1_OBSERVED_45D" + sfx;
        research.defineOutcome(default180, Event.DEFAULT, null, 180, null);
        research.defineOutcome(settled180, Event.SETTLEMENT, null, 180, null);
        research.defineOutcome(observed45, Event.DELINQUENCY_BANK_OBSERVED, 1, 45, null);
        cohort = "S_ALL" + sfx;
        cohortApproved = "S_APPROVED" + sfx;
        cohortEarly = "S_EARLY" + sfx;
        research.defineCohort(cohort, FROM, TO, h.kNow(), null, false, "all eight decisions");
        research.defineCohort(cohortApproved, FROM, TO, h.kNow(), List.of("APPROVED"), false, "approved only");
        research.defineCohort(cohortEarly, FROM, TO, h.hDecided().minusSeconds(1), null, false, "known before H");
    }

    Configuration config(String cohortCode, String definition, Integer horizon, Instant knownAt, String dimension) {
        return new Configuration(cohortCode, 1, definition, 1, horizon,
                knownAt == null ? KnowledgeBasis.AS_KNOWN_AT_DECISION : KnowledgeBasis.AS_KNOWN_AT, knownAt,
                dimension == null ? "completeness" : dimension);
    }

    SensitivityService.HorizonRequest horizonRequest() {
        return new SensitivityService.HorizonRequest(config(cohort, default180, null, h.kNow(), null), HORIZONS);
    }

    SensitivityService.KnowledgeRequest knowledgeRequest() {
        return new SensitivityService.KnowledgeRequest(new Configuration(cohort, 1, observed45, 1, null, null, null,
                "completeness"), List.of(new SensitivityAnalysis.Cutoff("kThen", h.kThen()),
                new SensitivityAnalysis.Cutoff("kH1", h.kH1()), new SensitivityAnalysis.Cutoff("kNow", h.kNow())), true);
    }

    JsonNode tree(Object o) {
        return json.valueToTree(o);
    }

    JsonNode step(JsonNode report, int i) {
        return report.at("/steps/" + i);
    }

    /**
     * The research facts this history must yield: horizon sensitivity (DEFAULT, known at kNow)
     * and knowledge sensitivity (bank-observed 1+ DPD within 45 days). Empty iff correct.
     */
    List<String> violations(OutcomeEvaluator eval) {
        List<String> v = new ArrayList<>();
        JsonNode hr = tree(sensitivity.compute(SensitivityService.Kind.HORIZON, horizonRequest(), eval).report());
        int[][] expected = {{6, 0, 0}, {6, 0, 0}, {5, 1, 0}, {5, 1, 2}, {0, 6, 0}}; // observed, censored, occurred
        for (int i = 0; i < HORIZONS.size(); i++) {
            JsonNode t = step(hr, i).get("totals");
            int[] got = {t.get("observed").asInt(), t.get("censored").asInt(), t.get("occurred").asInt()};
            if (got[0] != expected[i][0] || got[1] != expected[i][1] || got[2] != expected[i][2]) {
                v.add("horizon " + HORIZONS.get(i) + ": observed/censored/occurred " + got[0] + "/" + got[1] + "/" + got[2]);
            }
            if (!step(hr, i).at("/selection/accountingHolds").asBoolean()) {
                v.add("horizon " + HORIZONS.get(i) + ": selection accounting broken");
            }
        }
        if (step(hr, 4).at("/totals/eventsKnownWhileCensored").asInt() != 2) {
            v.add("horizon 365: the two known defaults must stay visible while censored");
        }
        hr.get("invariants").properties().forEach(e -> {
            if (!e.getValue().get("holds").asBoolean()) {
                v.add("horizon invariant " + e.getKey());
            }
        });
        JsonNode kr = tree(sensitivity.compute(SensitivityService.Kind.KNOWLEDGE, knowledgeRequest(), eval).report());
        if (step(kr, 0).at("/totals/observed").asInt() != 0) {
            v.add("decision-time knowledge observed something");
        }
        String hAtH1 = changeOf(kr, 3, "H");
        if (!"OBSERVED:NOT_OCCURRED->OBSERVED:OCCURRED".equals(hAtH1)) {
            v.add("H kH1->kNow: " + hAtH1);
        }
        kr.get("invariants").properties().forEach(e -> {
            if (!e.getValue().get("holds").asBoolean()) {
                v.add("knowledge invariant " + e.getKey());
            }
        });
        return v;
    }

    String changeOf(JsonNode report, int stepIndex, String story) {
        String id = h.decisions().get(story).toString();
        for (var e : step(report, stepIndex).path("changesFromPreviousCutoff").properties()) {
            for (JsonNode x : e.getValue()) {
                if (x.asText().equals(id)) {
                    return e.getKey();
                }
            }
        }
        return "unchanged";
    }

    /**
     * Every table outside research_*: banking, lending history, snapshots, information facts.
     * Pending JPA writes are flushed first: a JDBC query does not trigger a Hibernate flush, and an
     * unflushed banking write inside the measured transaction would otherwise be invisible.
     */
    String bankingFingerprint() {
        if (entityManager.isJoinedToTransaction()) {
            entityManager.flush();
        }
        List<String> tables = f.jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                   AND table_name NOT LIKE 'research_%' AND table_name <> 'flyway_schema_history'
                 ORDER BY table_name""", String.class);
        StringBuilder sb = new StringBuilder();
        for (String t : tables) {
            sb.append(t).append('=').append(f.jdbc.queryForObject(
                    "SELECT md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM " + t + " x", String.class))
                    .append(';');
        }
        return sb.toString();
    }

    <T> T hermetic(Runnable perturb, Supplier<T> measure) {
        return f.tx.execute(s -> {
            f.jdbc.execute("SET LOCAL session_replication_role = replica");
            perturb.run();
            T result = measure.get();
            s.setRollbackOnly();
            return result;
        });
    }

    String hash(Object o) {
        return CanonicalJson.sha256(CanonicalJson.canonical(json, json.valueToTree(o)));
    }

    // ============================================================= the experiments

    @Test
    void e01_horizonSensitivity() {
        assertThat(violations(new OutcomeEvaluator())).isEmpty();
        JsonNode r = tree(sensitivity.horizons(horizonRequest()).get("report"));
        // With a FIXED knowledge cutoff, a longer horizon asks a longer question, so it censors MORE, not less:
        assertThat(step(r, 0).at("/totals/censored").asInt()).isZero();                 // 30 days
        assertThat(step(r, 3).at("/totals/censored").asInt()).isEqualTo(1);             // 180 days: H
        assertThat(step(r, 3).at("/totals/censoredShare/fraction").asText()).isEqualTo("1/6");
        // lengthening reveals D's and E's defaults (~T+121d) without hiding any known event
        assertThat(step(r, 3).at("/transitionsFromPreviousHorizon/OBSERVED:NOT_OCCURRED->OBSERVED:OCCURRED")).hasSize(2);
        assertThat(step(r, 4).at("/transitionsFromPreviousHorizon/OBSERVED:OCCURRED->CENSORED")).hasSize(2);
        assertThat(step(r, 4).at("/totals/eventsKnownWhileCensored").asInt()).isEqualTo(2);
        assertThat(step(r, 4).at("/newlyObservable").asInt()).isZero();
        assertThat(r.at("/invariants/knownEventNeverDisappears/holds").asBoolean()).isTrue();
        assertThat(r.at("/invariants/fixedCutoffLongerHorizonNeverCompletesAWindow/holds").asBoolean()).isTrue();
        assertThat(r.at("/invariants/declinedAlwaysUnknown/holds").asBoolean()).isTrue();
    }

    @Test
    void e02_knowledgeSensitivity() {
        JsonNode r = tree(sensitivity.knowledge(knowledgeRequest()).get("report"));
        assertThat(step(r, 0).get("label").asText()).isEqualTo("DECISION_TIME");
        assertThat(step(r, 0).at("/totals/censored").asInt()).isEqualTo(6);
        assertThat(step(r, 0).at("/totals/unknown").asInt()).isEqualTo(2);
        assertThat(changeOf(r, 2, "H")).isEqualTo("CENSORED->OBSERVED:NOT_OCCURRED");        // kThen -> kH1
        assertThat(changeOf(r, 3, "H")).isEqualTo("OBSERVED:NOT_OCCURRED->OBSERVED:OCCURRED"); // late record
        assertThat(r.at("/invariants/laterCutoffNeverReopensAWindow/holds").asBoolean()).isTrue();
    }

    @Test
    void e03_declinedStayUnknownAndCounterfactualApprovalsUnobserved() {
        for (JsonNode s : tree(sensitivity.horizons(horizonRequest()).get("report")).get("steps")) {
            assertThat(s.at("/selection/declined").asInt()).isEqualTo(2);
            assertThat(s.at("/totals/unknown").asInt()).isEqualTo(2);
            assertThat(s.at("/selection/accountingHolds").asBoolean()).isTrue();
        }
        for (JsonNode s : tree(sensitivity.knowledge(knowledgeRequest()).get("report")).get("steps")) {
            assertThat(s.at("/totals/unknown").asInt()).isEqualTo(2);
        }
        UUID replay = replays.replay(new PolicyReplayService.Population("s-" + UUID.randomUUID(), FROM, TO),
                "LENDING_CREDIT_POLICY", 1).replay().getId();
        var rows = research.counterfactualBoundary(replay, settled180, 1, h.kNow());
        var f = rows.stream().filter(x -> x.get("sourceDecisionId").equals(h.decisions().get("F").toString()))
                .findFirst().orElseThrow();
        assertThat(f.get("counterfactualDecision")).isEqualTo("APPROVED");
        assertThat(f.get("counterfactualOutcome")).isEqualTo("UNOBSERVED");
    }

    @Test
    void e04_comparisonDecomposesEveryDifference() {
        var a = config(cohort, default180, null, h.kNow(), "completeness");
        var b = config(cohortApproved, settled180, 90, h.kH1(), "incomeState");
        JsonNode r = tree(sensitivity.compare(new SensitivityService.ComparisonRequest(a, b)).get("report"));
        JsonNode steps = r.get("decomposition");
        assertThat(steps.at("/0/membership/removed")).hasSize(2).allSatisfy(x ->
                assertThat(x.get("reason").asText()).isEqualTo("EXCLUSION_RULE: DECISION_KIND_NOT_INCLUDED"));
        assertThat(steps.at("/2/parameter").asText()).isEqualTo("HORIZON");
        assertThat(steps.at("/2/outcomeChanges/OBSERVED:OCCURRED->OBSERVED:NOT_OCCURRED")).hasSize(2); // D, E
        assertThat(steps.at("/3/parameter").asText()).isEqualTo("OUTCOME_DEFINITION");
        assertThat(steps.at("/3/outcomeChanges/OBSERVED:NOT_OCCURRED->OBSERVED:OCCURRED")).hasSize(1); // B settled
        assertThat(steps.at("/4/regrouped")).hasSize(6);
        assertThat(steps.at("/4/outcomeChanges").asInt()).isZero();
        assertThat(r.get("unattributedDifferences")).isEmpty();

        JsonNode early = tree(sensitivity.compare(new SensitivityService.ComparisonRequest(a,
                config(cohortEarly, default180, null, h.kNow(), "completeness"))).get("report"));
        assertThat(early.at("/decomposition/0/membership/removed/0/decisionId").asText())
                .isEqualTo(h.decisions().get("H").toString());
        assertThat(early.at("/decomposition/0/membership/removed/0/reason").asText()).startsWith("POPULATION_DEFINITION");
        assertThat(early.get("unattributedDifferences")).isEmpty();
    }

    @Test
    void e05_boundaryEventsExposeTimestampSensitivity() {
        JsonNode r = tree(sensitivity.boundaries(new SensitivityService.BoundaryRequest(
                config(cohort, observed45, null, h.kH1(), null), Duration.ofDays(5))).get("report"));
        String hId = h.decisions().get("H").toString();
        List<JsonNode> knowledge = new ArrayList<>();
        r.get("events").forEach(e -> {
            if (e.get("decisionId").asText().equals(hId) && e.get("boundary").asText().equals("KNOWLEDGE_CUTOFF")
                    && e.get("eventType").asText().equals("DELINQUENCY_CHANGED")) {
                knowledge.add(e);
            }
        });
        assertThat(knowledge).singleElement().satisfies(e -> {
            assertThat(e.get("side").asText()).isEqualTo("AFTER");
            assertThat(e.get("knownAtCutoff").asBoolean()).isFalse();
        });
        assertThat(r.get("decisionsNearObservabilityFlip")).anySatisfy(x ->
                assertThat(x.get("decisionId").asText()).isEqualTo(hId));
    }

    @Test
    void e06_populationViewAndGroupingChangeOnlyTheDescription() {
        String before = bankingFingerprint();
        JsonNode byCompleteness = tree(sensitivity.population(config(cohort, default180, null, h.kNow(), "completeness"))
                .get("report"));
        JsonNode byIncome = tree(sensitivity.population(config(cohort, default180, null, h.kNow(), "incomeState"))
                .get("report"));
        assertThat(byCompleteness.get("totals")).isEqualTo(byIncome.get("totals"));
        assertThat(byCompleteness.at("/selection/approvedObservable").asInt()).isEqualTo(5);
        assertThat(byCompleteness.at("/crossTabulations/completeness/cells/INSUFFICIENT/DECLINED/UNKNOWN").asInt())
                .isEqualTo(2);
        assertThat(byCompleteness.at("/crossTabulations/completeness/order").asText()).contains("not a ranking");
        assertThat(bankingFingerprint()).isEqualTo(before);
    }

    @Test
    void e07_researchNeverChangesBankingStateOrSnapshots() {
        String before = bankingFingerprint();
        sensitivity.horizons(horizonRequest());
        sensitivity.knowledge(knowledgeRequest());
        sensitivity.compare(new SensitivityService.ComparisonRequest(config(cohort, default180, null, h.kNow(), null),
                config(cohortApproved, settled180, 90, h.kH1(), "incomeState")));
        sensitivity.boundaries(new SensitivityService.BoundaryRequest(config(cohort, observed45, null, h.kH1(), null),
                Duration.ofDays(2)));
        sensitivity.population(config(cohort, default180, null, null, null));
        assertThat(bankingFingerprint()).isEqualTo(before);
        for (var s : h.run().stories().values()) {
            assertThat(snapshotRepo.findByDecisionId(s.approvalDecisionId()).orElseThrow().getContentSha256())
                    .isEqualTo(s.approvalSnapshotSha256());
        }
    }

    // ======================================================== reproducibility & time

    @Test
    void r01_everyKindReproduces() {
        List<Map<String, Object>> reports = List.of(sensitivity.horizons(horizonRequest()),
                sensitivity.knowledge(knowledgeRequest()),
                sensitivity.compare(new SensitivityService.ComparisonRequest(config(cohort, default180, null, h.kNow(), null),
                        config(cohortEarly, settled180, 30, h.kH1(), "obligationsState"))),
                sensitivity.boundaries(new SensitivityService.BoundaryRequest(
                        config(cohort, observed45, null, h.kH1(), null), Duration.ofHours(36))),
                sensitivity.population(config(cohortApproved, observed45, null, null, "incomeState")));
        for (Map<String, Object> r : reports) {
            var rep = sensitivity.reproduce(UUID.fromString((String) r.get("id")));
            assertThat(rep.identical()).as(rep.kind()).isTrue();
        }
        assertThat(sensitivity.horizons(horizonRequest()).get("outputHash")).isEqualTo(reports.get(0).get("outputHash"));
        assertThatThrownBy(() -> f.jdbc.update("UPDATE research_sensitivity_report SET kind = 'HORIZON'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void r02_cutoffsAreExplicitAndNeverFromTheFuture() {
        assertThatThrownBy(() -> sensitivity.population(new Configuration(cohort, 1, default180, 1, null,
                KnowledgeBasis.RESEARCH_CURRENT, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sensitivity.population(new Configuration(cohort, 1, default180, 1, null,
                KnowledgeBasis.AS_KNOWN_AT, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sensitivity.population(config(cohort, default180, null, clock.instant().plusSeconds(1),
                null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sensitivity.horizons(new SensitivityService.HorizonRequest(
                config(cohort, default180, null, h.kNow(), null), List.of(90, 30)))).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * PostgreSQL keeps microseconds and ROUNDS a finer instant on write; Java keeps nanoseconds.
     * An instant must be normalized before it is used, hashed or stored, or the stored cutoff
     * and the one the result was computed from differ.
     */
    @Test
    void r03_nanosecondCutoffsAreNormalizedToDatabasePrecision() {
        Instant raw = h.kH1().plusNanos(123_456_789);
        Instant stored = f.jdbc.queryForObject("SELECT ?::timestamptz", Timestamp.class, Timestamp.from(raw)).toInstant();
        assertThat(stored).isNotEqualTo(raw).isEqualTo(h.kH1().plusNanos(123_457_000)); // rounded, not truncated
        Instant normalized = DatabaseTime.normalize(raw);
        assertThat(f.jdbc.queryForObject("SELECT ?::timestamptz", Timestamp.class, Timestamp.from(normalized))
                .toInstant()).isEqualTo(normalized);

        Map<String, Object> r = sensitivity.population(config(cohort, observed45, null, raw, null));
        assertThat(sensitivity.get(UUID.fromString((String) r.get("id"))).toString()).contains(normalized.toString());
        assertThat(sensitivity.reproduce(UUID.fromString((String) r.get("id"))).identical()).isTrue();
    }

    // ================================================================== mutations

    @Test
    void m02_unnormalizedNanosecondsCannotBeReproduced() {
        Instant raw = h.kH1().plusNanos(123_456_789);
        Instant roundTripped = f.jdbc.queryForObject("SELECT ?::timestamptz", Timestamp.class, Timestamp.from(raw))
                .toInstant();
        var computedFromRaw = sensitivity.compute(SensitivityService.Kind.POPULATION,
                config(cohort, observed45, null, raw, null), new OutcomeEvaluator());
        var recomputedFromStore = sensitivity.compute(SensitivityService.Kind.POPULATION,
                config(cohort, observed45, null, roundTripped, null), new OutcomeEvaluator());
        assertThat(hash(computedFromRaw.report())).isNotEqualTo(hash(recomputedFromStore.report()));
    }

    @Test
    void m03_eventsAfterTheKnowledgeCutoffAreCaught() {
        assertThat(violations(new OutcomeEvaluator() {
            @Override
            protected boolean counts(LoanHistoryFold.Event e, Subject s, Instant cutoff, Instant knownAt) {
                return super.counts(e, s, cutoff, Instant.MAX);
            }
        })).anyMatch(v -> v.startsWith("H kH1->kNow"));
    }

    @Test
    void m04_eventsAfterTheHorizonAreCaught() {
        assertThat(violations(new OutcomeEvaluator() {
            @Override
            protected boolean counts(LoanHistoryFold.Event e, Subject s, Instant cutoff, Instant knownAt) {
                return super.counts(e, s, knownAt, knownAt);
            }
        })).anyMatch(v -> v.startsWith("horizon 30:"));
    }

    @Test
    void m06_censoredCountedAsNonDefaultIsCaught() {
        assertThat(violations(new OutcomeEvaluator() {
            @Override
            protected Result censored(Definition def, Subject s, Instant cutoff, Instant knownAt) {
                return new Result(def.key(), "OBSERVED", "NOT_OCCURRED", cutoff, knownAt, null, null, null, "", List.of());
            }
        })).anyMatch(v -> v.startsWith("horizon 365:"));
    }

    @Test
    void m07_inferredOutcomesForDeclinedApplicantsAreCaught() {
        assertThat(violations(new OutcomeEvaluator() {
            @Override
            protected Result declined(Definition def, Instant cutoff, Instant knownAt) {
                return new Result(def.key(), "OBSERVED", "NOT_OCCURRED", cutoff, knownAt, null, null, null, "", List.of());
            }
        })).contains("horizon invariant declinedAlwaysUnknown", "knowledge invariant declinedAlwaysUnknown");
    }

    @Test
    void m09_aResearchDefinitionMutatedInPlaceIsCaught() {
        assertThatThrownBy(() -> f.jdbc.update("UPDATE research_outcome_definition SET definition = definition WHERE code = ?",
                default180)).isInstanceOf(DataAccessException.class);
        UUID earlier = UUID.fromString((String) sensitivity.horizons(horizonRequest()).get("id"));
        Object[] outcome = hermetic(() -> f.jdbc.update("""
                        UPDATE research_outcome_definition SET definition = jsonb_set(definition, '{horizonDays}', '30')
                         WHERE code = ?""", default180),
                () -> {
                    Throwable refused = null;
                    try {
                        sensitivity.population(config(cohort, default180, null, h.kNow(), null));
                    } catch (ConflictException e) {
                        refused = e;
                    }
                    return new Object[] {refused, sensitivity.reproduce(earlier).identical()};
                });
        assertThat(outcome[0]).isInstanceOf(ConflictException.class);
        assertThat(outcome[1]).isEqualTo(false);
        assertThat(sensitivity.reproduce(earlier).identical()).isTrue();
    }

    @Test
    void m10_currentMutableLoanStateIsCaught() {
        Instant farFuture = Instant.parse("9999-01-01T00:00:00Z");
        assertThat(violations(new OutcomeEvaluator() {
            @Override
            protected LoanHistoryFold.Event firstQualifying(Definition def, Subject s, Instant cutoff, Instant knownAt) {
                if (def.event() == Event.DEFAULT) {
                    String current = LoanHistoryFold.fold(s.obligationId(), s.history(), farFuture, farFuture).status();
                    return "DEFAULTED".equals(current) ? s.history().get(s.history().size() - 1) : null;
                }
                return super.firstQualifying(def, s, cutoff, knownAt);
            }
        })).isNotEmpty();
    }

    @Test
    void m11_aSnapshotModifiedDuringAnalysisIsCaught() {
        UUID earlier = UUID.fromString((String) sensitivity.population(config(cohort, default180, null, h.kNow(), null))
                .get("id"));
        UUID aDecision = h.decisions().get("A");
        Object[] outcome = hermetic(() -> f.jdbc.update("""
                        UPDATE credit_decision_snapshot SET content = jsonb_set(content, '{application,principalMinor}', '1')
                         WHERE decision_id = ?""", aDecision),
                () -> {
                    JsonNode r = tree(sensitivity.compute(SensitivityService.Kind.POPULATION,
                            config(cohort, default180, null, h.kNow(), null), new OutcomeEvaluator()).report());
                    return new Object[] {r.at("/inputs/memberships").toString(), sensitivity.reproduce(earlier).identical(),
                            research.membership(cohort, 1).excluded()};
                });
        assertThat(outcome[1]).isEqualTo(false);
        assertThat(outcome[2].toString()).contains(aDecision.toString(), "SNAPSHOT_FAILED_INTEGRITY_CHECK");
    }

    /** The isolation invariant catches a research operation that also appends to lending history. */
    @Test
    void m12_researchExecutionWritingToLendingHistoryIsCaught() {
        UUID defaultEvent = pit.history(h.run().get("E").obligationId()).stream()
                .filter(e -> e.type() == LendingEventType.DEFAULT_DECLARED).findFirst().orElseThrow().id();
        Boolean isolated = f.tx.execute(s -> {
            String before = bankingFingerprint();
            sensitivity.compute(SensitivityService.Kind.HORIZON, horizonRequest(), new OutcomeEvaluator());
            corrections.correct(defaultEvent, "rationale", "written by a research run", "mutation");
            boolean same = before.equals(bankingFingerprint());
            s.setRollbackOnly();
            return same;
        });
        assertThat(isolated).isFalse();
    }

    /** Research services and controllers may read the banking record; they must not hold its write services. */
    @Test
    void m12_researchCodeCannotDependOnBankingWriteServices() throws Exception {
        Set<String> forbidden = Set.of("com.wbank.obligation.LoanService", "com.wbank.obligation.history.CorrectionService",
                "com.wbank.ledger.LedgerService", "com.wbank.payment.PaymentService", "com.wbank.account.AccountService",
                "com.wbank.customer.CustomerService", "com.wbank.customer.PartyService",
                "com.wbank.account.DepositService", "com.wbank.information.FinancialInformationService");
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> offending = new ArrayList<>();
        int scanned = 0;
        for (var bean : scanner.findCandidateComponents("com.wbank.research")) {
            scanned++;
            for (Constructor<?> c : Class.forName(bean.getBeanClassName()).getConstructors()) {
                for (Class<?> p : c.getParameterTypes()) {
                    if (forbidden.contains(p.getName())) {
                        offending.add(bean.getBeanClassName() + " <- " + p.getName());
                    }
                }
            }
        }
        assertThat(scanned).isGreaterThanOrEqualTo(8);
        assertThat(offending).isEmpty();
    }

    // ======================================================================= HTTP

    @Test
    void h01_httpContract() throws Exception {
        String cfg = """
                {"cohortCode":"%s","cohortVersion":1,"definitionCode":"%s","definitionVersion":1,
                 "knowledgeBasis":"AS_KNOWN_AT","knownAt":"%s"}""".formatted(cohort, default180, h.kNow());
        JsonNode horizon = json.readTree(mvc.perform(post("/api/v1/research/sensitivity/horizons")
                .contentType(MediaType.APPLICATION_JSON).content("{\"configuration\":" + cfg + ",\"horizonsDays\":[30,180]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = horizon.get("id").asText();
        mvc.perform(get("/api/v1/research/sensitivity/reports/" + id)).andExpect(status().isOk());
        JsonNode rep = json.readTree(mvc.perform(get("/api/v1/research/sensitivity/reports/" + id + "/reproduction"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(rep.get("identical").asBoolean()).isTrue();
        mvc.perform(post("/api/v1/research/sensitivity/knowledge-cutoffs").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"configuration":{"cohortCode":"%s","cohortVersion":1,"definitionCode":"%s","definitionVersion":1},
                         "cutoffs":[{"label":"then","knownAt":"%s"},{"label":"now","knownAt":"%s"}],"includeDecisionTime":true}"""
                        .formatted(cohort, observed45, h.kH1(), h.kNow()))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/research/sensitivity/comparisons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"a\":" + cfg + ",\"b\":" + cfg.replace(cohort, cohortApproved) + "}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/research/sensitivity/boundaries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"configuration\":" + cfg + ",\"window\":\"PT48H\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/research/sensitivity/population").contentType(MediaType.APPLICATION_JSON)
                .content(cfg)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/research/sensitivity/population").contentType(MediaType.APPLICATION_JSON)
                .content(cfg.replace("\"AS_KNOWN_AT\"", "\"RESEARCH_CURRENT\""))).andExpect(status().isBadRequest());
    }

    // =========================================================== database rules

    @Test
    void d01_anEventCannotBeRecordedBeforeItTookEffect() {
        UUID obligation = h.run().get("A").obligationId();
        int next = pit.history(obligation).size() + 1;
        Instant t = h.kNow();
        assertThatThrownBy(() -> f.jdbc.update("""
                INSERT INTO lending_event (id, obligation_id, loan_seq, event_type, event_kind, effective_at, recorded_at,
                    payload, actor, actor_type, origin)
                VALUES (?, ?, ?, 'REPAYMENT_RECEIVED', 'FACT', ?, ?, '{}'::jsonb, 't', 'SYSTEM', 'LIVE')""",
                UUID.randomUUID(), obligation, next, Timestamp.from(t.plusSeconds(1)), Timestamp.from(t)))
                .hasStackTraceContaining("lending_event_not_future_dated");
    }
}
