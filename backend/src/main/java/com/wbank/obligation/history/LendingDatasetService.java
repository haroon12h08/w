package com.wbank.obligation.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A reproducible, leakage-safe dataset of credit decisions and their outcomes.
 *
 * <p>One row per credit decision (approve/decline). The "context" of each row is the
 * decision snapshot, exactly as captured at decision time; no features are engineered here.
 * The "outcome" is evaluated as known at {@code decidedAt + horizon} (never later than the
 * dataset's own {@code knownAt}); rows whose horizon has not elapsed are marked censored.
 * Declined applications have no observable outcome: the counterfactual is recorded as unknown,
 * not guessed.
 */
@Service
public class LendingDatasetService {

    private final LendingEventRepository events;
    private final CreditDecisionSnapshotRepository snapshots;
    private final PointInTimeService pit;
    private final ObjectMapper json;

    public LendingDatasetService(LendingEventRepository events, CreditDecisionSnapshotRepository snapshots,
                                 PointInTimeService pit, ObjectMapper json) {
        this.events = events;
        this.snapshots = snapshots;
        this.pit = pit;
        this.json = json;
    }

    public record Row(String decisionId, String obligationNumber, String decision, Instant decidedAt,
                      String policyCode, Integer policyVersion, String snapshotSha256, JsonNode context,
                      Instant outcomeKnownAt, boolean censored, DecisionContext.Outcome outcome) {}

    @Transactional(readOnly = true)
    public List<Row> creditDecisions(Duration horizon, Instant knownAt) {
        List<Row> rows = new ArrayList<>();
        for (LendingEvent e : events.findByTypes(List.of(LendingEventType.CREDIT_DECISION))) {
            if (e.getRecordedAt().isAfter(knownAt)) {
                continue; // the dataset itself is point-in-time
            }
            JsonNode p = read(e.getPayload());
            Instant decided = e.getEffectiveAt();
            Instant horizonEnd = decided.plus(horizon);
            Instant outcomeAt = horizonEnd.isAfter(knownAt) ? knownAt : horizonEnd;
            List<LoanHistoryFold.Event> history = pit.history(e.getObligationId());
            String obligationNumber = LoanHistoryFold.fold(e.getObligationId(), history, decided, decided)
                    .obligationNumber();
            JsonNode context = snapshots.findByDecisionId(java.util.UUID.fromString(p.get("decisionId").asText()))
                    .map(s -> read(s.getContent())).orElse(null);
            boolean approved = "APPROVED".equals(p.path("decision").asText());
            rows.add(new Row(p.get("decisionId").asText(), obligationNumber, p.path("decision").asText(), decided,
                    p.path("policyCode").isNull() ? null : p.path("policyCode").asText(null),
                    p.hasNonNull("policyVersion") ? p.get("policyVersion").asInt() : null,
                    p.path("snapshotSha256").asText(null), context, outcomeAt, horizonEnd.isAfter(knownAt),
                    approved ? DecisionReconstructionService.outcome(e.getObligationId(), history, e.getLoanSeq(),
                            decided, outcomeAt) : null));
        }
        return rows;
    }

    /** The dataset as canonical JSON text: identical input, identical bytes. */
    public String canonical(List<Row> rows, Duration horizon, Instant knownAt) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", "credit-decision-dataset/v1");
        doc.put("horizon", horizon.toString());
        doc.put("knownAt", knownAt.toString());
        doc.put("rows", rows);
        return CanonicalJson.canonical(json, json.valueToTree(doc));
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
