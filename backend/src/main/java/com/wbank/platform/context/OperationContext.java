package com.wbank.platform.context;

import java.util.Objects;
import java.util.UUID;

/**
 * Provenance carried with every state-changing operation.
 *
 * <p>This is the minimum a future intelligence layer needs to answer
 * "what happened, when, who caused it, and which operation produced it".
 * It is mandatory, not optional: the ledger refuses to record an
 * unattributable fact.
 *
 * @param correlationId  identifies one logical business interaction end-to-end
 * @param causationId    the event/operation that directly caused this one, if any
 * @param actor          stable identifier of the initiator (user id, service name)
 * @param actorType      classification of the initiator
 * @param sourceOperation dotted name of the operation, e.g. {@code payments.transfer}
 */
public record OperationContext(
        UUID correlationId,
        UUID causationId,
        String actor,
        ActorType actorType,
        String sourceOperation) {

    public OperationContext {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(actorType, "actorType");
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required for every financial operation");
        }
        if (sourceOperation == null || sourceOperation.isBlank()) {
            throw new IllegalArgumentException("sourceOperation is required for every financial operation");
        }
    }

    public OperationContext withOperation(String operation) {
        return new OperationContext(correlationId, causationId, actor, actorType, operation);
    }

    public OperationContext causedBy(UUID cause) {
        return new OperationContext(correlationId, cause, actor, actorType, sourceOperation);
    }
}
