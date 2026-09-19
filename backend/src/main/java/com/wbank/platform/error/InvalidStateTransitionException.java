package com.wbank.platform.error;


/**
 * A lifecycle transition that the domain does not permit (e.g. CLOSED -> ACTIVE).
 * Maps to HTTP 409: the request is valid in form but conflicts with current state.
 */
public class InvalidStateTransitionException extends ConflictException {

    public InvalidStateTransitionException(String entity, Object id, Enum<?> from, Enum<?> to) {
        super(entity + ".invalid_transition",
                "%s %s cannot move from %s to %s".formatted(entity, id, from, to));
    }

    /** Throws unless {@code allowed}. Keeps every lifecycle check a one-liner at the call site. */
    public static void require(boolean allowed, String entity, Object id, Enum<?> from, Enum<?> to) {
        if (!allowed) {
            throw new InvalidStateTransitionException(entity, id, from, to);
        }
    }
}
