package com.wbank.obligation.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The typed content of a credit policy version, and its evaluation. Every rule is explicit,
 * deterministic and reports what it observed against what threshold, so a decision can be
 * explained later exactly as it was evaluated.
 */
public record CreditPolicyRules(boolean requireActiveCustomer, long maxPrincipalMajor, int maxInstallments,
                                int maxAnnualRateBps, boolean blockIfAnyObligationDefaulted,
                                long maxExistingDaysPastDue, long defaultDeclarationMinDaysPastDue) {

    public record RuleResult(String rule, boolean passed, Object observed, Object threshold) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", rule);
            m.put("passed", passed);
            m.put("observed", observed);
            m.put("threshold", threshold);
            return m;
        }
    }

    /** What the approval rules look at, as known at decision time. */
    public record ApprovalFacts(String customerStatus, long principalMinor, int currencyScale, int installmentCount,
                                int annualRateBps, boolean anyObligationDefaulted, long maxExistingDaysPastDue) {}

    public List<RuleResult> evaluateApproval(ApprovalFacts f) {
        long maxPrincipalMinor = Math.multiplyExact(maxPrincipalMajor, (long) Math.pow(10, f.currencyScale()));
        List<RuleResult> r = new ArrayList<>();
        if (requireActiveCustomer) {
            r.add(new RuleResult("CUSTOMER_ACTIVE", "ACTIVE".equals(f.customerStatus()), f.customerStatus(), "ACTIVE"));
        }
        r.add(new RuleResult("PRINCIPAL_WITHIN_LIMIT", f.principalMinor() <= maxPrincipalMinor, f.principalMinor(),
                maxPrincipalMinor));
        r.add(new RuleResult("TERM_WITHIN_LIMIT", f.installmentCount() <= maxInstallments, f.installmentCount(),
                maxInstallments));
        r.add(new RuleResult("RATE_WITHIN_LIMIT", f.annualRateBps() <= maxAnnualRateBps, f.annualRateBps(),
                maxAnnualRateBps));
        if (blockIfAnyObligationDefaulted) {
            r.add(new RuleResult("NO_DEFAULTED_OBLIGATIONS", !f.anyObligationDefaulted(), f.anyObligationDefaulted(),
                    false));
        }
        r.add(new RuleResult("EXISTING_DELINQUENCY_WITHIN_LIMIT", f.maxExistingDaysPastDue() <= maxExistingDaysPastDue,
                f.maxExistingDaysPastDue(), maxExistingDaysPastDue));
        return List.copyOf(r);
    }

    public List<RuleResult> evaluateDefault(long daysPastDue) {
        return List.of(new RuleResult("DEFAULT_THRESHOLD_MET", daysPastDue >= defaultDeclarationMinDaysPastDue,
                daysPastDue, defaultDeclarationMinDaysPastDue));
    }
}
