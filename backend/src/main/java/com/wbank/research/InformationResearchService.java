package com.wbank.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.information.AffordabilityService;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.obligation.history.CreditDecisionSnapshotRepository;
import com.wbank.obligation.history.LendingEvent;
import com.wbank.obligation.history.LendingEventRepository;
import com.wbank.obligation.history.LendingEventType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Descriptive measurement of what the bank knew at its credit decisions: which information was
 * available, verified, declared or missing, and which affordability calculations were
 * determinable. Read from the decision snapshots as captured.
 *
 * <p>It also runs one deliberately INVALID experiment, clearly labelled: recomputing each
 * decision's affordability with information recorded after the decision allowed in. Its only
 * purpose is to measure how much later-known information would contaminate historical research.
 *
 * <p>No prediction, accuracy, ranking or causal claim is produced.
 */
@Service
public class InformationResearchService {

    private final LendingEventRepository events;
    private final CreditDecisionSnapshotRepository snapshots;
    private final AffordabilityService affordability;
    private final ObjectMapper json;
    private final Clock clock;

    public InformationResearchService(LendingEventRepository events, CreditDecisionSnapshotRepository snapshots,
                                      AffordabilityService affordability, ObjectMapper json, Clock clock) {
        this.events = events;
        this.snapshots = snapshots;
        this.affordability = affordability;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> report(String label, Instant decidedFrom, Instant decidedTo, Instant knownAt) {
        Instant k = knownAt == null ? clock.instant() : knownAt;
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> snapshotSchemas = new TreeMap<>();
        Map<String, Integer> income = new TreeMap<>();
        Map<String, Integer> obligations = new TreeMap<>();
        Map<String, Integer> completeness = new TreeMap<>();
        Map<String, Integer> verifiedBasis = new TreeMap<>();
        Map<String, Integer> inclusiveBasis = new TreeMap<>();
        Map<String, Integer> indeterminateReasons = new TreeMap<>();
        int notCaptured = 0;
        int leakageChanged = 0;
        int leakageEvaluated = 0;

        for (LendingEvent e : events.findByTypes(List.of(LendingEventType.CREDIT_DECISION))) {
            if (e.getEffectiveAt().isBefore(decidedFrom) || !e.getEffectiveAt().isBefore(decidedTo)
                    || e.getRecordedAt().isAfter(k)) {
                continue;
            }
            JsonNode payload = read(e.getPayload());
            UUID decisionId = UUID.fromString(payload.get("decisionId").asText());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("decisionId", decisionId.toString());
            row.put("decision", payload.path("decision").asText());
            row.put("decidedAt", e.getEffectiveAt().toString());
            var snapshot = snapshots.findByDecisionId(decisionId).orElse(null);
            JsonNode content = snapshot == null ? null : read(snapshot.getContent());
            String schema = content == null ? "NONE" : content.path("schema").asText();
            snapshotSchemas.merge(schema, 1, Integer::sum);
            row.put("snapshotSchema", schema);
            JsonNode info = content == null ? null : content.get("financialInformation");
            if (info == null) {
                notCaptured++;
                row.put("information", "NOT_CAPTURED");
                rows.add(row);
                continue;
            }
            JsonNode out = info.get("output");
            String incomeState = out.at("/income/state").asText();
            String obligationState = out.at("/obligations/state").asText();
            String complete = out.at("/completeness/status").asText();
            String vb = out.at("/bases/VERIFIED_INCOME/status").asText();
            String ib = out.at("/bases/DECLARED_INCLUSIVE/status").asText();
            income.merge(incomeState, 1, Integer::sum);
            obligations.merge(obligationState, 1, Integer::sum);
            completeness.merge(complete, 1, Integer::sum);
            verifiedBasis.merge(vb, 1, Integer::sum);
            inclusiveBasis.merge(ib, 1, Integer::sum);
            out.at("/bases/VERIFIED_INCOME/reasons").forEach(r -> indeterminateReasons.merge(r.asText(), 1, Integer::sum));
            row.put("incomeState", incomeState);
            row.put("obligationsState", obligationState);
            row.put("completeness", complete);
            row.put("verifiedBasis", vb);
            row.put("declaredInclusiveBasis", ib);
            row.put("missing", out.at("/completeness/missing"));
            row.put("affordabilityOutputHash", info.path("outputHash").asText());

            // INVALID-BY-DESIGN leakage experiment: same decision time, knowledge cut-off moved to k.
            Instant decidedAt = e.getEffectiveAt();
            JsonNode application = content.get("application");
            AffordabilityService.Computed hindsight = affordability.compute(
                    UUID.fromString(content.at("/subject/partyId").asText()),
                    new AffordabilityService.Proposal(application.path("currency").asText(),
                            application.path("principalMinor").asLong(), application.path("annualRateBps").asInt(),
                            application.path("installmentCount").asInt()),
                    decidedAt, k, e.getObligationId());
            Map<String, Object> hindsightState = state(json.valueToTree(hindsight.output()));
            Map<String, Object> actualState = state(out);
            boolean changed = !hindsightState.equals(actualState);
            leakageEvaluated++;
            leakageChanged += changed ? 1 : 0;
            if (changed) {
                row.put("stateIfLaterInformationWereAllowed_INVALID", hindsightState);
            }
            rows.add(row);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "information-completeness-report/v1");
        report.put("population", Map.of("label", label, "decidedFrom", decidedFrom.toString(),
                "decidedTo", decidedTo.toString(), "knownAt", k.toString(), "decisions", rows.size()));
        report.put("snapshotSchemas", snapshotSchemas);
        report.put("informationNotCaptured", notCaptured);
        report.put("incomeState", income);
        report.put("recurringObligationsState", obligations);
        report.put("completeness", completeness);
        report.put("affordabilityVerifiedBasis", verifiedBasis);
        report.put("affordabilityDeclaredInclusiveBasis", inclusiveBasis);
        report.put("verifiedBasisIndeterminateReasons", indeterminateReasons);
        Map<String, Object> leakage = new LinkedHashMap<>();
        leakage.put("validity", "INVALID_FOR_RESEARCH: uses information recorded after each decision");
        leakage.put("purpose", "Measures how much later-known information would change the historical information state");
        leakage.put("decisionsEvaluated", leakageEvaluated);
        leakage.put("decisionsWhoseStateWouldChange", leakageChanged);
        report.put("leakageExperiment", leakage);
        report.put("interpretation", Map.of(
                "nature", "descriptive counts of what the bank knew; not a risk measure",
                "notEstablished", List.of("predictive power", "policy superiority", "causality", "borrower ranking")));
        report.put("rows", rows);
        report.put("sha256", CanonicalJson.sha256(CanonicalJson.canonical(json, json.valueToTree(rows))));
        return report;
    }

    /** The comparable information state of an affordability output. */
    private static Map<String, Object> state(JsonNode out) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incomeState", out.at("/income/state").asText());
        m.put("verifiedMonthlyMinor", out.at("/income/verifiedMonthlyMinor").asText());
        m.put("declaredMonthlyMinor", out.at("/income/declaredMonthlyMinor").asText());
        m.put("obligationsState", out.at("/obligations/state").asText());
        m.put("externalMonthlyMinor", out.at("/obligations/externalMonthlyMinor").asText());
        m.put("completeness", out.at("/completeness/status").asText());
        m.put("verifiedBasis", out.at("/bases/VERIFIED_INCOME/status").asText());
        m.put("debtServiceRatioBps", out.at("/bases/VERIFIED_INCOME/debtServiceRatioBps").asText());
        return m;
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
