package com.wbank.obligation.history;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The stored parameters of a credit policy version (the JSON in {@code credit_policy.rules}).
 * They compile, deterministically, into explicit {@link PolicyRule}s; evaluation is done by
 * {@link PolicyEngine}. Optional parameters (null) produce no rule.
 *
 * @param minSettlementAvailableMajor optional (added in V11): minimum available balance on the
 *                                    settlement account at decision time
 * @param maxDebtServiceRatioBps      optional (Phase 7): maximum debt-service ratio on verified income;
 *                                    INDETERMINATE when the snapshot has no determinate verified basis
 * @param requireCompleteInformation  optional (Phase 7): the affordability information must be COMPLETE
 */
public record CreditPolicyRules(boolean requireActiveCustomer, long maxPrincipalMajor, int maxInstallments,
                                int maxAnnualRateBps, boolean blockIfAnyObligationDefaulted,
                                long maxExistingDaysPastDue, long defaultDeclarationMinDaysPastDue,
                                Long minSettlementAvailableMajor, Long maxDebtServiceRatioBps,
                                Boolean requireCompleteInformation) {

    /** Constructor for policies without the Phase 7 information rules. */
    public CreditPolicyRules(boolean requireActiveCustomer, long maxPrincipalMajor, int maxInstallments,
                             int maxAnnualRateBps, boolean blockIfAnyObligationDefaulted, long maxExistingDaysPastDue,
                             long defaultDeclarationMinDaysPastDue, Long minSettlementAvailableMajor) {
        this(requireActiveCustomer, maxPrincipalMajor, maxInstallments, maxAnnualRateBps, blockIfAnyObligationDefaulted,
                maxExistingDaysPastDue, defaultDeclarationMinDaysPastDue, minSettlementAvailableMajor, null, null);
    }

    /** Backwards-compatible constructor for policies without the optional rule. */
    public CreditPolicyRules(boolean requireActiveCustomer, long maxPrincipalMajor, int maxInstallments,
                             int maxAnnualRateBps, boolean blockIfAnyObligationDefaulted, long maxExistingDaysPastDue,
                             long defaultDeclarationMinDaysPastDue) {
        this(requireActiveCustomer, maxPrincipalMajor, maxInstallments, maxAnnualRateBps,
                blockIfAnyObligationDefaulted, maxExistingDaysPastDue, defaultDeclarationMinDaysPastDue, null, null, null);
    }

    /** The approval rules, in a fixed order. Money thresholds are converted to minor units exactly. */
    public List<PolicyRule> approvalRules(int currencyScale) {
        List<PolicyRule> r = new ArrayList<>();
        if (requireActiveCustomer) {
            r.add(new PolicyRule("CUSTOMER_ACTIVE", PolicyRule.Input.CUSTOMER_STATUS, PolicyRule.Operator.EQ, "ACTIVE"));
        }
        r.add(new PolicyRule("PRINCIPAL_WITHIN_LIMIT", PolicyRule.Input.PRINCIPAL_MINOR, PolicyRule.Operator.LTE,
                minor(maxPrincipalMajor, currencyScale)));
        r.add(new PolicyRule("TERM_WITHIN_LIMIT", PolicyRule.Input.INSTALLMENT_COUNT, PolicyRule.Operator.LTE,
                (long) maxInstallments));
        r.add(new PolicyRule("RATE_WITHIN_LIMIT", PolicyRule.Input.ANNUAL_RATE_BPS, PolicyRule.Operator.LTE,
                (long) maxAnnualRateBps));
        if (blockIfAnyObligationDefaulted) {
            r.add(new PolicyRule("NO_DEFAULTED_OBLIGATIONS", PolicyRule.Input.ANY_EXISTING_OBLIGATION_DEFAULTED,
                    PolicyRule.Operator.IS_FALSE, false));
        }
        r.add(new PolicyRule("EXISTING_DELINQUENCY_WITHIN_LIMIT", PolicyRule.Input.MAX_EXISTING_DAYS_PAST_DUE,
                PolicyRule.Operator.LTE, maxExistingDaysPastDue));
        if (minSettlementAvailableMajor != null) {
            r.add(new PolicyRule("SETTLEMENT_AVAILABLE_AT_LEAST", PolicyRule.Input.SETTLEMENT_AVAILABLE_MINOR,
                    PolicyRule.Operator.GTE, minor(minSettlementAvailableMajor, currencyScale)));
        }
        if (maxDebtServiceRatioBps != null) {
            r.add(new PolicyRule("DEBT_SERVICE_RATIO_WITHIN_LIMIT", PolicyRule.Input.DEBT_SERVICE_RATIO_BPS,
                    PolicyRule.Operator.LTE, maxDebtServiceRatioBps));
        }
        if (Boolean.TRUE.equals(requireCompleteInformation)) {
            r.add(new PolicyRule("INFORMATION_COMPLETE", PolicyRule.Input.INFORMATION_COMPLETENESS,
                    PolicyRule.Operator.EQ, "COMPLETE"));
        }
        return List.copyOf(r);
    }

    public List<PolicyRule> defaultRules() {
        return List.of(new PolicyRule("DEFAULT_THRESHOLD_MET", PolicyRule.Input.SUBJECT_DAYS_PAST_DUE,
                PolicyRule.Operator.GTE, defaultDeclarationMinDaysPastDue));
    }

    public PolicyEngine.Evaluation evaluateApproval(DecisionFacts facts) {
        return PolicyEngine.evaluate(approvalRules(facts.currencyScale()), facts);
    }

    public PolicyEngine.Evaluation evaluateDefault(long daysPastDue) {
        return PolicyEngine.evaluate(defaultRules(),
                new DecisionFacts(null, null, null, null, null, null, null, null, null, daysPastDue));
    }

    private static long minor(long major, int scale) {
        return BigDecimal.valueOf(major).movePointRight(scale).longValueExact();
    }
}
