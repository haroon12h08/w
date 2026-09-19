package com.wbank.customer.domain;

/**
 * Lifecycle of the bank's relationship with a party.
 *
 * <pre>
 *   PENDING --activate--> ACTIVE --suspend--> SUSPENDED
 *      |                    ^  |                 |
 *      |                    |  +---reactivate----+
 *      +------close------> CLOSED <----close------+
 * </pre>
 *
 * PENDING exists because a relationship is proposed before it is accepted (due
 * diligence is out of scope, but the gap in time is real). CLOSED is terminal: a party
 * returning later is a new decision, not a silent resumption.
 */
public enum CustomerStatus {
    PENDING,
    ACTIVE,
    /** Relationship retained; no new business (accounts, loans) may start. */
    SUSPENDED,
    CLOSED;

    public boolean canTransitionTo(CustomerStatus target) {
        return switch (this) {
            case PENDING -> target == ACTIVE || target == CLOSED;
            case ACTIVE -> target == SUSPENDED || target == CLOSED;
            case SUSPENDED -> target == ACTIVE || target == CLOSED;
            case CLOSED -> false;
        };
    }
}
