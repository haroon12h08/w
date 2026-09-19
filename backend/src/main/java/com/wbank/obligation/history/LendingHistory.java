package com.wbank.obligation.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.platform.context.OperationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends to a loan's history, inside the caller's transaction, so the history and the
 * change it describes commit together or not at all. Callers must hold the obligation's
 * row lock (every {@code LoanService} operation does), which makes the per-loan sequence
 * number race-free. PostgreSQL rejects any lending fact committed without its event.
 */
@Component
public class LendingHistory {

    private final LendingEventRepository events;
    private final ObjectMapper json;
    private final Clock clock;

    public LendingHistory(LendingEventRepository events, ObjectMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /** A calendar date as an instant: the start of that day, UTC (the model's day granularity). */
    public static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LendingEvent record(UUID obligationId, LendingEventType type, Instant effectiveAt, Map<String, ?> payload,
                               String sourceTable, UUID sourceId, OperationContext context) {
        return append(obligationId, type, effectiveAt, payload, sourceTable, sourceId, null, context);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LendingEvent recordCorrection(LendingEvent target, Map<String, ?> payload, OperationContext context) {
        // A correction is about the target's business time, but is only KNOWN from now on.
        return append(target.getObligationId(), LendingEventType.EVENT_CORRECTED, target.getEffectiveAt(), payload,
                null, null, target.getId(), context);
    }

    private LendingEvent append(UUID obligationId, LendingEventType type, Instant effectiveAt, Map<String, ?> payload,
                                String sourceTable, UUID sourceId, UUID correctsEventId, OperationContext context) {
        Instant now = clock.instant();
        int seq = events.maxLoanSeq(obligationId) + 1;
        try {
            return events.save(LendingEvent.create(obligationId, seq, type, effectiveAt, now,
                    json.writeValueAsString(payload), sourceTable, sourceId, correctsEventId, context.actor(),
                    context.actorType(), context.correlationId()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Lending event payload could not be serialised", e);
        }
    }
}
