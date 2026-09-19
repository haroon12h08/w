package com.wbank.obligation.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Duration;
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
 * Read-only point-in-time access to lending history, plus append-only corrections and
 * policy versions. Every time parameter is interpreted as an instant (ISO-8601).
 * {@code knownAt} defaults to {@code asOf}: "what the bank knew at that moment".
 */
@RestController
@RequestMapping("/api/v1")
public class HistoryController {

    private final PointInTimeService pit;
    private final DecisionReconstructionService decisions;
    private final LendingDatasetService datasets;
    private final CorrectionService corrections;
    private final CreditPolicyService policies;
    private final ObjectMapper json;
    private final Clock clock;

    public HistoryController(PointInTimeService pit, DecisionReconstructionService decisions,
                             LendingDatasetService datasets, CorrectionService corrections,
                             CreditPolicyService policies, ObjectMapper json, Clock clock) {
        this.pit = pit;
        this.decisions = decisions;
        this.datasets = datasets;
        this.corrections = corrections;
        this.policies = policies;
        this.json = json;
        this.clock = clock;
    }

    public record EventResponse(UUID id, int loanSeq, Long globalSeq, String type, String kind, Instant effectiveAt,
                                Instant recordedAt, JsonNode payload, UUID correctsEventId, String actor,
                                String origin) {}

    public record CorrectionRequest(@NotBlank String field, @NotBlank String correctedValue, @NotBlank String reason) {}

    public record PolicyRequest(@Valid CreditPolicyRules rules, Instant effectiveFrom, @NotBlank String description) {}

    public record PolicyResponse(String policyCode, int version, Instant effectiveFrom, JsonNode rules,
                                 String description, Instant publishedAt, String publishedBy) {}

    @GetMapping("/history/loans/{id}")
    public HistoricalLoanState loan(@PathVariable UUID id, @RequestParam(required = false) Instant asOf,
                                    @RequestParam(required = false) Instant knownAt) {
        return pit.loanAt(id, asOf == null ? clock.instant() : asOf, knownAt);
    }

    @GetMapping("/history/loans/{id}/events")
    public List<EventResponse> events(@PathVariable UUID id, @RequestParam(required = false) Instant knownAt) {
        return pit.events(id, knownAt).stream().map(e -> new EventResponse(e.getId(), e.getLoanSeq(), e.getGlobalSeq(),
                e.getEventType().name(), e.getEventKind().name(), e.getEffectiveAt(), e.getRecordedAt(),
                read(e.getPayload()), e.getCorrectsEventId(), e.getActor(), e.getOrigin())).toList();
    }

    @GetMapping("/history/parties/{partyId}/loans")
    public List<HistoricalLoanState> borrower(@PathVariable UUID partyId, @RequestParam Instant asOf,
                                              @RequestParam(required = false) Instant knownAt) {
        return pit.borrowerAt(partyId, asOf, knownAt);
    }

    @GetMapping("/history/decisions/{decisionId}")
    public DecisionContext decision(@PathVariable UUID decisionId,
                                    @RequestParam(required = false) Instant outcomeKnownAt) {
        return decisions.reconstruct(decisionId, outcomeKnownAt);
    }

    @GetMapping("/history/datasets/credit-decisions")
    public Map<String, Object> dataset(@RequestParam(defaultValue = "P180D") Duration horizon,
                                       @RequestParam(required = false) Instant knownAt) {
        Instant k = knownAt == null ? clock.instant() : knownAt;
        List<LendingDatasetService.Row> rows = datasets.creditDecisions(horizon, k);
        return Map.of("horizon", horizon.toString(), "knownAt", k, "rows", rows,
                "sha256", CanonicalJson.sha256(datasets.canonical(rows, horizon, k)));
    }

    @PostMapping("/history/events/{eventId}/corrections")
    public ResponseEntity<EventResponse> correct(@PathVariable UUID eventId, @Valid @RequestBody CorrectionRequest req) {
        LendingEvent e = corrections.correct(eventId, req.field(), req.correctedValue(), req.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(new EventResponse(e.getId(), e.getLoanSeq(),
                e.getGlobalSeq(), e.getEventType().name(), e.getEventKind().name(), e.getEffectiveAt(),
                e.getRecordedAt(), read(e.getPayload()), e.getCorrectsEventId(), e.getActor(), e.getOrigin()));
    }

    @GetMapping("/credit-policies")
    public List<PolicyResponse> policies() {
        return policies.versions().stream().map(this::render).toList();
    }

    @PostMapping("/credit-policies")
    public ResponseEntity<PolicyResponse> publish(@Valid @RequestBody PolicyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(render(policies.publish(req.rules(), req.effectiveFrom(), req.description())));
    }

    private PolicyResponse render(CreditPolicy p) {
        return new PolicyResponse(p.getPolicyCode(), p.getVersion(), p.getEffectiveFrom(), read(p.getRules()),
                p.getDescription(), p.getPublishedAt(), p.getPublishedBy());
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
