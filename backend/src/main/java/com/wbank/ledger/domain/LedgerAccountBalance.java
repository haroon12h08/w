package com.wbank.ledger.domain;

import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Materialised balance of a ledger account.
 *
 * <p><b>This is a projection, not the truth.</b> The truth is {@code SUM(posting)}.
 * It exists because summing an account's entire posting history on every request is
 * not viable, and it is updated inside the very same transaction that writes the
 * postings, so it can never lag. {@code LedgerReconciliationService} proves the two
 * agree, and the tests assert it.
 *
 * <p>{@code balanceMinor} is signed in the account's normal-balance direction.
 * {@code minBalanceMinor} is the authorised floor: {@code null} means unconstrained
 * (internal accounts), {@code 0} means no overdraft, a negative value is a granted
 * overdraft limit. A PostgreSQL CHECK constraint enforces the floor independently of
 * the application.
 */
@Entity
@Table(name = "ledger_account_balance")
public class LedgerAccountBalance {

    @Id
    @Column(name = "ledger_account_id", nullable = false, updatable = false)
    private UUID ledgerAccountId;

    @Column(name = "currency", nullable = false, updatable = false, length = 3, columnDefinition = "bpchar")
    private String currencyCode;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "total_debits_minor", nullable = false)
    private long totalDebitsMinor;

    @Column(name = "total_credits_minor", nullable = false)
    private long totalCreditsMinor;

    @Column(name = "posting_count", nullable = false)
    private long postingCount;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    @Column(name = "min_balance_minor")
    private Long minBalanceMinor;

    @Column(name = "last_posted_at")
    private Instant lastPostedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected LedgerAccountBalance() {
        // for JPA
    }

    public static LedgerAccountBalance openingZero(UUID ledgerAccountId,
                                                   String currencyCode,
                                                   Long minBalanceMinor,
                                                   Instant now) {
        LedgerAccountBalance balance = new LedgerAccountBalance();
        balance.ledgerAccountId = ledgerAccountId;
        balance.currencyCode = currencyCode;
        balance.balanceMinor = 0L;
        balance.totalDebitsMinor = 0L;
        balance.totalCreditsMinor = 0L;
        balance.postingCount = 0L;
        balance.lastSequence = 0L;
        balance.minBalanceMinor = minBalanceMinor;
        balance.updatedAt = now;
        return balance;
    }

    /** The next per-account posting sequence number. Callers must hold the row lock. */
    public long nextSequence() {
        return lastSequence + 1;
    }

    /**
     * Returns the balance this account would have after applying {@code amount} in
     * {@code direction}, without mutating anything. Used to decide whether a posting
     * is permissible before any of it is written.
     */
    public long projectedBalanceMinor(NormalBalance normalBalance, PostingDirection direction, long amountMinor) {
        return Math.addExact(balanceMinor, (long) direction.signumFor(normalBalance) * amountMinor);
    }

    public boolean wouldBreachFloor(NormalBalance normalBalance, PostingDirection direction, long amountMinor) {
        if (minBalanceMinor == null) {
            return false;
        }
        return projectedBalanceMinor(normalBalance, direction, amountMinor) < minBalanceMinor;
    }

    /** Amount that may still be debited (for a credit-normal account: withdrawn) before the floor. */
    public long availableMinor() {
        long floor = minBalanceMinor == null ? Long.MIN_VALUE / 4 : minBalanceMinor;
        return Math.subtractExact(balanceMinor, floor);
    }

    public void apply(NormalBalance normalBalance, PostingDirection direction, long amountMinor, Instant now) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Balance can only be moved by a positive amount");
        }
        this.balanceMinor = projectedBalanceMinor(normalBalance, direction, amountMinor);
        if (direction == PostingDirection.DEBIT) {
            this.totalDebitsMinor = Math.addExact(this.totalDebitsMinor, amountMinor);
        } else {
            this.totalCreditsMinor = Math.addExact(this.totalCreditsMinor, amountMinor);
        }
        this.postingCount += 1;
        this.lastSequence += 1;
        this.lastPostedAt = now;
        this.updatedAt = now;
    }

    public void setMinBalanceMinor(Long minBalanceMinor, Instant now) {
        this.minBalanceMinor = minBalanceMinor;
        this.updatedAt = now;
    }

    public Money balance(CurrencyUnit currency) {
        return Money.ofMinorUnits(balanceMinor, currency);
    }

    public Money available(CurrencyUnit currency) {
        return Money.ofMinorUnits(availableMinor(), currency);
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public long getTotalDebitsMinor() {
        return totalDebitsMinor;
    }

    public long getTotalCreditsMinor() {
        return totalCreditsMinor;
    }

    public long getPostingCount() {
        return postingCount;
    }

    public long getLastSequence() {
        return lastSequence;
    }

    public Long getMinBalanceMinor() {
        return minBalanceMinor;
    }

    public Instant getLastPostedAt() {
        return lastPostedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
