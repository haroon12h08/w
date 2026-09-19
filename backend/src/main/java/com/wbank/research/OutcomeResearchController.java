package com.wbank.research;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Outcome research: cohorts, outcome definitions, observability, timelines and the descriptive
 * information-state / outcome report. Reads the banking record; writes only research tables.
 */
@RestController
@RequestMapping("/api/v1/research")
public class OutcomeResearchController {

    private final OutcomeResearchService research;

    public OutcomeResearchController(OutcomeResearchService research) {
        this.research = research;
    }

    public record OutcomeDefinitionRequest(@NotBlank String code, @NotNull OutcomeEvaluator.Event event,
                                           Integer thresholdDaysPastDue, @NotNull Integer horizonDays,
                                           String description) {}

    public record CohortRequest(@NotBlank String code, @NotNull Instant decidedFrom, @NotNull Instant decidedTo,
                                Instant knownAt, List<String> includeDecisions, boolean requireInformationCaptured,
                                String description) {}

    public record DefinitionRef(@NotBlank String code, @NotNull Integer version) {}

    public record ReportRequest(@NotBlank String cohortCode, @NotNull Integer cohortVersion,
                                @NotEmpty List<@Valid DefinitionRef> definitions,
                                @NotNull OutcomeResearchService.KnowledgeBasis knowledgeBasis, Instant knownAt) {}

    public record BoundaryRequest(@NotBlank String definitionCode, @NotNull Integer definitionVersion, Instant knownAt) {}

    @PostMapping("/outcome-definitions")
    public ResponseEntity<Map<String, Object>> defineOutcome(@Valid @RequestBody OutcomeDefinitionRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(research.defineOutcome(r.code(), r.event(),
                r.thresholdDaysPastDue(), r.horizonDays(), r.description()));
    }

    @GetMapping("/outcome-definitions/{code}/{version}")
    public OutcomeEvaluator.Definition outcomeDefinition(@PathVariable String code, @PathVariable int version) {
        return research.outcomeDefinition(code, version);
    }

    @PostMapping("/cohorts")
    public ResponseEntity<OutcomeResearchService.CohortDefinition> defineCohort(@Valid @RequestBody CohortRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(research.defineCohort(r.code(), r.decidedFrom(),
                r.decidedTo(), r.knownAt(), r.includeDecisions(), r.requireInformationCaptured(), r.description()));
    }

    @GetMapping("/cohorts/{code}/{version}/membership")
    public Map<String, Object> membership(@PathVariable String code, @PathVariable int version) {
        OutcomeResearchService.Membership m = research.membership(code, version);
        return Map.of("cohort", m.cohort(), "definitionHash", m.definitionHash(), "membershipHash", m.membershipHash(),
                "members", m.members().stream().map(x -> Map.of("decisionId", x.subject().decisionId(),
                        "decision", x.subject().decision(), "decidedAt", x.subject().decidedAt(),
                        "informationState", x.informationState())).toList(),
                "excluded", m.excluded());
    }

    /** Observability and value of one outcome definition for one decision, as known at {@code knownAt}. */
    @GetMapping("/decisions/{decisionId}/outcome")
    public OutcomeEvaluator.Result outcome(@PathVariable UUID decisionId, @RequestParam String definitionCode,
                                           @RequestParam int definitionVersion,
                                           @RequestParam(required = false) Instant knownAt) {
        return research.observe(decisionId, definitionCode, definitionVersion, knownAt);
    }

    @GetMapping("/decisions/{decisionId}/outcome-timeline")
    public Map<String, Object> timeline(@PathVariable UUID decisionId, @RequestParam(required = false) Instant knownAt,
                                        @RequestParam(required = false) Integer horizonDays) {
        return research.timeline(decisionId, knownAt, horizonDays);
    }

    @PostMapping("/outcome-reports")
    public ResponseEntity<Map<String, Object>> report(@Valid @RequestBody ReportRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(research.report(r.cohortCode(), r.cohortVersion(),
                r.definitions().stream().map(d -> Map.entry(d.code(), d.version())).toList(), r.knowledgeBasis(),
                r.knownAt()));
    }

    @GetMapping("/outcome-reports/{id}")
    public Map<String, Object> storedReport(@PathVariable UUID id) {
        return research.storedReport(id);
    }

    @GetMapping("/outcome-reports/{id}/reproduction")
    public OutcomeResearchService.Reproduction reproduce(@PathVariable UUID id) {
        return research.reproduce(id);
    }

    @GetMapping("/cohorts/{code}/{version}/knowledge-comparison")
    public Map<String, Object> knowledgeComparison(@PathVariable String code, @PathVariable int version,
                                                   @RequestParam String definitionCode,
                                                   @RequestParam int definitionVersion,
                                                   @RequestParam Instant laterKnownAt) {
        return research.knowledgeComparison(code, version, definitionCode, definitionVersion, laterKnownAt);
    }

    /** Actual decision, counterfactual decision, observed outcome of the actual decision; counterfactual outcome UNOBSERVED. */
    @PostMapping("/policy-replays/{replayId}/outcome-boundary")
    public ResponseEntity<List<Map<String, Object>>> boundary(@PathVariable UUID replayId,
                                                              @Valid @RequestBody BoundaryRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(research.counterfactualBoundary(replayId,
                r.definitionCode(), r.definitionVersion(), r.knownAt()));
    }
}
