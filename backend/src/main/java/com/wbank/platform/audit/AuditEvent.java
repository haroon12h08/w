package com.wbank.platform.audit;

import com.wbank.platform.context.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * An immutable record that something financially meaningful happened.
 *
 * <p>This is <em>not</em> event sourcing: the ledger tables, not this log, are the
 * source of truth, and nothing is rebuilt by replaying these rows. It is a provenance
 * log, written in the same transaction as the change it describes, shaped like an
 * outbox so a message bus can later consume it without the financial core changing.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Database-assigned monotonic ordering; the natural cursor for a future outbox drain. */
    @Column(name = "sequence_no", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Long sequenceNo;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false)
    private ActorType actorType;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "causation_id", updatable = false)
    private UUID causationId;

    @Column(name = "source_operation", nullable = false, updatable = false)
    private String sourceOperation;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private short schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    protected AuditEvent() {
        // for JPA
    }

    AuditEvent(UUID id, String eventType, String aggregateType, UUID aggregateId, Instant occurredAt,
               Instant recordedAt, String actor, ActorType actorType, UUID correlationId, UUID causationId,
               String sourceOperation, String payload) {
        this.id = id;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.actor = actor;
        this.actorType = actorType;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.sourceOperation = sourceOperation;
        this.schemaVersion = 1;
        this.payload = payload;
    }

    public UUID getId() {
        return id;
    }

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
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

    public UUID getCausationId() {
        return causationId;
    }

    public String getSourceOperation() {
        return sourceOperation;
    }

    public short getSchemaVersion() {
        return schemaVersion;
    }

    public String getPayload() {
        return payload;
    }
}
