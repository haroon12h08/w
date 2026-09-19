package com.wbank.obligation.history;

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
 * One immutable entry in a loan's history.
 *
 * <p>Two times, deliberately:
 * <ul>
 *   <li>{@code effectiveAt}: when the event is true in the business world (the decision
 *       time of a decision; the date a delinquency observation refers to; the period end
 *       an accrual belongs to).</li>
 *   <li>{@code recordedAt}: when the bank knew it. Always assigned by the system, never by
 *       a caller, and never earlier than {@code effectiveAt}.</li>
 * </ul>
 * {@code loanSeq} gives a gap-free, deterministic order per loan.
 */
// Not @Immutable: Hibernate cannot refresh the @Generated global_seq on an immutable entry.
// Every column is updatable = false, there are no setters, and PostgreSQL rejects UPDATE/DELETE.
@Entity
@Table(name = "lending_event")
public class LendingEvent {

    public static final String ORIGIN_LIVE = "LIVE";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Generated(event = EventType.INSERT)
    @Column(name = "global_seq", insertable = false, updatable = false)
    private Long globalSeq;

    @Column(name = "obligation_id", nullable = false, updatable = false)
    private UUID obligationId;

    @Column(name = "loan_seq", nullable = false, updatable = false)
    private int loanSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private LendingEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_kind", nullable = false, updatable = false)
    private LendingEventType.Kind eventKind;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "source_table", updatable = false)
    private String sourceTable;

    @Column(name = "source_id", updatable = false)
    private UUID sourceId;

    @Column(name = "corrects_event_id", updatable = false)
    private UUID correctsEventId;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false)
    private ActorType actorType;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "origin", nullable = false, updatable = false)
    private String origin;

    protected LendingEvent() {
        // for JPA
    }

    static LendingEvent create(UUID obligationId, int loanSeq, LendingEventType type, Instant effectiveAt,
                               Instant recordedAt, String payloadJson, String sourceTable, UUID sourceId,
                               UUID correctsEventId, String actor, ActorType actorType, UUID correlationId) {
        if (effectiveAt.isAfter(recordedAt)) {
            throw new IllegalArgumentException("An event cannot take effect after it is recorded: " + type);
        }
        LendingEvent e = new LendingEvent();
        e.id = UUID.randomUUID();
        e.obligationId = obligationId;
        e.loanSeq = loanSeq;
        e.eventType = type;
        e.eventKind = type.kind();
        e.effectiveAt = effectiveAt;
        e.recordedAt = recordedAt;
        e.payload = payloadJson;
        e.sourceTable = sourceTable;
        e.sourceId = sourceId;
        e.correctsEventId = correctsEventId;
        e.actor = actor;
        e.actorType = actorType;
        e.correlationId = correlationId;
        e.origin = ORIGIN_LIVE;
        return e;
    }

    public UUID getId() {
        return id;
    }

    public Long getGlobalSeq() {
        return globalSeq;
    }

    public UUID getObligationId() {
        return obligationId;
    }

    public int getLoanSeq() {
        return loanSeq;
    }

    public LendingEventType getEventType() {
        return eventType;
    }

    public LendingEventType.Kind getEventKind() {
        return eventKind;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getPayload() {
        return payload;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getCorrectsEventId() {
        return correctsEventId;
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

    public String getOrigin() {
        return origin;
    }
}
