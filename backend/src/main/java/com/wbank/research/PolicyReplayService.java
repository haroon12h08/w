package com.wbank.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.obligation.history.CreditPolicy;
import com.wbank.obligation.history.CreditPolicyService;
import com.wbank.obligation.history.LendingEvent;
import com.wbank.obligation.history.LendingEventRepository;
import com.wbank.obligation.history.LendingEventType;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replays a labelled population of historical credit decisions under an alternative policy.
 * Produces, per decision, the actual decision next to the counterfactual one; never an
 * assessment of which is better (see {@link ReplayOutcomeEvaluator} for the separate,
 * carefully limited outcome comparison).
 */
@Service
public class PolicyReplayService {

    private final LendingEventRepository events;
    private final CreditPolicyService policies;
    private final CounterfactualEvaluator evaluator;
    private final SnapshotDecisionFacts snapshotFacts;
    private final PolicyReplayRepository replays;
    private final CounterfactualDecisionRepository counterfactuals;
    private final ObjectMapper json;
    private final Clock clock;

    public PolicyReplayService(LendingEventRepository events, CreditPolicyService policies,
                               CounterfactualEvaluator evaluator, SnapshotDecisionFacts snapshotFacts,
                               PolicyReplayRepository replays, CounterfactualDecisionRepository counterfactuals,
                               ObjectMapper json, Clock clock) {
        this.events = events;
        this.policies = policies;
        this.evaluator = evaluator;
        this.snapshotFacts = snapshotFacts;
        this.replays = replays;
        this.counterfactuals = counterfactuals;
        this.json = json;
        this.clock = clock;
    }

    /** Which historical decisions are replayed, and a human-readable name for that population. */
    public record Population(String label, Instant decidedFrom, Instant decidedTo) {}

    /**
     * @param differsFromActualDecision       counterfactual decision != what the bank actually decided
     * @param differsFromActualPolicyVerdict  alternative policy verdict != actual policy's verdict on the
     *                                        same facts (isolates the policy change from human discretion)
     */
    public record Row(ActualDecision actual, CounterfactualDecision counterfactual, boolean differsFromActualDecision,
                      boolean differsFromActualPolicyVerdict) {}

    public record ReplayView(PolicyReplay replay, JsonNode population, List<Row> rows) {}

    @Transactional
    public ReplayView replay(Population population, String policyCode, int version) {
        CreditPolicy alternative = policies.require(policyCode, version);
        Instant now = clock.instant();
        List<LendingEvent> decisionsInScope = events.findByTypes(List.of(LendingEventType.CREDIT_DECISION)).stream()
                .filter(e -> !e.getEffectiveAt().isBefore(population.decidedFrom())
                        && e.getEffectiveAt().isBefore(population.decidedTo())
                        && !e.getRecordedAt().isAfter(now))
                .toList();
        UUID replayId = UUID.randomUUID();
        List<CounterfactualDecision> computed = new ArrayList<>();
        List<String> decisionIds = new ArrayList<>();
        for (LendingEvent e : decisionsInScope) {
            UUID decisionId = UUID.fromString(read(e.getPayload()).get("decisionId").asText());
            decisionIds.add(decisionId.toString());
            computed.add(evaluator.prepare(evaluator.source(decisionId), alternative, snapshotFacts, replayId));
        }

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("label", population.label());
        definition.put("decisionKinds", List.of("APPROVED", "DECLINED"));
        definition.put("decidedFrom", population.decidedFrom().toString());
        definition.put("decidedTo", population.decidedTo().toString());
        definition.put("decisionIds", decisionIds);
        Map<String, Object> inputDoc = new LinkedHashMap<>();
        inputDoc.put("policy", Map.of("code", policyCode, "version", version));
        inputDoc.put("population", definition);
        inputDoc.put("inputHashes", computed.stream().map(CounterfactualDecision::getInputHash).toList());
        String inputHash = CanonicalJson.sha256(CanonicalJson.canonical(json, json.valueToTree(inputDoc)));
        String outputHash = CanonicalJson.sha256(CanonicalJson.canonical(json, json.valueToTree(
                computed.stream().map(CounterfactualDecision::getOutputHash).toList())));

        Map<String, Object> populationDoc = new LinkedHashMap<>(definition);
        populationDoc.put("selectedAt", now.toString());
        PolicyReplay replay = replays.saveAndFlush(PolicyReplay.of(replayId, policyCode, version,
                CanonicalJson.canonical(json, json.valueToTree(populationDoc)), computed.size(), now,
                RequestContext.forOperation("research.policy_replay").actor(), inputHash, outputHash));
        counterfactuals.saveAll(computed);
        return view(replay, computed);
    }

    @Transactional(readOnly = true)
    public ReplayView get(UUID replayId) {
        PolicyReplay replay = replays.findById(replayId).orElseThrow(() -> NotFoundException.of("policy_replay", replayId));
        return view(replay, counterfactuals.findByReplayIdOrderByOriginalDecidedAtAscSourceDecisionIdAsc(replayId));
    }

    private ReplayView view(PolicyReplay replay, List<CounterfactualDecision> rows) {
        List<Row> out = new ArrayList<>();
        for (CounterfactualDecision c : rows.stream()
                .sorted((a, b) -> a.getOriginalDecidedAt().equals(b.getOriginalDecidedAt())
                        ? a.getSourceDecisionId().compareTo(b.getSourceDecisionId())
                        : a.getOriginalDecidedAt().compareTo(b.getOriginalDecidedAt())).toList()) {
            ActualDecision actual = evaluator.actual(c.getSourceDecisionId());
            boolean evaluable = !"NOT_EVALUABLE".equals(c.getPolicyResult());
            out.add(new Row(actual, c, evaluable && !c.getHypotheticalDecision().equals(actual.decision()),
                    evaluable && c.getControlPolicyResult() != null
                            && !c.getControlPolicyResult().equals(c.getPolicyResult())));
        }
        return new ReplayView(replay, read(replay.getPopulation()), out);
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
