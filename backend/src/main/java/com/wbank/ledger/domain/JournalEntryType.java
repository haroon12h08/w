package com.wbank.ledger.domain;

/**
 * The financial meaning of an entry.
 *
 * <p>Kept small on purpose. Every value here corresponds to an operation the system
 * actually supports; speculative banking products are not pre-declared.
 */
public enum JournalEntryType {
    /** Money enters the bank: debit cash, credit customer deposit. */
    CASH_DEPOSIT,
    /** Money leaves the bank: debit customer deposit, credit cash. */
    CASH_WITHDRAWAL,
    /** Money moves between two customer deposit accounts. Balance sheet total unchanged. */
    CUSTOMER_TRANSFER,
    /** Exact mirror of a previously posted entry. The only permitted form of correction. */
    REVERSAL,
    /**
     * A journal originated directly against the ledger (via the ledger API) rather than
     * by a product module. Subject to exactly the same invariants as every other entry.
     */
    ADJUSTMENT,
    /** Loan principal paid out: debit loan receivable, credit the borrower's deposit. */
    LOAN_DISBURSEMENT,
    /** Borrower pays: debit deposit, credit loan receivable (principal) and interest income. */
    LOAN_REPAYMENT,
    /** Settlement of an authorised payment instruction between two customer accounts. */
    PAYMENT_TRANSFER,
    /** Interest earned at a period end: debit loan interest receivable, credit interest income. */
    INTEREST_ACCRUAL
}
