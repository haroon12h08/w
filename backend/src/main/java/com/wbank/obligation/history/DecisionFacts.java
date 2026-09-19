package com.wbank.obligation.history;

/**
 * The facts a credit policy is evaluated against, as they stood at decision time.
 * A null value means the fact was not available; rules reading it are INDETERMINATE,
 * never guessed.
 */
public record DecisionFacts(String customerStatus, String currency, Integer currencyScale, Long principalMinor,
                            Long installmentCount, Long annualRateBps, Boolean anyExistingObligationDefaulted,
                            Long maxExistingDaysPastDue, Long settlementAvailableMinor, Long subjectDaysPastDue) {

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
        };
    }
}
