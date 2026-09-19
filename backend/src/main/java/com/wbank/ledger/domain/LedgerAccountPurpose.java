package com.wbank.ledger.domain;

/** Why a ledger account exists. Drives which module is permitted to own it. */
public enum LedgerAccountPurpose {
    /** The bank's own cash / settlement position. Counter-account for money entering or leaving. */
    INTERNAL_CASH,
    /** Holding account for items that cannot yet be attributed. */
    INTERNAL_SUSPENSE,
    /** The bank's own funds. */
    INTERNAL_EQUITY,
    /** Interest the bank has earned. Revenue: credit-normal. */
    INTERNAL_INTEREST_INCOME,
    /** Backs exactly one customer deposit account (a LIABILITY of the bank). */
    CUSTOMER_DEPOSIT,
    /** Backs exactly one loan position account (an ASSET of the bank: money owed to it). */
    LOAN_RECEIVABLE,
    /** Interest earned on one loan but not yet paid (an ASSET). Created at disbursement. */
    LOAN_INTEREST_RECEIVABLE;

    /**
     * Positions owned by a banking product. Their balances may only change through that
     * product's operations, never through a manual ledger adjustment.
     */
    public boolean isProductPosition() {
        return this == CUSTOMER_DEPOSIT || this == LOAN_RECEIVABLE || this == LOAN_INTEREST_RECEIVABLE;
    }
}
