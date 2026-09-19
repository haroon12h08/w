package com.wbank.obligation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Money actually received against an obligation. It is a business fact that points at
 * the journal entry recording its financial effect; the two are distinct (a repayment
 * is "the borrower paid 250.00 against loan L"; the journal is "debit deposit, credit loan
 * receivable, credit interest income"). Append-only.
 */
@Entity
@Immutable
@Table(name = "obligation_repayment")
public class Repayment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "obligation_id", nullable = false, updatable = false)
    private UUID obligationId;

    @Column(name = "currency", nullable = false, updatable = false, length = 3, columnDefinition = "bpchar")
    private String currencyCode;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "principal_minor", nullable = false, updatable = false)
    private long principalMinor;

    @Column(name = "interest_minor", nullable = false, updatable = false)
    private long interestMinor;

    @Column(name = "allocation_policy", nullable = false, updatable = false)
    private String allocationPolicy;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected Repayment() {
        // for JPA
    }

    public static Repayment record(UUID id, Obligation obligation, UUID journalEntryId, String idempotencyKey,
                                   AllocationPolicy.Result allocation, Instant now) {
        Repayment r = new Repayment();
        r.id = id;
        r.obligationId = obligation.getId();
        r.currencyCode = obligation.getCurrencyCode();
        r.journalEntryId = journalEntryId;
        r.idempotencyKey = idempotencyKey;
        r.amountMinor = allocation.totalMinor();
        r.principalMinor = allocation.principalMinor();
        r.interestMinor = allocation.interestMinor();
        r.allocationPolicy = allocation.policy();
        r.receivedAt = now;
        return r;
    }

    public UUID getId() {
        return id;
    }

    public UUID getObligationId() {
        return obligationId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public long getPrincipalMinor() {
        return principalMinor;
    }

    public long getInterestMinor() {
        return interestMinor;
    }

    public String getAllocationPolicy() {
        return allocationPolicy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
