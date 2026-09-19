package com.wbank.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * The fact that a payment settled: the obligation between debtor and creditor was
 * discharged by moving value, through exactly one journal entry. At most one per payment
 * (unique constraint), which is the database's "exactly once".
 */
@Entity
@Immutable
@Table(name = "payment_settlement")
public class PaymentSettlement {

    public static final String INTERNAL_BOOK_TRANSFER = "INTERNAL_BOOK_TRANSFER";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    @Column(name = "method", nullable = false, updatable = false)
    private String method;

    @Column(name = "settled_at", nullable = false, updatable = false)
    private Instant settledAt;

    protected PaymentSettlement() {
        // for JPA
    }

    public static PaymentSettlement internal(UUID paymentId, UUID journalEntryId, Instant now) {
        PaymentSettlement s = new PaymentSettlement();
        s.id = UUID.randomUUID();
        s.paymentId = paymentId;
        s.journalEntryId = journalEntryId;
        s.method = INTERNAL_BOOK_TRANSFER;
        s.settledAt = now;
        return s;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public String getMethod() {
        return method;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}
