package com.wbank.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.domain.Delinquency;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.obligation.history.DecisionContext;
import com.wbank.obligation.history.DecisionReconstructionService;
import com.wbank.obligation.history.LoanHistoryFold;
import com.wbank.obligation.history.PointInTimeService;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second, separate step of a policy experiment: setting a replay's counterfactual
 * decisions against what was actually OBSERVED afterwards.
 *
 * <p>Three things are kept apart in every row and in the report:
 * <ul>
 *   <li><b>observed outcome</b>: only exists for historically approved applications, and only
 *       as far as the observation window and {@code outcomeKnownAt} allow (else censored);</li>
 *   <li><b>counterfactual decision</b>: what the alternative policy would have decided;</li>
 *   <li><b>causal conclusion</b>: always {@code NOT_ESTABLISHED}. Replay shows what a policy would
 *       have decided about the people the bank actually saw; it does not show what would have
 *       happened under that policy.</li>
 * </ul>
 * Historically declined applications have no observable outcome and are reported as
 * UNKNOWABLE; no reject-inference outcome is invented. Nothing here ranks, scores or
 * recommends a policy.
 */
@Service
public class ReplayOutcomeEvaluator {

    public static final String NOT_ESTABLISHED = "NOT_ESTABLISHED";

    private final PolicyReplayService replays;
    private final PointInTimeService pit;
    private final OutcomeEvaluationRepository evaluations;
    private final ObjectMapper json;
    private final Clock clock;

    public ReplayOutcomeEvaluator(PolicyReplayService replays, PointInTimeService pit,
                                  OutcomeEvaluationRepository evaluations, ObjectMapper json, Clock clock) {
        this.replays = replays;
        this.pit = pit;
        this.evaluations = evaluations;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public OutcomeEvaluation evaluate(UUID replayId, Duration horizon, Instant outcomeKnownAt) {
        Instant now = clock.instant();
        Instant knownAt = outcomeKnownAt == null ? now : outcomeKnownAt;
        if (knownAt.isAfter(now)) {
            throw new IllegalArgumentException("Outcomes cannot be known after now: " + knownAt);
        }
        Map<String, Object> report = report(replays.get(replayId), horizon, knownAt);
        String canonical = CanonicalJson.canonical(json, json.valueToTree(report));
        return evaluations.save(OutcomeEvaluation.of(replayId, horizon.toString(), knownAt, now,
                RequestContext.forOperation("research.outcome_evaluation").actor(), canonical,
                CanonicalJson.sha256(canonical)));
    }

    @Transactional(readOnly = true)
    public OutcomeEvaluation get(UUID evaluationId) {
        return evaluations.findById(evaluationId).orElseThrow(() -> NotFoundException.of("outcome_evaluation", evaluationId));
    }

    Map<String, Object> report(PolicyReplayService.ReplayView view, Duration horizon, Instant knownAt) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int actualApproved = 0;
        int actualDeclined = 0;
        int cfApproved = 0;
        int cfDeclined = 0;
        int cfUndetermined = 0;
        int notEvaluable = 0;
        int changed = 0;
        int verdictChanged = 0;
        int observedDefaults = 0;
        int observedSettlements = 0;
        int observedEarlySettlements = 0;
        int stillOpen = 0;
        int censored = 0;
        int reached1 = 0;
        int reached30 = 0;
        int reached90 = 0;
        int unknowable = 0;
        int unknowableCfApproved = 0;
        Map<String, Integer> maxDpdBuckets = new TreeMap<>();
        Map<String, Map<String, Integer>> cells = new TreeMap<>();

        for (PolicyReplayService.Row r : view.rows()) {
            CounterfactualDecision cf = r.counterfactual();
            String actual = r.actual().decision();
            String hypothetical = cf.getHypotheticalDecision();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("decisionId", cf.getSourceDecisionId().toString());
            row.put("decidedAt", cf.getOriginalDecidedAt().toString());
            row.put("snapshotId", cf.getSourceSnapshotId() == null ? null : cf.getSourceSnapshotId().toString());
            row.put("actualDecision", actual);
            row.put("actualPolicy", cf.getActualPolicyCode() == null ? null
                    : cf.getActualPolicyCode() + " v" + cf.getActualPolicyVersion());
            row.put("counterfactualDecision", hypothetical);
            row.put("alternativePolicy", cf.getAlternativePolicyCode() + " v" + cf.getAlternativePolicyVersion());
            row.put("differsFromActualDecision", r.differsFromActualDecision());
            row.put("differsFromActualPolicyVerdict", r.differsFromActualPolicyVerdict());
            row.put("causalConclusion", NOT_ESTABLISHED);

            if ("NOT_EVALUABLE".equals(hypothetical)) {
                notEvaluable++;
                row.put("observedOutcome", "NOT_ASSESSED");
                row.put("finding", "NOT_EVALUABLE: no decision snapshot was captured for this decision");
                rows.add(row);
                continue;
            }
            switch (hypothetical) {
                case "APPROVED" -> cfApproved++;
                case "DECLINED" -> cfDeclined++;
                default -> cfUndetermined++;
            }
            changed += r.differsFromActualDecision() ? 1 : 0;
            verdictChanged += r.differsFromActualPolicyVerdict() ? 1 : 0;
            String observed;
            if ("APPROVED".equals(actual)) {
                actualApproved++;
                Instant horizonEnd = cf.getOriginalDecidedAt().plus(horizon);
                Instant outcomeAt = horizonEnd.isAfter(knownAt) ? knownAt : horizonEnd;
                boolean isCensored = horizonEnd.isAfter(knownAt);
                List<LoanHistoryFold.Event> history = pit.history(r.actual().obligationId());
                int decisionSeq = history.stream().filter(e -> r.actual().decisionId().toString()
                        .equals(e.payload().path("decisionId").asText())).findFirst().orElseThrow().loanSeq();
                DecisionContext.Outcome o = DecisionReconstructionService.outcome(r.actual().obligationId(), history,
                        decisionSeq, cf.getOriginalDecidedAt(), outcomeAt);
                observed = o.defaulted() ? "DEFAULTED" : o.settled() ? "SETTLED" : "OPEN_NO_DEFAULT_OBSERVED";
                observedDefaults += o.defaulted() ? 1 : 0;
                observedSettlements += o.settled() ? 1 : 0;
                observedEarlySettlements += o.settledEarly() ? 1 : 0;
                stillOpen += (!o.defaulted() && !o.settled()) ? 1 : 0;
                censored += isCensored ? 1 : 0;
                reached1 += o.maxDaysPastDue() >= 1 ? 1 : 0;
                reached30 += o.maxDaysPastDue() >= 30 ? 1 : 0;
                reached90 += o.maxDaysPastDue() >= 90 ? 1 : 0;
                maxDpdBuckets.merge(Delinquency.Bucket.of(o.maxDaysPastDue()).name(), 1, Integer::sum);
                row.put("observedOutcome", observed);
                row.put("observedMaxDaysPastDue", o.maxDaysPastDue());
                row.put("observedSettledEarly", o.settledEarly());
                row.put("censored", isCensored);
                row.put("outcomeObservedUntil", outcomeAt.toString());
                row.put("finding", finding(hypothetical, observed, isCensored));
            } else {
                actualDeclined++;
                unknowable++;
                unknowableCfApproved += "APPROVED".equals(hypothetical) ? 1 : 0;
                observed = "UNKNOWABLE";
                row.put("observedOutcome", observed);
                row.put("finding", "APPROVED".equals(hypothetical)
                        ? "The alternative policy would have approved an application the bank declined. What would have "
                                + "happened had it been approved was never observed and is not estimated."
                        : "Declined under both. No outcome exists to observe.");
            }
            cells.computeIfAbsent(actual + "->" + hypothetical, k -> new TreeMap<>()).merge(observed, 1, Integer::sum);
            rows.add(row);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "policy-replay-outcome-report/v1");
        Map<String, Object> population = new LinkedHashMap<>();
        population.put("label", view.population().path("label").asText());
        population.put("definition", view.population());
        population.put("decisions", view.rows().size());
        population.put("evaluable", view.rows().size() - notEvaluable);
        population.put("notEvaluable", notEvaluable);
        report.put("population", population);
        report.put("alternativePolicy", view.replay().getAlternativePolicyCode() + " v"
                + view.replay().getAlternativePolicyVersion());
        report.put("replayId", view.replay().getId().toString());
        report.put("replayOutputHash", view.replay().getOutputHash());
        report.put("outcomeHorizon", horizon.toString());
        report.put("outcomeKnownAt", knownAt.toString());

        Map<String, Object> decisions = new LinkedHashMap<>();
        decisions.put("actualApprovals", actualApproved);
        decisions.put("actualDeclines", actualDeclined);
        decisions.put("counterfactualApprovals", cfApproved);
        decisions.put("counterfactualDeclines", cfDeclined);
        decisions.put("counterfactualUndetermined", cfUndetermined);
        decisions.put("changedFromActualDecision", changed);
        decisions.put("changedFromActualPolicyVerdict", verdictChanged);
        report.put("decisions", decisions);

        Map<String, Object> observedOutcomes = new LinkedHashMap<>();
        observedOutcomes.put("population", "historically APPROVED applications only (the only observable outcomes)");
        observedOutcomes.put("count", actualApproved);
        observedOutcomes.put("observedDefaults", observedDefaults);
        observedOutcomes.put("observedSettlements", observedSettlements);
        observedOutcomes.put("observedEarlySettlements", observedEarlySettlements);
        observedOutcomes.put("openWithoutObservedDefault", stillOpen);
        observedOutcomes.put("censored", censored);
        Map<String, Object> delinquency = new LinkedHashMap<>();
        delinquency.put("reached1DaysPastDue", reached1);
        delinquency.put("reached30DaysPastDue", reached30);
        delinquency.put("reached90DaysPastDue", reached90);
        delinquency.put("maxDaysPastDueBuckets", maxDpdBuckets);
        observedOutcomes.put("delinquency", delinquency);
        report.put("observedOutcomes", observedOutcomes);

        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("historicallyDeclined", unknowable);
        unknown.put("ofWhichCounterfactualWouldApprove", unknowableCfApproved);
        unknown.put("reason", "A declined application never produced a loan, so its outcome was never observed. "
                + "No reject-inference estimate is made.");
        report.put("outcomeUnknowable", unknown);
        report.put("crossTabulation", cells);

        Map<String, Object> interpretation = new LinkedHashMap<>();
        interpretation.put("causalConclusion", NOT_ESTABLISHED);
        interpretation.put("whatThisShows", List.of(
                "How the alternative policy's rules classify the applications the bank actually decided, using only "
                        + "what was known at each decision.",
                "For historically approved applications only: which observed outcomes (defaults, settlements) fall in "
                        + "applications the alternative policy would have declined."));
        interpretation.put("whatThisDoesNotShow", List.of(
                "That the alternative policy is better or worse: outcomes of applications it would have approved but the "
                        + "bank declined are unobservable.",
                "What would have happened under the alternative policy: a different policy changes who applies, pricing, "
                        + "behaviour and the bank's book; replay holds all of that fixed.",
                "Statistical significance: populations here are small and not sampled for inference.",
                "Anything about censored observations beyond their observation window."));
        interpretation.put("ranking", "none: this instrument does not score, rank or recommend policies");
        report.put("interpretation", interpretation);
        report.put("rows", rows);
        return report;
    }

    private static String finding(String hypothetical, String observed, boolean censored) {
        String suffix = censored ? " (observation window not yet complete: censored)" : "";
        if ("DECLINED".equals(hypothetical)) {
            return switch (observed) {
                case "DEFAULTED" -> "The alternative policy would have declined this application; under the actual "
                        + "approval a default was observed. Observed, not a proof that the policy is superior." + suffix;
                case "SETTLED" -> "The alternative policy would have declined this application; under the actual "
                        + "approval it was repaid in full (an observed good outcome that would have been forgone)." + suffix;
                default -> "The alternative policy would have declined this application; no default has been observed "
                        + "under the actual approval." + suffix;
            };
        }
        if ("APPROVED".equals(hypothetical)) {
            return "Same decision as actually taken; the observed outcome is " + observed + "." + suffix;
        }
        return "The alternative policy could not decide (a required fact was not captured)." + suffix;
    }

    public JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
