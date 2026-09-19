package com.wbank.platform.error;

/**
 * Two things that must be denominated in the same currency are not.
 *
 * <p>There is no implicit conversion anywhere in this system. Cross-currency movement
 * requires an FX deal with an explicit rate and an FX gain/loss account, which is out
 * of scope for this phase.
 */
public class CurrencyMismatchException extends BusinessRuleViolationException {

    public CurrencyMismatchException(String message) {
        super("ledger.currency_mismatch", message);
    }
}
