package com.wbank.obligation.history;

/**
 * The kinds of thing that can happen to a loan, and what each one is.
 *
 * <ul>
 *   <li><b>FACT</b>: something that happened (money moved, a schedule was agreed).</li>
 *   <li><b>DECISION</b>: a choice made by a person or system; its effective time is the decision time.</li>
 *   <li><b>OBSERVATION</b>: a derived value the bank materialised at the time (delinquency);
 *       recorded so that what the bank <em>saw</em> is preserved, but always re-derivable
 *       from facts.</li>
 *   <li><b>CORRECTION</b>: a later amendment to information in an earlier event; never a rewrite.</li>
 * </ul>
 */
public enum LendingEventType {
    APPLICATION_RECEIVED(Kind.FACT, "PROPOSED"),
    CREDIT_DECISION(Kind.DECISION, null),      // APPROVED or DECLINED, from the payload
    APPLICATION_CANCELLED(Kind.FACT, "CANCELLED"),
    LOAN_DISBURSED(Kind.FACT, "ACTIVE"),
    SCHEDULE_ESTABLISHED(Kind.FACT, null),
    INTEREST_ACCRUED(Kind.FACT, null),
    REPAYMENT_RECEIVED(Kind.FACT, null),
    DELINQUENCY_CHANGED(Kind.OBSERVATION, null),
    DEFAULT_DECLARED(Kind.DECISION, "DEFAULTED"),
    LOAN_SETTLED(Kind.FACT, "SETTLED"),
    EVENT_CORRECTED(Kind.CORRECTION, null);

    public enum Kind { FACT, DECISION, OBSERVATION, CORRECTION }

    private final Kind kind;
    private final String impliedStatus;

    LendingEventType(Kind kind, String impliedStatus) {
        this.kind = kind;
        this.impliedStatus = impliedStatus;
    }

    public Kind kind() {
        return kind;
    }

    /** The obligation status this event establishes, if it is a status-changing event. */
    public String impliedStatus() {
        return impliedStatus;
    }
}
