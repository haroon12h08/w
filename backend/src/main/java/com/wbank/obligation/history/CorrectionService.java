package com.wbank.obligation.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.persistence.ObligationRepository;
import com.wbank.platform.audit.AuditTrail;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.NotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrects non-financial information in the history (what an applicant declared, what a
 * decider wrote) by APPENDING a correction. The original event and the decision snapshot
 * stay exactly as they were: a reconstruction "as known then" still shows the original,
 * one "as known now" also shows the correction, and the two are never merged silently.
 *
 * <p>Money is not corrected here: financial errors are corrected in the ledger by reversal
 * and in the lending domain by its own operations.
 */
@Service
public class CorrectionService {

    private static final Set<LendingEventType> CORRECTABLE = Set.of(LendingEventType.APPLICATION_RECEIVED,
            LendingEventType.CREDIT_DECISION, LendingEventType.DEFAULT_DECLARED);

    private final LendingEventRepository events;
    private final ObligationRepository obligations;
    private final LendingHistory history;
    private final AuditTrail auditTrail;
    private final ObjectMapper json;

    public CorrectionService(LendingEventRepository events, ObligationRepository obligations, LendingHistory history,
                             AuditTrail auditTrail, ObjectMapper json) {
        this.events = events;
        this.obligations = obligations;
        this.history = history;
        this.auditTrail = auditTrail;
        this.json = json;
    }

    /**
     * @param field "rationale" or "evidence.&lt;key&gt;" (supplied evidence on a decision)
     */
    @Transactional
    public LendingEvent correct(UUID eventId, String field, String correctedValue, String reason) {
        OperationContext context = RequestContext.forOperation("lending_history.correct");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A correction requires a reason");
        }
        LendingEvent target = events.findById(eventId).orElseThrow(() -> NotFoundException.of("lending_event", eventId));
        obligations.findByIdForUpdate(target.getObligationId()).orElseThrow(); // serialise with the loan's history
        if (!CORRECTABLE.contains(target.getEventType())) {
            throw new BusinessRuleViolationException("history.not_correctable",
                    target.getEventType() + " records a financial fact; correct it through its owning operation");
        }
        JsonNode payload = read(target.getPayload());
        JsonNode previous;
        if ("rationale".equals(field)) {
            previous = payload.get("rationale");
        } else if (field != null && field.startsWith("evidence.") && field.length() > 9) {
            previous = payload.path("evidence").get(field.substring(9));
        } else {
            throw new IllegalArgumentException("Correctable fields are 'rationale' and 'evidence.<key>'");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("correctsEventId", eventId.toString());
        p.put("field", field);
        p.put("previousValue", previous);
        p.put("correctedValue", correctedValue);
        p.put("reason", reason.strip());
        LendingEvent correction = history.recordCorrection(target, p, context);
        auditTrail.record("lending_history.corrected", "obligation", target.getObligationId(), context,
                Map.of("correctsEventId", eventId.toString(), "field", field));
        return correction;
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
