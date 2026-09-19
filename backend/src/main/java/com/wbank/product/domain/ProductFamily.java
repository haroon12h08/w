package com.wbank.product.domain;

import com.wbank.ledger.domain.LedgerAccountPurpose;
import com.wbank.ledger.domain.LedgerAccountType;

/**
 * The economic nature of positions opened under a product, seen from the bank's books.
 *
 * <p>This is the single point where "what the customer bought" meets "how the bank
 * accounts for it": a deposit is money the bank owes (LIABILITY); a loan is money owed
 * to the bank (ASSET). PostgreSQL re-checks the mapping on every account.
 */
public enum ProductFamily {
    DEPOSIT(LedgerAccountType.LIABILITY, LedgerAccountPurpose.CUSTOMER_DEPOSIT),
    LOAN(LedgerAccountType.ASSET, LedgerAccountPurpose.LOAN_RECEIVABLE);

    private final LedgerAccountType ledgerType;
    private final LedgerAccountPurpose ledgerPurpose;

    ProductFamily(LedgerAccountType ledgerType, LedgerAccountPurpose ledgerPurpose) {
        this.ledgerType = ledgerType;
        this.ledgerPurpose = ledgerPurpose;
    }

    public LedgerAccountType ledgerType() {
        return ledgerType;
    }

    public LedgerAccountPurpose ledgerPurpose() {
        return ledgerPurpose;
    }
}
