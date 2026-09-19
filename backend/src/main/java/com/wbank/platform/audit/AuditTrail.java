package com.wbank.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.platform.context.OperationContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes provenance records.
 *
 * <p>Deliberately joins the caller's transaction ({@code MANDATORY}). An audit record
 * that survives a rolled-back financial change would be a lie, and a financial change
 * without an audit record would be unexplainable. They commit together or not at all.
 */
@Component
public class AuditTrail {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditTrail(AuditEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEvent record(String eventType,
                             String aggregateType,
                             UUID aggregateId,
                             OperationContext context,
                             Map<String, ?> payload) {

        Instant now = clock.instant();
        AuditEvent event = new AuditEvent(
                UUID.randomUUID(),
                eventType,
                aggregateType,
                aggregateId,
                now,
                now,
                context.actor(),
                context.actorType(),
                context.correlationId(),
                context.causationId(),
                context.sourceOperation(),
                serialise(payload));
        return repository.save(event);
    }

    private String serialise(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            // Failing the financial transaction is the correct response: we will not
            // book money we cannot describe.
            throw new IllegalStateException("Audit payload could not be serialised", e);
        }
    }
}
