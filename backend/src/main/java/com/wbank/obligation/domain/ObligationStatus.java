package com.wbank.obligation.domain;

/**
 * Lifecycle of a lending obligation.
 *
 * <pre>
 *   PROPOSED --approve--> APPROVED --disburse--> ACTIVE --fully satisfied--> SETTLED
 *      |  \                  |                     |                           ^
 *      |   decline           cancel          declare default (90+ DPD)         |
 *      v      v              v                     v                           |
 *   CANCELLED DECLINED   CANCELLED            DEFAULTED --fully satisfied------+
 * </pre>
 *
 * PROPOSED is the application (requested terms); APPROVED is the bank's credit decision;
 * ACTIVE begins when the agreement is originated and the principal disbursed (one
 * operation: single-tranche loans). DEFAULTED is a <em>declared</em> state, a decision
 * the bank takes on observable facts; delinquency by contrast is <em>derived</em> and is
 * not a status. SETTLED, DECLINED and CANCELLED are terminal.
 */
public enum ObligationStatus {
    PROPOSED,
    APPROVED,
    DECLINED,
    CANCELLED,
    ACTIVE,
    DEFAULTED,
    SETTLED;

    public boolean canTransitionTo(ObligationStatus target) {
        return switch (this) {
            case PROPOSED -> target == APPROVED || target == DECLINED || target == CANCELLED;
            case APPROVED -> target == ACTIVE || target == CANCELLED;
            case ACTIVE -> target == SETTLED || target == DEFAULTED;
            case DEFAULTED -> target == SETTLED;
            case DECLINED, CANCELLED, SETTLED -> false;
        };
    }

    /** Disbursed and not yet satisfied: money is owed under this obligation. */
    public boolean isServiced() {
        return this == ACTIVE || this == DEFAULTED;
    }
}
