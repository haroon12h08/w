package com.wbank.payment.domain;

/**
 * Lifecycle of a payment. Only states that can actually be observed are modelled.
 *
 * <pre>
 *                ┌──> SETTLED ──reverse──> REVERSED
 *   AUTHORIZED ──┼──> CANCELLED   (initiator withdrew before execution)
 *                ├──> EXPIRED     (authorisation not executed in time)
 *                └──> FAILED      (execution could not complete; no financial effect)
 *   REJECTED                      (failed validation or authorisation; never held funds)
 * </pre>
 *
 * <b>Deliberately absent:</b> INITIATED/RECEIVED and VALIDATED are never observable: an
 * instruction is validated and authorised (or rejected) in the same transaction that records
 * it, so those steps are captured as events, not states. PROCESSING is absent because an
 * internal book transfer settles atomically in one transaction; it becomes necessary only
 * with an external rail, where submission and settlement confirmation are separated in time.
 */
public enum PaymentStatus {
    AUTHORIZED,
    SETTLED,
    REJECTED,
    CANCELLED,
    EXPIRED,
    FAILED,
    REVERSED;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case AUTHORIZED -> target == SETTLED || target == CANCELLED || target == EXPIRED || target == FAILED;
            case SETTLED -> target == REVERSED;
            case REJECTED, CANCELLED, EXPIRED, FAILED, REVERSED -> false;
        };
    }

    /** Whether money changed hands (and stayed changed). */
    public boolean hasFinancialEffect() {
        return this == SETTLED;
    }
}
