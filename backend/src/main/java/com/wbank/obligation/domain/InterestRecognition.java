package com.wbank.obligation.domain;

/** When the bank books interest income for a loan. */
public enum InterestRecognition {
    /** Phase-2 loans: income is booked when interest is received. */
    CASH,
    /**
     * Interest of a period is earned in full on that period's due date (period-end
     * accrual): Dr loan interest receivable, Cr interest income. No daily accrual.
     */
    ACCRUAL_PERIOD_END
}
