package com.wbank.ledger.domain;

import com.wbank.platform.context.ActorType;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * An atomic, balanced financial fact.
 *
 * <p>A journal entry is the unit of financial atomicity: its postings are all applied
 * or none are. Once written it is immutable apart from reversal linkage; the database
 * enforces this with a trigger so that no ORM behaviour, migration script or manual
 * {@code UPDATE} can quietly rewrite financial history.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Database-assigned monotonic number; stable ordering for statements and audit. */
    @Column(name = "entry_number", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Long entryNumber;

    @Column(name = "currency", nullable = false, updatable = false, length = 3, columnDefinition = "bpchar")
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false)
    private JournalEntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JournalEntryStatus status;

    @Column(name = "description", nullable = false)
    private String description;

    /** The date the entry takes economic effect; may differ from when it was booked. */
    @Column(name = "value_date", nullable = false, updatable = false)
    private LocalDate valueDate;

    @Column(name = "booked_at", nullable = false, updatable = false)
    private Instant bookedAt;

    @Column(name = "total_amount_minor", nullable = false, updatable = false)
    private long totalAmountMinor;

    @Column(name = "reverses_entry_id", updatable = false)
    private UUID reversesEntryId;

    @Column(name = "reversed_by_entry_id")
    private UUID reversedByEntryId;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "initiated_by", nullable = false, updatable = false)
    private String initiatedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "initiator_type", nullable = false, updatable = false)
    private ActorType initiatorType;

    @Column(name = "source_operation", nullable = false, updatable = false)
    private String sourceOperation;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    /** SHA-256 of the request first submitted under {@link #idempotencyKey}. */
    @Column(name = "request_fingerprint", updatable = false)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected JournalEntry() {
        // for JPA
    }

    public static JournalEntry post(UUID id,
                                    JournalEntryType entryType,
                                    String description,
                                    LocalDate valueDate,
                                    Money total,
                                    UUID reversesEntryId,
                                    OperationContext context,
                                    String idempotencyKey,
                                    String requestFingerprint,
                                    Instant bookedAt) {
        if (!total.isPositive()) {
            throw new IllegalArgumentException("A journal entry total must be strictly positive: " + total);
        }
        JournalEntry entry = new JournalEntry();
        entry.id = id;
        entry.currencyCode = total.currency().code();
        entry.entryType = entryType;
        entry.status = JournalEntryStatus.POSTED;
        entry.description = description;
        entry.valueDate = valueDate;
        entry.bookedAt = bookedAt;
        entry.totalAmountMinor = total.minorUnits();
        entry.reversesEntryId = reversesEntryId;
        entry.correlationId = context.correlationId();
        entry.initiatedBy = context.actor();
        entry.initiatorType = context.actorType();
        entry.sourceOperation = context.sourceOperation();
        entry.idempotencyKey = idempotencyKey;
        entry.requestFingerprint = idempotencyKey == null ? null : requestFingerprint;
        entry.createdAt = bookedAt;
        return entry;
    }

    /**
     * Marks this entry as reversed by {@code reversalEntryId}.
     *
     * <p>This is the only mutation a journal entry ever undergoes. The original entry
     * and its postings are untouched; the correction exists as a separate, equally
     * auditable fact.
     */
    public void markReversedBy(UUID reversalEntryId) {
        if (this.status == JournalEntryStatus.REVERSED) {
            throw new IllegalStateException("Journal entry " + id + " has already been reversed");
        }
        if (this.entryType == JournalEntryType.REVERSAL) {
            throw new IllegalStateException("A reversal entry cannot itself be reversed: " + id);
        }
        this.status = JournalEntryStatus.REVERSED;
        this.reversedByEntryId = reversalEntryId;
    }

    public boolean isReversed() {
        return status == JournalEntryStatus.REVERSED;
    }

    public UUID getId() {
        return id;
    }

    public Long getEntryNumber() {
        return entryNumber;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public JournalEntryType getEntryType() {
        return entryType;
    }

    public JournalEntryStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public Instant getBookedAt() {
        return bookedAt;
    }

    public long getTotalAmountMinor() {
        return totalAmountMinor;
    }

    public UUID getReversesEntryId() {
        return reversesEntryId;
    }

    public UUID getReversedByEntryId() {
        return reversedByEntryId;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getInitiatedBy() {
        return initiatedBy;
    }

    public ActorType getInitiatorType() {
        return initiatorType;
    }

    public String getSourceOperation() {
        return sourceOperation;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
