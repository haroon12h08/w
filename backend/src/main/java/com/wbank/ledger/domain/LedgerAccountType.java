package com.wbank.ledger.domain;

/**
 * The five classical account types of the accounting equation:
 * {@code Assets = Liabilities + Equity (+ Revenue - Expense)}.
 *
 * <p>Customer money is a LIABILITY: the bank owes it. This is the single most
 * important modelling fact in the system, and the reason a customer's "balance"
 * is a credit balance rather than a number the bank owns.
 */
public enum LedgerAccountType {
    ASSET(NormalBalance.DEBIT),
    EXPENSE(NormalBalance.DEBIT),
    LIABILITY(NormalBalance.CREDIT),
    EQUITY(NormalBalance.CREDIT),
    REVENUE(NormalBalance.CREDIT);

    private final NormalBalance normalBalance;

    LedgerAccountType(NormalBalance normalBalance) {
        this.normalBalance = normalBalance;
    }

    public NormalBalance normalBalance() {
        return normalBalance;
    }
}
