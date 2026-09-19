package com.wbank.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.wbank.obligation.domain.Delinquency;
import com.wbank.obligation.history.LendingEventType;
import com.wbank.obligation.history.LoanHistoryFold;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Evaluates one outcome definition for one historical credit decision, from the loan's
 * immutable event history only. Never from current loan state.
 *
 * <p>For a decision at T under a definition with horizon H, and a researcher's knowledge
 * cut-off K:
 * <ul>
 *   <li>{@code outcome_cutoff = T + H};</li>
 *   <li>an event counts only if {@code T < effective_at <= outcome_cutoff} (after the decision,
 *       within the horizon) AND {@code recorded_at <= K} (known to the researcher);</li>
 *   <li>DECLINED: {@code UNKNOWN}; no loan was made, so no outcome exists to observe, and none
 *       is inferred;</li>
 *   <li>APPROVED with {@code outcome_cutoff > K}: {@code CENSORED}; the window has not been
 *       fully observed, so the value is absent, neither "occurred" nor "did not occur";</li>
 *   <li>APPROVED, window complete, no disbursement visible within it: {@code NOT_APPLICABLE};</li>
 *   <li>otherwise {@code OBSERVED} with value {@code OCCURRED} or {@code NOT_OCCURRED}.</li>
 * </ul>
 *
 * <p>Pure. The protected methods are its individual decisions; mutation tests override them
 * one at a time to prove each wrong choice is detected.
 */
public class OutcomeEvaluator {

    public static final String VERSION = "OUTCOME_EVALUATOR_V1";

    public enum Event { DEFAULT, SETTLEMENT, DELINQUENCY_DERIVED, DELINQUENCY_BANK_OBSERVED }

    public record Definition(String code, int version, Event event, Integer thresholdDaysPastDue, int horizonDays) {
        public Duration horizon() {
            return Duration.ofDays(horizonDays);
        }

        public String key() {
            return code + " v" + version;
        }
    }

    /** One historical decision and its loan's full event history (visibility is applied here). */
    public record Subject(UUID decisionId, UUID obligationId, String decision, Instant decidedAt, int decisionSeq,
                          List<LoanHistoryFold.Event> history) {}

    public record Result(String definition, String status, String value, Instant outcomeCutoff, Instant knownAt,
                         Instant eventEffectiveAt, Instant eventRecordedAt, Long maxDaysPastDueWithinHorizon,
                         String reason, List<Map<String, Object>> corrections) {}

    // ------------------------------------------------------------ decisions (hooks)

    protected Instant outcomeCutoff(Instant decidedAt, Definition d, Instant knownAt) {
        return decidedAt.plus(d.horizon());
    }

    protected boolean windowComplete(Instant cutoff, Instant knownAt) {
        return !cutoff.isAfter(knownAt);
    }

    /** Is an event part of the observed outcome window, as known at {@code knownAt}? */
    protected boolean counts(LoanHistoryFold.Event e, Subject s, Instant cutoff, Instant knownAt) {
        return e.loanSeq() > s.decisionSeq() && e.effectiveAt().isAfter(s.decidedAt())
                && !e.effectiveAt().isAfter(cutoff) && !e.recordedAt().isAfter(knownAt);
    }

    /** A later correction is part of what is known only once it has been recorded. */
    protected boolean correctionKnown(LoanHistoryFold.Event correction, Instant knownAt) {
        return !correction.recordedAt().isAfter(knownAt);
    }

    /** The outcome of a declined application: none was ever observed. */
    protected Result declined(Definition d, Instant cutoff, Instant knownAt) {
        return new Result(d.key(), "UNKNOWN", null, cutoff, knownAt, null, null, null,
                "DECLINED: no loan was made; its outcome was never observed and is not inferred", List.of());
    }

    /** An incomplete window: the value is absent, never a default. */
    protected Result censored(Definition d, Subject s, Instant cutoff, Instant knownAt) {
        return new Result(d.key(), "CENSORED", null, cutoff, knownAt, null, null, null,
                "OBSERVATION_WINDOW_INCOMPLETE: outcome cutoff " + cutoff + " is after the knowledge cutoff " + knownAt,
                List.of());
    }

    /** The qualifying event within the window, or null. */
    protected LoanHistoryFold.Event firstQualifying(Definition d, Subject s, Instant cutoff, Instant knownAt) {
        for (LoanHistoryFold.Event e : s.history()) {
            if (!counts(e, s, cutoff, knownAt)) {
                continue;
            }
            boolean hit = switch (d.event()) {
                case DEFAULT -> e.type() == LendingEventType.DEFAULT_DECLARED;
                case SETTLEMENT -> e.type() == LendingEventType.LOAN_SETTLED;
                case DELINQUENCY_BANK_OBSERVED -> e.type() == LendingEventType.DELINQUENCY_CHANGED
                        && e.payload().path("daysPastDue").asLong() >= d.thresholdDaysPastDue();
                case DELINQUENCY_DERIVED -> false;
            };
            if (hit) {
                return e;
            }
        }
        return null;
    }

    /**
     * The worst days past due in (decidedAt, cutoff], derived from the schedule and repayments
     * as known at {@code knownAt}: DPD can only peak just before a repayment or at the window end.
     */
    protected long maxDaysPastDue(Subject s, Instant cutoff, Instant knownAt) {
        List<Instant> checkpoints = new ArrayList<>();
        for (LoanHistoryFold.Event e : s.history()) {
            if (e.type() == LendingEventType.REPAYMENT_RECEIVED && counts(e, s, cutoff, knownAt)) {
                checkpoints.add(e.effectiveAt().minusNanos(1000));
            }
        }
        checkpoints.add(cutoff);
        long max = 0;
        for (Instant c : checkpoints) {
            Delinquency.Status st = LoanHistoryFold.fold(s.obligationId(), s.history(), c, knownAt).derivedDelinquency();
            if (st != null) {
                max = Math.max(max, st.daysPastDue());
            }
        }
        return max;
    }

    // -------------------------------------------------------------------- evaluate

    public Result evaluate(Definition d, Subject s, Instant knownAt) {
        Instant cutoff = outcomeCutoff(s.decidedAt(), d, knownAt);
        if (!"APPROVED".equals(s.decision())) {
            return declined(d, cutoff, knownAt);
        }
        if (!windowComplete(cutoff, knownAt)) {
            return censored(d, s, cutoff, knownAt);
        }
        boolean disbursed = s.history().stream()
                .anyMatch(e -> e.type() == LendingEventType.LOAN_DISBURSED && counts(e, s, cutoff, knownAt));
        List<Map<String, Object>> corrections = corrections(s, knownAt);
        if (!disbursed) {
            return new Result(d.key(), "NOT_APPLICABLE", null, cutoff, knownAt, null, null, null,
                    "NO_LOAN_DISBURSED_WITHIN_HORIZON: approved, but no loan existed to observe", corrections);
        }
        if (d.event() == Event.DELINQUENCY_DERIVED) {
            long max = maxDaysPastDue(s, cutoff, knownAt);
            return new Result(d.key(), "OBSERVED", max >= d.thresholdDaysPastDue() ? "OCCURRED" : "NOT_OCCURRED",
                    cutoff, knownAt, null, null, max, "DERIVED_FROM_SCHEDULE_AND_REPAYMENTS", corrections);
        }
        LoanHistoryFold.Event hit = firstQualifying(d, s, cutoff, knownAt);
        return new Result(d.key(), "OBSERVED", hit == null ? "NOT_OCCURRED" : "OCCURRED", cutoff, knownAt,
                hit == null ? null : hit.effectiveAt(), hit == null ? null : hit.recordedAt(), null,
                hit == null ? "NO_QUALIFYING_EVENT_WITHIN_HORIZON" : hit.type().name(), corrections);
    }

    /** Corrections to this loan's post-decision events that were known at {@code knownAt}. */
    List<Map<String, Object>> corrections(Subject s, Instant knownAt) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LoanHistoryFold.Event e : s.history()) {
            if (e.type() != LendingEventType.EVENT_CORRECTED || !correctionKnown(e, knownAt)) {
                continue;
            }
            LoanHistoryFold.Event target = s.history().stream().filter(t -> t.id().equals(e.correctsEventId()))
                    .findFirst().orElse(null);
            if (target == null || target.loanSeq() <= s.decisionSeq()) {
                continue;
            }
            JsonNode p = e.payload();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("correctionEventId", e.id().toString());
            c.put("correctsEventType", target.type().name());
            c.put("field", p.path("field").asText());
            c.put("previousValue", p.get("previousValue"));
            c.put("correctedValue", p.get("correctedValue"));
            c.put("recordedAt", e.recordedAt().toString());
            out.add(c);
        }
        return out;
    }

    /**
     * The policy-intervention boundary. The outcome of a hypothetical decision was never
     * observed: an observed outcome belongs only to the decision that was actually taken.
     */
    protected String counterfactualOutcome(String actualDecision, String counterfactualDecision, Result actualOutcome) {
        return "UNOBSERVED";
    }

    public String boundary(String actualDecision, String counterfactualDecision, Result actualOutcome) {
        return counterfactualOutcome(actualDecision, counterfactualDecision, actualOutcome);
    }
}
