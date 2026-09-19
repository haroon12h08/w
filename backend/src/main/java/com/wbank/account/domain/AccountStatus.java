package com.wbank.account.domain;

import com.wbank.ledger.domain.LedgerAccountStatus;

/**
 * Lifecycle of a financial position.
 *
 * <pre>
 *   PENDING --activate--> ACTIVE --freeze--> FROZEN
 *      |                   |  ^                |
 *      |                   |  +---unfreeze-----+
 *      +--close--> CLOSED <+ (close requires ACTIVE, zero balance, no open obligation)
 * </pre>
 *
 * <ul>
 *   <li>PENDING: the contract exists but no ledger position does. Money cannot move.</li>
 *   <li>FROZEN cannot close directly: a hold (investigation, legal order) must be lifted
 *       deliberately, not dodged by closing the account.</li>
 *   <li>CLOSED is terminal. A closed position never silently resumes; new business needs
 *       a new account.</li>
 * </ul>
 */
public enum AccountStatus {
    PENDING,
    ACTIVE,
    FROZEN,
    CLOSED;

    public boolean canTransitionTo(AccountStatus target) {
        return switch (this) {
            case PENDING -> target == ACTIVE || target == CLOSED;
            case ACTIVE -> target == FROZEN || target == CLOSED;
            case FROZEN -> target == ACTIVE;
            case CLOSED -> false;
        };
    }

    /** The ledger status that must mirror this status once a ledger position exists. */
    public LedgerAccountStatus ledgerStatus() {
        return switch (this) {
            case ACTIVE -> LedgerAccountStatus.ACTIVE;
            case FROZEN -> LedgerAccountStatus.FROZEN;
            case CLOSED -> LedgerAccountStatus.CLOSED;
            case PENDING -> throw new IllegalStateException("A PENDING account has no ledger position");
        };
    }
}
