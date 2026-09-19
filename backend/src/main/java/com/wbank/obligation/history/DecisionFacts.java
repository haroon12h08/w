package com.wbank.obligation.history;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The facts a credit policy is evaluated against, as they stood at decision time.
 * A null value means the fact was not available; rules reading it are INDETERMINATE,
 * never guessed.
 *
 * <p>The last three components come from the financial-information section of a version-2
 * decision snapshot. They are omitted from JSON when absent, so facts drawn from a version-1
 * snapshot serialise exactly as they did before that section existed (old counterfactual
 * inputs, and therefore their hashes, are unchanged).
 */
public record DecisionFacts(String customerStatus, String currency, Integer currencyScale, Long principalMinor,
                            Long installmentCount, Long annualRateBps, Boolean anyExistingObligationDefaulted,
                            Long maxExistingDaysPastDue, Long settlementAvailableMinor, Long subjectDaysPastDue,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Long verifiedMonthlyIncomeMinor,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Long debtServiceRatioBps,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String informationCompleteness) {

    public DecisionFacts(String customerStatus, String currency, Integer currencyScale, Long principalMinor,
                         Long installmentCount, Long annualRateBps, Boolean anyExistingObligationDefaulted,
                         Long maxExistingDaysPastDue, Long settlementAvailableMinor, Long subjectDaysPastDue) {
        this(customerStatus, currency, currencyScale, principalMinor, installmentCount, annualRateBps,
                anyExistingObligationDefaulted, maxExistingDaysPastDue, settlementAvailableMinor, subjectDaysPastDue,
                null, null, null);
    }

    public Object value(PolicyRule.Input input) {
        return switch (input) {
            case CUSTOMER_STATUS -> customerStatus;
            case PRINCIPAL_MINOR -> principalMinor;
            case INSTALLMENT_COUNT -> installmentCount;
            case ANNUAL_RATE_BPS -> annualRateBps;
            case ANY_EXISTING_OBLIGATION_DEFAULTED -> anyExistingObligationDefaulted;
            case MAX_EXISTING_DAYS_PAST_DUE -> maxExistingDaysPastDue;
            case SETTLEMENT_AVAILABLE_MINOR -> settlementAvailableMinor;
            case SUBJECT_DAYS_PAST_DUE -> subjectDaysPastDue;
            case VERIFIED_MONTHLY_INCOME_MINOR -> verifiedMonthlyIncomeMinor;
            case DEBT_SERVICE_RATIO_BPS -> debtServiceRatioBps;
            case INFORMATION_COMPLETENESS -> informationCompleteness;
        };
    }
}
