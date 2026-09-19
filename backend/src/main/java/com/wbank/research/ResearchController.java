package com.wbank.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.history.CreditPolicy;
import com.wbank.obligation.history.CreditPolicyRules;
import com.wbank.obligation.history.CreditPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Counterfactual research endpoints. Everything here READS the banking record and WRITES
 * only research records; no endpoint can alter a decision, an outcome or a policy version.
 */
@RestController
@RequestMapping("/api/v1/research")
public class ResearchController {

    private final CounterfactualEvaluator evaluator;
    private final PolicyReplayService replays;
    private final ReplayOutcomeEvaluator outcomes;
    private final CreditPolicyService policies;
    private final ObjectMapper json;

    public ResearchController(CounterfactualEvaluator evaluator, PolicyReplayService replays,
                              ReplayOutcomeEvaluator outcomes, CreditPolicyService policies, ObjectMapper json) {
        this.evaluator = evaluator;
        this.replays = replays;
        this.outcomes = outcomes;
        this.policies = policies;
        this.json = json;
    }

    public record CounterfactualRequest(@NotNull UUID decisionId, @NotBlank String policyCode, @NotNull Integer policyVersion) {}

    public record ReplayRequest(@NotBlank String label, @NotNull Instant decidedFrom, @NotNull Instant decidedTo,
                                @NotBlank String policyCode, @NotNull Integer policyVersion) {}

    public record OutcomeRequest(Duration horizon, Instant outcomeKnownAt) {}

    public record ResearchPolicyRequest(@NotBlank String policyCode, @Valid @NotNull CreditPolicyRules rules,
                                        @NotBlank String description) {}

    public record CounterfactualView(UUID id, UUID replayId, ActualDecision actual, JsonNode counterfactual) {}

    @PostMapping("/policies")
    public ResponseEntity<JsonNode> publishResearchPolicy(@Valid @RequestBody ResearchPolicyRequest req) {
        if (CreditPolicy.LENDING.equals(req.policyCode())) {
            throw new IllegalArgumentException("Research policies must not use the live policy code");
        }
        CreditPolicy p = policies.publish(req.policyCode(), req.rules(), null, req.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(json.valueToTree(java.util.Map.of(
                "policyCode", p.getPolicyCode(), "version", p.getVersion(), "rules", read(p.getRules()))));
    }

    @PostMapping("/counterfactuals")
    public ResponseEntity<CounterfactualView> counterfactual(@Valid @RequestBody CounterfactualRequest req) {
        CounterfactualDecision c = evaluator.evaluate(req.decisionId(), req.policyCode(), req.policyVersion());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(c));
    }

    @GetMapping("/counterfactuals/{id}/reproduction")
    public CounterfactualEvaluator.Reproduction reproduce(@PathVariable UUID id) {
        return evaluator.reproduce(id);
    }

    @PostMapping("/policy-replays")
    public ResponseEntity<JsonNode> replay(@Valid @RequestBody ReplayRequest req) {
        var v = replays.replay(new PolicyReplayService.Population(req.label(), req.decidedFrom(), req.decidedTo()),
                req.policyCode(), req.policyVersion());
        return ResponseEntity.status(HttpStatus.CREATED).body(render(v));
    }

    @GetMapping("/policy-replays/{id}")
    public JsonNode replay(@PathVariable UUID id) {
        return render(replays.get(id));
    }

    @PostMapping("/policy-replays/{id}/outcome-evaluations")
    public ResponseEntity<JsonNode> evaluateOutcomes(@PathVariable UUID id, @RequestBody OutcomeRequest req) {
        OutcomeEvaluation e = outcomes.evaluate(id, req.horizon() == null ? Duration.ofDays(180) : req.horizon(),
                req.outcomeKnownAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(json.valueToTree(java.util.Map.of(
                "id", e.getId(), "reportSha256", e.getReportSha256(), "report", read(e.getReport()))));
    }

    private CounterfactualView view(CounterfactualDecision c) {
        return new CounterfactualView(c.getId(), c.getReplayId(), evaluator.actual(c.getSourceDecisionId()),
                counterfactualJson(c));
    }

    private JsonNode counterfactualJson(CounterfactualDecision c) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("kind", "COUNTERFACTUAL (research record, not a banking decision)");
        m.put("sourceDecisionId", c.getSourceDecisionId());
        m.put("sourceSnapshotId", c.getSourceSnapshotId());
        m.put("alternativePolicy", c.getAlternativePolicyCode() + " v" + c.getAlternativePolicyVersion());
        m.put("originalDecidedAt", c.getOriginalDecidedAt());
        m.put("evaluatedAt", c.getEvaluatedAt());
        m.put("policyResult", c.getPolicyResult());
        m.put("controlPolicyResult", c.getControlPolicyResult());
        m.put("hypotheticalDecision", c.getHypotheticalDecision());
        m.put("ruleResults", read(c.getRuleResults()));
        m.put("inputHash", c.getInputHash());
        m.put("outputHash", c.getOutputHash());
        m.put("contextCheck", c.getContextCheck());
        return json.valueToTree(m);
    }

    private JsonNode render(PolicyReplayService.ReplayView v) {
        List<Object> rows = v.rows().stream().map(r -> (Object) java.util.Map.of(
                "actual", r.actual(), "counterfactual", counterfactualJson(r.counterfactual()),
                "differsFromActualDecision", r.differsFromActualDecision(),
                "differsFromActualPolicyVerdict", r.differsFromActualPolicyVerdict())).toList();
        return json.valueToTree(java.util.Map.of("replayId", v.replay().getId(), "population", v.population(),
                "alternativePolicy", v.replay().getAlternativePolicyCode() + " v" + v.replay().getAlternativePolicyVersion(),
                "inputHash", v.replay().getInputHash(), "outputHash", v.replay().getOutputHash(), "rows", rows));
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
