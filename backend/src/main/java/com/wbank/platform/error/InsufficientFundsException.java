package com.wbank.platform.error;

import com.wbank.platform.money.Money;
import java.util.UUID;

/** A debit would push an account below its authorised floor. */
public class InsufficientFundsException extends BusinessRuleViolationException {

    public InsufficientFundsException(UUID ledgerAccountId, Money available, Money requested) {
        super("ledger.insufficient_funds",
                "Ledger account %s has %s available but %s was requested"
                        .formatted(ledgerAccountId, available, requested));
    }
}
