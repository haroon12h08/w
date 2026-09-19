package com.wbank.ledger.domain;

/**
 * The side on which an account is expected to carry a positive balance.
 *
 * <p>Balances are stored signed in this direction, so that a single predicate
 * ("balance &gt;= floor") is meaningful for both a cash asset and a customer deposit
 * liability, without every call site remembering the sign convention.
 */
public enum NormalBalance {
    DEBIT,
    CREDIT
}
