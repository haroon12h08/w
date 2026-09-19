package com.wbank.obligation.history;

/**
 * One explicit, versioned credit rule: "INPUT OPERATOR THRESHOLD must hold". There is no
 * scripting: inputs, operators and the failure outcome are closed sets, so every rule can
 * be evaluated, explained and replayed deterministically.
 *
 * @param id        stable rule identifier (e.g. PRINCIPAL_WITHIN_LIMIT)
 * @param input     which decision-time fact the rule reads
 * @param operator  how the observed value is compared with the threshold
 * @param threshold the comparison value (Long, String or Boolean)
 */
public record PolicyRule(String id, Input input, Operator operator, Object threshold) {

    /** Facts a rule may read. Each one is available in a decision snapshot. */
    public enum Input {
        CUSTOMER_STATUS,
        PRINCIPAL_MINOR,
        INSTALLMENT_COUNT,
        ANNUAL_RATE_BPS,
        ANY_EXISTING_OBLIGATION_DEFAULTED,
        MAX_EXISTING_DAYS_PAST_DUE,
        SETTLEMENT_AVAILABLE_MINOR,
        SUBJECT_DAYS_PAST_DUE
    }

    public enum Operator { EQ, LTE, GTE, IS_FALSE }

    /** A rule fails the policy; nothing else is modelled (no scores, no weights). */
    public static final String ON_FAIL = "DECLINE";
}
