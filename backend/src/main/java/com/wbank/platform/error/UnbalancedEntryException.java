package com.wbank.platform.error;

/**
 * A proposed journal entry does not satisfy the double-entry identity.
 *
 * <p>The database enforces this too (deferred constraint trigger). The application
 * check exists so the caller receives a precise diagnosis rather than a generic
 * integrity violation at commit.
 */
public class UnbalancedEntryException extends BusinessRuleViolationException {

    public UnbalancedEntryException(String message) {
        super("ledger.unbalanced_entry", message);
    }
}
