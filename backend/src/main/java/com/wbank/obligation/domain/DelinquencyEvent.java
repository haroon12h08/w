package com.wbank.obligation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A change in a loan's delinquency bucket, as observed on {@code asOf}. Append-only history. */
@Entity
@Immutable
@Table(name = "delinquency_event")
public class DelinquencyEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "obligation_id", nullable = false, updatable = false)
    private UUID obligationId;

    @Column(name = "as_of", nullable = false, updatable = false)
    private LocalDate asOf;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_bucket", nullable = false, updatable = false)
    private Delinquency.Bucket fromBucket;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_bucket", nullable = false, updatable = false)
    private Delinquency.Bucket toBucket;

    @Column(name = "days_past_due", nullable = false, updatable = false)
    private int daysPastDue;

    @Column(name = "overdue_principal_minor", nullable = false, updatable = false)
    private long overduePrincipalMinor;

    @Column(name = "overdue_interest_minor", nullable = false, updatable = false)
    private long overdueInterestMinor;

    @Column(name = "oldest_unpaid_due_date", updatable = false)
    private LocalDate oldestUnpaidDueDate;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected DelinquencyEvent() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public static DelinquencyEvent of(UUID obligationId, Delinquency.Bucket from, Delinquency.Status s, Instant now) {
        DelinquencyEvent e = new DelinquencyEvent();
        e.id = UUID.randomUUID();
        e.obligationId = obligationId;
        e.asOf = s.asOf();
        e.fromBucket = from;
        e.toBucket = s.bucket();
        e.daysPastDue = (int) s.daysPastDue();
        e.overduePrincipalMinor = s.overduePrincipalMinor();
        e.overdueInterestMinor = s.overdueInterestMinor();
        e.oldestUnpaidDueDate = s.oldestUnpaidDueDate();
        e.recordedAt = now;
        return e;
    }

    public LocalDate getAsOf() {
        return asOf;
    }

    public Delinquency.Bucket getFromBucket() {
        return fromBucket;
    }

    public Delinquency.Bucket getToBucket() {
        return toBucket;
    }

    public int getDaysPastDue() {
        return daysPastDue;
    }

    public long getOverduePrincipalMinor() {
        return overduePrincipalMinor;
    }

    public long getOverdueInterestMinor() {
        return overdueInterestMinor;
    }
}
