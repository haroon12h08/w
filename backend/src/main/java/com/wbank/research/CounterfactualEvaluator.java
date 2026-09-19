package com.wbank.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.domain.LoanDecision;
import com.wbank.obligation.history.CanonicalJson;
import com.wbank.obligation.history.CreditDecisionSnapshot;
import com.wbank.obligation.history.CreditDecisionSnapshotRepository;
import com.wbank.obligation.history.CreditPolicy;
import com.wbank.obligation.history.CreditPolicyService;
import com.wbank.obligation.history.DecisionFacts;
import com.wbank.obligation.history.DecisionSnapshots;
import com.wbank.obligation.history.HistoricalLoanState;
import com.wbank.obligation.history.LendingEvent;
import com.wbank.obligation.history.LendingEventRepository;
import com.wbank.obligation.history.PointInTimeService;
import com.wbank.obligation.history.PolicyEngine;
import com.wbank.obligation.history.PolicyRule;
import com.wbank.obligation.persistence.LoanDecisionRepository;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "What would policy P have decided at time T, given exactly what was known when the real
 * decision was made?"
 *
 * <p>The decision path reads only immutable records: the decision row, its history event
 * (for the decision time) and its snapshot, whose hash is verified before use. It never
 * reads current loan state, balances, later events or outcomes. The result is stored as an
 * immutable research record, never as a banking event, and never alters the actual decision.
 */
@Service
public class CounterfactualEvaluator {

    public static final String INPUT_SCHEMA = "counterfactual-input/v1";
    public static final String OUTPUT_SCHEMA = "counterfactual-output/v1";

    private final LoanDecisionRepository decisions;
    private final LendingEventRepository events;
    private final CreditDecisionSnapshotRepository snapshots;
    private final DecisionSnapshots snapshotIntegrity;
    private final CreditPolicyService policies;
    private final DecisionFactsSource snapshotFacts;
    private final CounterfactualDecisionRepository counterfactuals;
    private final PointInTimeService pit;
    private final ObjectMapper json;
    private final Clock clock;

    public CounterfactualEvaluator(LoanDecisionRepository decisions, LendingEventRepository events,
                                   CreditDecisionSnapshotRepository snapshots, DecisionSnapshots snapshotIntegrity,
                                   CreditPolicyService policies, SnapshotDecisionFacts snapshotFacts,
                                   CounterfactualDecisionRepository counterfactuals, PointInTimeService pit,
                                   ObjectMapper json, Clock clock) {
        this.decisions = decisions;
        this.events = events;
        this.snapshots = snapshots;
        this.snapshotIntegrity = snapshotIntegrity;
        this.policies = policies;
        this.snapshotFacts = snapshotFacts;
        this.counterfactuals = counterfactuals;
        this.pit = pit;
        this.json = json;
        this.clock = clock;
    }

    public record Reproduction(UUID counterfactualId, String storedInputHash, String recomputedInputHash,
                               String storedOutputHash, String recomputedOutputHash, boolean identical) {}

    /** Evaluates one historical decision under {@code policyCode} v{@code version} and records the experiment. */
    @Transactional
    public CounterfactualDecision evaluate(UUID decisionId, String policyCode, int version) {
        return counterfactuals.save(prepare(source(decisionId), policies.require(policyCode, version), snapshotFacts,
                null));
    }

    /** The historical decision, from immutable records only. Refuses a snapshot that fails its hash. */
    @Transactional(readOnly = true)
    public SourceDecision source(UUID decisionId) {
        LoanDecision d = decisions.findById(decisionId).orElseThrow(() -> NotFoundException.of("loan_decision", decisionId));
        if (d.getDecision() == LoanDecision.Kind.DEFAULT_DECLARED) {
            throw new BusinessRuleViolationException("research.not_a_credit_decision",
                    "Only approve/decline decisions can be replayed under a credit policy");
        }
        LendingEvent event = events.findBySourceTableAndSourceId("loan_decision", decisionId)
                .orElseThrow(() -> new IllegalStateException("Decision " + decisionId + " has no history event"));
        CreditDecisionSnapshot snapshot = snapshots.findByDecisionId(decisionId).orElse(null);
        JsonNode content = null;
        if (snapshot != null) {
            if (!snapshotIntegrity.verify(snapshot)) {
                throw new BusinessRuleViolationException("research.snapshot_integrity",
                        "Snapshot " + snapshot.getId() + " no longer matches the hash taken at decision time; refusing to "
                                + "evaluate a decision context that may have been altered");
            }
            content = read(snapshot.getContent());
        }
        return new SourceDecision(decisionId, d.getObligationId(), d.getDecision().name(), event.getEffectiveAt(),
                event.getLoanSeq(), d.getPolicyCode(), d.getPolicyVersion(),
                snapshot == null ? null : snapshot.getId(), snapshot == null ? null : snapshot.getContentSha256(),
                content);
    }

    /**
     * Computes (without saving) the counterfactual for {@code src} under {@code alternative}, taking
     * decision-time facts from {@code factsSource}. Public so that tests can substitute sources.
     */
    public CounterfactualDecision prepare(SourceDecision src, CreditPolicy alternative, DecisionFactsSource factsSource,
                                          UUID replayId) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("decisionId", src.decisionId().toString());
        source.put("snapshotId", src.snapshotId() == null ? null : src.snapshotId().toString());
        source.put("snapshotSha256", src.snapshotSha256());
        source.put("decidedAt", src.decidedAt().toString());
        source.put("actualDecision", src.actualDecision());
        source.put("actualPolicyCode", src.actualPolicyCode());
        source.put("actualPolicyVersion", src.actualPolicyVersion());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schema", INPUT_SCHEMA);
        input.put("source", source);
        String policyResult;
        String control = null;
        List<Map<String, Object>> ruleResults = List.of();
        if (src.snapshot() == null) {
            input.put("facts", null);
            input.put("policy", policyInput(alternative, List.of()));
            policyResult = "NOT_EVALUABLE";
        } else {
            DecisionFacts facts = factsSource.facts(src);
            List<PolicyRule> rules = policies.rules(alternative).approvalRules(facts.currencyScale());
            input.put("facts", facts);
            input.put("policy", policyInput(alternative, rules));
            PolicyEngine.Evaluation eval = PolicyEngine.evaluate(rules, facts);
            policyResult = eval.result().name();
            ruleResults = eval.results().stream().map(PolicyEngine.RuleResult::asMap).toList();
            if (src.actualPolicyCode() != null) {
                CreditPolicy actual = policies.require(src.actualPolicyCode(), src.actualPolicyVersion());
                control = PolicyEngine.evaluate(policies.rules(actual).approvalRules(facts.currencyScale()), facts)
                        .result().name();
            }
        }
        String inputJson = CanonicalJson.canonical(json, json.valueToTree(input));
        String inputHash = CanonicalJson.sha256(inputJson);
        String hypothetical = hypothetical(policyResult);
        String outputHash = outputHash(inputHash, policyResult, hypothetical, ruleResults);
        Instant now = clock.instant();
        return CounterfactualDecision.of(replayId, src, alternative.getPolicyCode(), alternative.getVersion(), now,
                RequestContext.forOperation("research.counterfactual").actor(), inputJson, inputHash,
                CanonicalJson.canonical(json, json.valueToTree(ruleResults)), policyResult, control, hypothetical,
                outputHash, contextCheck(src));
    }

    /** Recomputes a stored counterfactual from its recorded input alone, and compares hashes. */
    @Transactional(readOnly = true)
    public Reproduction reproduce(UUID counterfactualId) {
        CounterfactualDecision c = counterfactuals.findById(counterfactualId)
                .orElseThrow(() -> NotFoundException.of("counterfactual", counterfactualId));
        JsonNode input = read(c.getInput());
        String recomputedInputHash = CanonicalJson.sha256(CanonicalJson.canonical(json, input));
        String policyResult = "NOT_EVALUABLE";
        List<Map<String, Object>> ruleResults = List.of();
        if (!input.get("facts").isNull()) {
            DecisionFacts facts = treeToValue(input.get("facts"));
            List<PolicyRule> rules = new ArrayList<>();
            for (JsonNode r : input.at("/policy/rules")) {
                rules.add(new PolicyRule(r.get("id").asText(), PolicyRule.Input.valueOf(r.get("input").asText()),
                        PolicyRule.Operator.valueOf(r.get("operator").asText()), scalar(r.get("threshold"))));
            }
            PolicyEngine.Evaluation eval = PolicyEngine.evaluate(rules, facts);
            policyResult = eval.result().name();
            ruleResults = eval.results().stream().map(PolicyEngine.RuleResult::asMap).toList();
        }
        String recomputedOutput = outputHash(recomputedInputHash, policyResult, hypothetical(policyResult), ruleResults);
        return new Reproduction(counterfactualId, c.getInputHash(), recomputedInputHash, c.getOutputHash(),
                recomputedOutput, c.getInputHash().equals(recomputedInputHash) && c.getOutputHash().equals(recomputedOutput));
    }

    @Transactional(readOnly = true)
    public ActualDecision actual(UUID decisionId) {
        SourceDecision src = source(decisionId);
        LoanDecision d = decisions.findById(decisionId).orElseThrow();
        return new ActualDecision(decisionId, src.obligationId(), src.actualDecision(), src.decidedAt(),
                d.getDecidedBy(), src.actualPolicyCode(), src.actualPolicyVersion(), src.snapshotId(),
                src.snapshotSha256(), src.snapshot() == null ? null : src.snapshot().get("ruleEvaluation"));
    }

    // ---------------------------------------------------------------- internals

    static String hypothetical(String policyResult) {
        return switch (policyResult) {
            case "PASS" -> "APPROVED";
            case "FAIL" -> "DECLINED";
            case "INDETERMINATE" -> "UNDETERMINED";
            default -> "NOT_EVALUABLE";
        };
    }

    private String outputHash(String inputHash, String policyResult, String hypothetical,
                              List<Map<String, Object>> ruleResults) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema", OUTPUT_SCHEMA);
        output.put("inputHash", inputHash);
        output.put("policyResult", policyResult);
        output.put("hypotheticalDecision", hypothetical);
        output.put("ruleResults", ruleResults);
        return CanonicalJson.sha256(CanonicalJson.canonical(json, json.valueToTree(output)));
    }

    private static Map<String, Object> policyInput(CreditPolicy p, List<PolicyRule> rules) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.getPolicyCode());
        m.put("version", p.getVersion());
        m.put("rules", rules.stream().map(r -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", r.id());
            x.put("input", r.input().name());
            x.put("operator", r.operator().name());
            x.put("threshold", r.threshold());
            x.put("onFail", PolicyRule.ON_FAIL);
            return x;
        }).toList());
        return m;
    }

    /**
     * Independent check (not an input): does the snapshot's view of the borrower's other
     * obligations agree with the history reconstructed as known at decision time? Uses the
     * point-in-time fold, so it also guards the fold's knowledge cut-off.
     */
    private String contextCheck(SourceDecision src) {
        if (src.snapshot() == null) {
            return "NOT_CHECKED";
        }
        UUID party = UUID.fromString(src.snapshot().at("/subject/partyId").asText());
        Map<String, List<Object>> history = new LinkedHashMap<>();
        for (HistoricalLoanState s : pit.borrowerAt(party, src.decidedAt(), src.decidedAt())) {
            if (!s.obligationId().equals(src.obligationId())) {
                history.put(s.obligationId().toString(), List.of(s.status(), s.outstandingPrincipalMinor(),
                        s.derivedDelinquency() == null ? 0L : s.derivedDelinquency().daysPastDue()));
            }
        }
        Map<String, List<Object>> snapshot = new LinkedHashMap<>();
        for (JsonNode o : src.snapshot().path("existingObligations")) {
            snapshot.put(o.get("obligationId").asText(), List.of(o.get("status").asText(),
                    o.get("outstandingPrincipalMinor").asLong(), o.get("daysPastDue").asLong()));
        }
        return Objects.equals(history, snapshot) ? "CONSISTENT" : "INCONSISTENT";
    }

    private static Object scalar(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isNumber()) {
            return n.asLong();
        }
        return n.asText();
    }

    private DecisionFacts treeToValue(JsonNode n) {
        try {
            return json.treeToValue(n, DecisionFacts.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
