package com.wbank.ledger.domain;

public enum LedgerAccountStatus {
    /** Accepts postings in both directions. */
    ACTIVE,
    /** Accepts no postings. Used for investigation holds. */
    FROZEN,
    /** Permanently closed; no further postings. Historic postings remain. */
    CLOSED;

    public boolean acceptsPostings() {
        return this == ACTIVE;
    }
}
