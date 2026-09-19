package com.wbank.payment.domain;

import com.wbank.platform.context.ActorType;
import com.wbank.platform.context.OperationContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One step in a payment's history: which check ran with what result, which decision was
 * taken by whom, what the ledger recorded. Append-only; ordered per payment.
 */
@Entity
@Immutable
@Table(name = "payment_event")
public class PaymentEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", updatable = false)
    private PaymentStatus toStatus;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false)
    private ActorType actorType;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String details;

    protected PaymentEvent() {
        // for JPA
    }

    public static PaymentEvent of(UUID paymentId, int sequenceNo, String eventType, PaymentStatus from,
                                  PaymentStatus to, OperationContext context, String detailsJson, Instant now) {
        PaymentEvent e = new PaymentEvent();
        e.id = UUID.randomUUID();
        e.paymentId = paymentId;
        e.sequenceNo = sequenceNo;
        e.eventType = eventType;
        e.fromStatus = from;
        e.toStatus = to;
        e.occurredAt = now;
        e.actor = context.actor();
        e.actorType = context.actorType();
        e.correlationId = context.correlationId();
        e.details = detailsJson;
        return e;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getEventType() {
        return eventType;
    }

    public PaymentStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getDetails() {
        return details;
    }
}
