package com.wbank.obligation.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic evaluation of explicit credit rules against decision-time facts. Pure: no
 * clock, no I/O, no randomness. The live approval path and the counterfactual evaluator use
 * this same engine, so replaying the policy actually used reproduces the recorded result.
 */
public final class PolicyEngine {

    public enum Status { PASS, FAIL, INDETERMINATE }

    public record RuleResult(String rule, PolicyRule.Input input, PolicyRule.Operator operator, Object observed,
                             Object threshold, Status status) {
        public boolean passed() {
            return status == Status.PASS;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", rule);
            m.put("input", input.name());
            m.put("operator", operator.name());
            m.put("observed", observed);
            m.put("threshold", threshold);
            m.put("passed", passed());
            m.put("status", status.name());
            return m;
        }
    }

    /** FAIL if any rule fails; INDETERMINATE if none fails but a fact was unavailable; else PASS. */
    public record Evaluation(List<RuleResult> results, Status result) {}

    private PolicyEngine() {}

    public static Evaluation evaluate(List<PolicyRule> rules, DecisionFacts facts) {
        List<RuleResult> results = new ArrayList<>(rules.size());
        for (PolicyRule rule : rules) {
            Object observed = facts.value(rule.input());
            results.add(new RuleResult(rule.id(), rule.input(), rule.operator(), observed, rule.threshold(),
                    observed == null ? Status.INDETERMINATE : holds(rule, observed) ? Status.PASS : Status.FAIL));
        }
        Status overall = results.stream().anyMatch(r -> r.status() == Status.FAIL) ? Status.FAIL
                : results.stream().anyMatch(r -> r.status() == Status.INDETERMINATE) ? Status.INDETERMINATE
                : Status.PASS;
        return new Evaluation(List.copyOf(results), overall);
    }

    private static boolean holds(PolicyRule rule, Object observed) {
        return switch (rule.operator()) {
            case EQ -> observed.equals(rule.threshold());
            case LTE -> asLong(observed) <= asLong(rule.threshold());
            case GTE -> asLong(observed) >= asLong(rule.threshold());
            case IS_FALSE -> Boolean.FALSE.equals(observed);
        };
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("Not a number: " + o);
    }
}
