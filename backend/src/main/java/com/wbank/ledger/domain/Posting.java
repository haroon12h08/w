package com.wbank.ledger.domain;

import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One leg of a double entry. Immutable, in Java and in PostgreSQL (append-only trigger).
 *
 * <p>The amount is always positive; direction carries the sign. This makes "the total
 * debited to this account" and "the total credited" both directly queryable, which is
 * what reconciliation and regulatory reporting actually need.
 *
 * <p>{@code accountSequence} and {@code balanceAfterMinor} are recorded at posting time.
 * Together they let any account statement be reconstructed and independently verified
 * without trusting the cached balance row.
 */
@Entity
@Table(name = "posting")
public class Posting {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    @Column(name = "entry_leg", nullable = false, updatable = false)
    private short entryLeg;

    @Column(name = "ledger_account_id", nullable = false, updatable = false)
    private UUID ledgerAccountId;

    @Column(name = "currency", nullable = false, updatable = false, length = 3, columnDefinition = "bpchar")
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false)
    private PostingDirection direction;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "account_sequence", nullable = false, updatable = false)
    private long accountSequence;

    @Column(name = "balance_after_minor", nullable = false, updatable = false)
    private long balanceAfterMinor;

    @Column(name = "booked_at", nullable = false, updatable = false)
    private Instant bookedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Posting() {
        // for JPA
    }

    public static Posting create(UUID id,
                                 UUID journalEntryId,
                                 int entryLeg,
                                 UUID ledgerAccountId,
                                 Money amount,
                                 PostingDirection direction,
                                 long accountSequence,
                                 long balanceAfterMinor,
                                 Instant bookedAt) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A posting amount must be strictly positive: " + amount);
        }
        Posting posting = new Posting();
        posting.id = id;
        posting.journalEntryId = journalEntryId;
        posting.entryLeg = (short) entryLeg;
        posting.ledgerAccountId = ledgerAccountId;
        posting.currencyCode = amount.currency().code();
        posting.direction = direction;
        posting.amountMinor = amount.minorUnits();
        posting.accountSequence = accountSequence;
        posting.balanceAfterMinor = balanceAfterMinor;
        posting.bookedAt = bookedAt;
        posting.createdAt = bookedAt;
        return posting;
    }

    public Money amount(CurrencyUnit currency) {
        return Money.ofMinorUnits(amountMinor, currency);
    }

    /** Debit-positive signed amount; the sum of these over any closed set must be zero. */
    public long signedAmountMinor() {
        return direction.rawSignum() * amountMinor;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public short getEntryLeg() {
        return entryLeg;
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public PostingDirection getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public long getAccountSequence() {
        return accountSequence;
    }

    public long getBalanceAfterMinor() {
        return balanceAfterMinor;
    }

    public Instant getBookedAt() {
        return bookedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
