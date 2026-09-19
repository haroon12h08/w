package com.wbank.ledger.domain;

/**
 * One of the two sides of a double entry.
 *
 * <p>Debit and credit are not "in" and "out". Their effect depends on the account:
 * a debit increases an asset and decreases a liability. {@link #signumFor} encodes
 * that rule in exactly one place.
 */
public enum PostingDirection {
    DEBIT,
    CREDIT;

    /** Sign of this posting when accumulating a raw (debit-positive) total. */
    public int rawSignum() {
        return this == DEBIT ? 1 : -1;
    }

    /** Sign of this posting's effect on a balance expressed in {@code normalBalance} direction. */
    public int signumFor(NormalBalance normalBalance) {
        boolean sameSide = (this == DEBIT && normalBalance == NormalBalance.DEBIT)
                || (this == CREDIT && normalBalance == NormalBalance.CREDIT);
        return sameSide ? 1 : -1;
    }

    public PostingDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
