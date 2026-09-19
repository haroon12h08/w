package com.wbank.platform.context;

import java.util.UUID;

/**
 * Thread-bound holder for the provenance of the request currently being served.
 *
 * <p>A {@code ThreadLocal} is adequate while all financial work happens on the
 * request thread, which is true by design in this phase: money never moves on a
 * background thread. If asynchronous execution is introduced, propagation becomes
 * an explicit decision made here rather than a hidden accident.
 */
public final class RequestContext {

    public record Attributes(UUID correlationId, String actor, ActorType actorType) {}

    private static final ThreadLocal<Attributes> CURRENT = new ThreadLocal<>();

    private RequestContext() {}

    static void set(Attributes attributes) {
        CURRENT.set(attributes);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** Builds the context for an operation, falling back to a system identity off-request. */
    public static OperationContext forOperation(String sourceOperation) {
        Attributes attributes = CURRENT.get();
        if (attributes == null) {
            return new OperationContext(
                    UUID.randomUUID(), null, "system", ActorType.SYSTEM, sourceOperation);
        }
        return new OperationContext(
                attributes.correlationId(), null, attributes.actor(), attributes.actorType(), sourceOperation);
    }
}
